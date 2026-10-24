/* ============================================================
   VMD 动作系统
   ------------------------------------------------------------
   把导入的 .vmd 动作绑定到各种触发时机：
     idle(待机循环) / walk(走路循环) / touchHead / touchBody / touchFeet / greet
   另外支持「待机随机表演」（原生定时触发，页面从动作池里随机抽一个播一次）。

   ★ 为什么要借 MMDAnimationHelper（而不是自己写）：
     VMD 的走路/跑步动作大量依赖【足ＩＫ】—— 腿骨是由 IK 解算出来的；
     不跑 CCDIKSolver 的话上半身在动、下半身僵着。PMX 还有「付与(grant)」
     （腕捩等）。这两块 MMDAnimationHelper 里是现成且久经考验的，
     所以借它的 IK + grant；**物理仍旧由我们自己管**
     （传 physics:false，不创建它的 MMDPhysics，避免和 §14.2 那套顺序打架）。

   ★★ 三个"踩过才知道"的关键点 ★★

   ① 动作剪辑必须【按模型】拟合。
      loadAnimation(url, model) 会按模型的骨骼/表情字典过滤轨道；
      换模型后必须重建，否则一条轨道都绑不上。

   ② ★★★ 建剪辑之前必须把骨骼复位到 rest pose ★★★
      MMDLoader.buildSkeletalAnimation 里这么算位置：
          const basePosition = mesh.skeleton.getBoneByName(key).position.toArray();
          positions.push(basePosition[j] + position[j]);
      ——它用的是【模型当前的骨骼位置】当基准！
      如果建剪辑时骨骼还被上一个动作扭着，那份歪掉的姿态就会被
      **永久烘进新剪辑**。表现：试播了 A 再去试播 B，B 也是歪的；
      甚至换成一个正常的动作还是歪的（实测）。
      修法：建剪辑前 `model.pose()`（从 boneInverses 反推回绑定姿态）。

   ③ 一个插槽里选多个动作时，不能把它们的轨道【合并】成一个剪辑。
      同一个骨骼的轨道被两个动作各写一遍、再按帧号排序 → 关键帧互相穿插
      → 姿态彻底乱掉。正确做法是【每个动作各建一个 Action 同时播放】：
        · 动作 + 表情（Motion + Morph）→ 各管各的轨道，互不干扰，完美
        · 两个全身动作 → 权重各 1，被 AnimationMixer 归一化成各 50% 混合
      （导入时 <名字>_Motion.vmd + <名字>_Morph.vmd 已自动合并，所以
        平常一个插槽只放一个文件就够。）

   ★ 轨道名形态（实测 three r150）：`.bones[上半身].position` /
     `.morphTargetInfluences[3]`。锁根骨骼位移时要按这个形态解析骨骼名。
   ============================================================ */

const VMD_SLOT_KEYS = ['idle', 'walk', 'touchHead', 'touchBody', 'touchFeet', 'greet'];
/** 一次性播放（播完回待机）的插槽。 */
const VMD_ONESHOT_SLOTS = { touchHead: 1, touchBody: 1, touchFeet: 1, greet: 1 };
/** 根骨骼候选：只有这些骨骼的位移才算「整体位移」。 */
const VMD_ROOT_BONES = ['全ての親', 'センター', 'グルーブ', 'ルート',
                        'root', 'Root', 'center', 'Center', 'Center2'];

const VMD_FADE_S = 0.32;          // 层与层之间的交叉过渡
const VMD_ONESHOT_IN_S = 0.16;    // 一次性动作淡入（要快，否则"点了半天没反应"）

const vmd = {
  cfg: null,
  cfgKey: '',              // 配置指纹，避免重复重建
  seq: 0,                  // 构建序号（并发守卫）
  model: null,             // 本轮剪辑是为哪个模型构建的
  helper: null,
  mixer: null,
  /** slot -> [AnimationAction...]（一个插槽可以有多个动作同时播放）。 */
  acts: {},
  /** slot -> 时长（取最长的那个，用于保险丝计时/界面显示）。 */
  durs: {},
  layer: 'idle',           // 当前主层：idle / walk / oneshot
  oneShotSlot: null,
  oneShotBackup: null,
  oneShotTimer: null,
  /** 待恢复的骨骼姿态 + 恢复时间点（见 vmdDeferRestore 的说明）。 */
  restoreBuf: null,
  restoreAt: 0,
  walking: false,
  /** 走动方向：+1 往右 / -1 往左 / 0 不转（见 __setVmdWalking 的说明）。 */
  walkDir: 0,
  /** 目标朝向（-1 / 0 / +1），实际角度由 vmdApplyYaw 平滑插值过去。 */
  yawTarget: 0,
  /** 已经上报过"转向完成"的目标值（避免重复打日志）。 */
  yawApplied: 99,
  appliedYaw: 0,
  spinForced: false,
  spinPrev: null,
  lastErr: '',
};

function vmdLog(m) { nlog('[vmd] ' + m); }
function vmdErr(m) { nerr('[vmd] ' + m); }

/** 某个插槽是否配了动作。 */
function vmdSlotReady(slot) {
  const l = vmd.acts[slot];
  return !!(l && l.length);
}

function vmdSlotActions(slot) {
  const l = vmd.acts[slot];
  return l ? l : [];
}

function vmdAnyRunning(slot) {
  for (const a of vmdSlotActions(slot)) if (a.isRunning()) return true;
  return false;
}

/* ---------------------------------------------------------------- 配置 */

/**
 * 原生下发配置。
 * ★ 这里必须做「指纹去重」：原生会在 页面就绪 / 模型就绪 / 设置改动 三个时机
 *   都会推一次，不去重就会反复重建剪辑（白解码几 MB 动作文件）。
 */
window.__setVmdConfig = function (json) {
  let cfg;
  try {
    cfg = (typeof json === 'string') ? JSON.parse(json) : json;
  } catch (e) {
    vmdErr('config parse failed: ' + e.message);
    return;
  }
  if (!cfg || typeof cfg !== 'object') return;
  const key = JSON.stringify(cfg);
  if (key === vmd.cfgKey && vmd.model === model) {
    vmdLog('config unchanged, skip rebuild');
    return;
  }
  vmd.cfgKey = key;
  vmd.cfg = cfg;
  vmdLog('config: enabled=' + !!cfg.enabled
    + ' slots=' + VMD_SLOT_KEYS.map(k => k + ':' + ((cfg.slots && cfg.slots[k]) || []).length).join(' ')
    + ' rootLock=' + !!cfg.rootLock + ' pool=' + ((cfg.pool || []).length));
  vmdRebuild('config');
};

/* ---------------------------------------------------------------- 复位 */

/**
 * 把骨骼恢复到绑定姿态（rest pose）。
 * ★ 为什么必须做：见文件头 ② 的说明 —— 否则 MMDLoader 会把"当前歪掉的
 *   骨骼位置"当成基准烘进新剪辑，导致后续所有动作都是歪的。
 */
function vmdResetPose() {
  try {
    if (!model) return;
    if (typeof model.pose === 'function') {
      model.pose();                       // SkinnedMesh.pose() -> skeleton.pose()
    } else {
      model.traverse(o => { if (o.isSkinnedMesh && o.pose) o.pose(); });
    }
    model.updateMatrixWorld(true);
    vmdLog('骨骼已复位到绑定姿态（建剪辑前必须做，见文件头 ②）');
  } catch (e) {
    vmdErr('pose reset failed: ' + (e && e.message));
  }
}

/* ---------------------------------------------------------------- 构建 */

/** 把 slot 的 id 列表变成 URL 数组（原生已编码好，这里只兜底）。 */
function vmdSlotUrls(slot) {
  const s = (vmd.cfg && vmd.cfg.slots) ? vmd.cfg.slots[slot] : null;
  return Array.isArray(s) ? s.filter(u => typeof u === 'string' && u.length > 0) : [];
}

function vmdTeardown() {
  try {
    if (vmd.mixer) vmd.mixer.stopAllAction();
  } catch (e) {}
  vmd.mixer = null;
  vmd.helper = null;
  vmd.acts = {};
  vmd.durs = {};
  vmd.model = null;
  vmd.layer = 'idle';
  vmd.oneShotSlot = null;
  vmd.oneShotBackup = null;
  vmd.restoreBuf = null;
  vmd.restoreAt = 0;
  vmd.walking = false;
  vmd.walkDir = 0;
  vmd.yawTarget = 0;
  vmd.yawApplied = 99;
  vmd.appliedYaw = 0;
  if (vmd.oneShotTimer) { clearTimeout(vmd.oneShotTimer); vmd.oneShotTimer = null; }
  if (model) { try { model.rotation.y = 0; } catch (e) {} }
}

/**
 * 重新构建全部动作剪辑。
 * 触发时机：收到配置 / 模型加载完成 / 换模型。
 */
async function vmdRebuild(reason) {
  const mySeq = ++vmd.seq;
  try {
    if (!model) { vmdLog('rebuild(' + reason + '): 模型还没好，等模型就绪后再建'); return; }
    /* ★★ 建剪辑前先复位骨骼（文件头 ② 的核心修复）。
       必须在 vmdTeardown 之后、loadAnimation 之前。 */
    vmdTeardown();
    vmdResetPose();
    if (!vmd.cfg || !vmd.cfg.enabled) {
      vmdLog('rebuild(' + reason + '): VMD 总开关关闭 -> 已复位到绑定姿态');
      return;
    }
    vmd.model = model;

    const loader = new MMDLoader();
    const loaded = {};                 // slot -> [clip...]
    let built = 0, failed = 0;

    for (const slot of VMD_SLOT_KEYS) {
      const urls = vmdSlotUrls(slot);
      if (!urls.length) continue;
      const list = [];
      for (const url of urls) {
        try {
          /* ★ 每个文件【单独】建剪辑（不能传数组：数组会合并轨道，
             同一个骨骼被两个动作各写一遍再按帧排序 → 姿态全乱，见文件头 ③）。 */
          const clip = await new Promise((res, rej) => {
            loader.loadAnimation(url, model, res, undefined,
              e => rej(e instanceof Error ? e : new Error(String((e && e.message) || e))));
          });
          if (mySeq !== vmd.seq) { vmdLog('rebuild(' + reason + '): 已被取代，放弃'); return; }
          if (!clip || !clip.tracks || clip.tracks.length === 0) {
            vmdErr('slot ' + slot + ' 的 ' + decodeURIComponent(url.split('/').pop())
              + ' 轨道为空（动作没绑定到本模型？）');
            failed++;
            continue;
          }
          if (vmd.cfg.rootLock !== false) vmdLockRootMotion(clip);
          list.push({ url: url, clip: clip });
          built++;
          vmdLog('slot ' + slot + ' + ' + decodeURIComponent(url.split('/').pop())
            + '：' + clip.tracks.length + ' 轨 ' + clip.duration.toFixed(2) + 's');
        } catch (e) {
          failed++;
          vmdErr('slot ' + slot + ' 加载失败（' + decodeURIComponent(url.split('/').pop())
            + '）: ' + (e && e.message));
        }
      }
      if (list.length) loaded[slot] = list;
    }

    if (mySeq !== vmd.seq) return;
    if (built === 0) {
      vmdLog('rebuild(' + reason + '): 没有可用的动作' + (failed ? ('（失败 ' + failed + '）') : ''));
      return;
    }

    /* ---- 混合器 + IK/付与 ---- */
    vmd.helper = new MMDAnimationHelper({
      sync: false,
      afterglow: 0,
      resetPhysicsOnLoop: false,
    });
    /* 物理归我们自己管（见 §14.2 的执行顺序），这里只要 IK + grant。
       ★ 必须传 animation 数组（哪怕是空的）—— 否则 helper 不会创建 mixer，
         而它内部的 IK/grant 解算恰好被包在 `if (mixer)` 里，等于不跑。 */
    vmd.helper.enabled.physics = false;
    vmd.helper.add(model, { animation: [], physics: false });
    vmd.mixer = vmd.helper.objects.get(model).mixer;
    vmdOptimizeIK();

    const mmd = (model.geometry && model.geometry.userData && model.geometry.userData.MMD) || {};
    vmdLog('helper ready: iks=' + ((mmd.iks || []).length)
      + ' grants=' + ((mmd.grants || []).length)
      + ' rigidBodies=' + ((mmd.rigidBodies || []).length));

    /* ---- 建 action（一个插槽可以多个） ---- */
    for (const slot of VMD_SLOT_KEYS) {
      const list = loaded[slot];
      if (!list || !list.length) continue;
      const arr = [];
      let maxDur = 0;
      for (const it of list) {
        const act = vmd.mixer.clipAction(it.clip);
        if (VMD_ONESHOT_SLOTS[slot]) {
          act.setLoop(THREE.LoopOnce, 1);
          act.clampWhenFinished = true;
        } else {
          act.setLoop(THREE.LoopRepeat, Infinity);
        }
        arr.push(act);
        if (it.clip.duration > maxDur) maxDur = it.clip.duration;
      }
      vmd.acts[slot] = arr;
      vmd.durs[slot] = maxDur;
      if (arr.length > 1) {
        vmdLog('注意：' + slot + ' 配了 ' + arr.length
          + ' 个动作，会【同时播放并按权重混合】（只适合 动作+表情 这类互补组合）');
      }
    }

    vmd.mixer.addEventListener('finished', vmdOnActionFinished);

    /* ---- 起播 ---- */
    vmd.layer = 'idle';
    if (vmd.acts.idle) {
      vmdPlayList('idle', 0, true);
      vmdLog('idle 动作开始循环播放（' + vmd.acts.idle.length + ' 个）');
    }
    if (vmd.walking && vmd.acts.walk) vmdSwitchLayer('walk', 0);
    vmdCheckWalkSmoothness();
    vmdLog('rebuild(' + reason + ') 完成: ' + built + ' 个动作就绪');
  } catch (e) {
    vmdErr('rebuild(' + reason + ') 异常: ' + (e && e.message));
  }
}

/** 播放（或淡入）某个插槽的全部 action。 */
function vmdPlayList(slot, fade, isFirst) {
  const f = (fade === undefined) ? VMD_FADE_S : fade;
  for (const a of vmdSlotActions(slot)) {
    a.reset().setEffectiveWeight(1);
    if (f > 0) a.fadeIn(f);
    a.play();
  }
}

/** 淡出某个插槽的全部 action。 */
function vmdFadeOutList(slot, fade) {
  const f = (fade === undefined) ? VMD_FADE_S : fade;
  for (const a of vmdSlotActions(slot)) {
    try { a.fadeOut(f); } catch (e) {}
  }
}

/**
 * 锁住根骨骼的水平位移 → 原地播放。
 *
 * ★ 走路动作的文件名里那个「前移動20」就是烘焙进去的位移；
 *   不锁的话模型会在窗口里一路走偏（窗口本身也在移动，等于双倍位移）。
 * ★ 保留 Y（上下起伏/跳跃），只把 X/Z 压成第 0 帧的值。
 */
function vmdLockRootMotion(clip) {
  let locked = 0;
  for (const t of clip.tracks) {
    const m = /^\.bones\[(.+?)\]\.position$/.exec(t.name);
    if (!m) continue;
    if (VMD_ROOT_BONES.indexOf(m[1]) < 0) continue;
    const v = t.values;
    if (!v || v.length < 3) continue;
    const x0 = v[0], z0 = v[2];
    for (let i = 0; i < v.length; i += 3) {
      v[i] = x0;
      v[i + 2] = z0;
    }
    locked++;
  }
  if (locked) vmdLog('锁定根骨骼位移: ' + locked + ' 条轨道（原地播放）');
}

/** IK 链接里被物理驱动的骨骼要禁用（否则 IK 和物理互相抢）。 */
function vmdOptimizeIK() {
  try {
    if (vmd.helper && model) vmd.helper._optimizeIK(model, !!physics);
  } catch (e) {
    vmdLog('optimizeIK skipped: ' + (e && e.message));
  }
}

/* ---------------------------------------------------------------- 播放 */

/** 切主层（idle <-> walk），带交叉过渡。 */
function vmdSwitchLayer(slot, fade) {
  if (vmd.layer === slot && vmdAnyRunning(slot)) return;
  const from = (vmd.layer === 'oneshot') ? vmd.oneShotSlot : vmd.layer;
  /* ★ 从一次性动作切走（比如漫游开始了）→ 它的姿态必须登记恢复，
     否则骨骼会永久停在那个动作的中间帧（实测的"奇怪的动作"）。 */
  if (vmd.layer === 'oneshot' && vmd.oneShotBackup) {
    vmdDeferRestore(vmd.oneShotBackup, (VMD_FADE_S + 0.05) * 1000);
    vmd.oneShotBackup = null;
    vmd.oneShotSlot = null;
    if (vmd.oneShotTimer) { clearTimeout(vmd.oneShotTimer); vmd.oneShotTimer = null; }
  }
  vmd.layer = slot;
  /* ★ 走路动画是 30fps 录的：帧率上限低于 30 时会跳帧播放（一卡一卡）。
     这里通知原生在走动期间临时放宽，走完恢复。 */
  if (slot === 'walk') vmdNotifyWalkFps(true);
  else if (from === 'walk') vmdNotifyWalkFps(false);

  const f = (fade === undefined) ? VMD_FADE_S : fade;
  if (from && from !== slot) vmdFadeOutList(from, f);
  if (!vmdSlotReady(slot)) {
    /* 目标层没配动作 → 退回待机（没有就什么都不播） */
    vmdLog('切层 ' + from + ' -> ' + slot + '（该插槽没配动作，退回待机）');
    vmd.layer = 'idle';
    if (vmdSlotReady('idle')) vmdPlayList('idle', VMD_FADE_S);
    return;
  }
  vmdPlayList(slot, f);
  vmdLog('切层 ' + from + ' -> ' + slot + '（' + (vmd.durs[slot] || 0).toFixed(2) + 's'
    + (vmd.acts[slot].length > 1 ? '，' + vmd.acts[slot].length + ' 个混合' : '') + '）');
}

/** 播一次性动作（摸头/摸身/摸脚/见面）。 */
function vmdPlayOneShot(slot) {
  const list = vmdSlotActions(slot);
  if (!list.length) {
    vmdLog('一次性动作 ' + slot + ' 没配，跳过');
    return false;
  }
  /* ★ 先把上一轮遗留的待恢复姿态处理掉（否则两个动作的残留会叠在一起）。 */
  if (vmd.restoreBuf) {
    vmdRestoreBones(vmd.restoreBuf);
    vmd.restoreBuf = null;
  }
  /* ★ 播放前备份全身骨骼。
     原因：MMD 的剪辑只包含它用到的那几根骨骼，动作结束后
     那些「只有它才动」的骨骼会停在最后一帧（比如土下座就一直跪着）。
     备份 + 播完复位可以干净地回到待机姿态。 */
  vmd.oneShotBackup = vmdSnapshotBones();

  /* 上一个一次性动作可能还在跑（连点两下）→ 先让它淡出 */
  const prevOneShot = (vmd.layer === 'oneshot') ? vmd.oneShotSlot : null;
  if (prevOneShot && prevOneShot !== slot) vmdFadeOutList(prevOneShot, VMD_ONESHOT_IN_S);
  /* 当前层让位（idle 或 walk） */
  const from = (vmd.layer === 'oneshot') ? null : vmd.layer;
  if (from) vmdFadeOutList(from, VMD_ONESHOT_IN_S);

  vmd.oneShotSlot = slot;
  vmd.layer = 'oneshot';
  vmdPlayList(slot, VMD_ONESHOT_IN_S);

  const dur = vmd.durs[slot] || 3;
  /* 保险丝：万一 'finished' 没来（被 fadeOut 打断等），也要能回到待机 */
  if (vmd.oneShotTimer) clearTimeout(vmd.oneShotTimer);
  vmd.oneShotTimer = setTimeout(() => vmdEndOneShot(slot), (dur + 1.2) * 1000);
  vmdLog('播放一次性动作 ' + slot + '（' + dur.toFixed(2) + 's，'
    + list.length + ' 段）');
  return true;
}

function vmdOnActionFinished(e) {
  const a = e && e.action;
  if (!a) return;
  for (const slot of VMD_SLOT_KEYS) {
    if (vmdSlotActions(slot).indexOf(a) >= 0 && VMD_ONESHOT_SLOTS[slot]) {
      vmdEndOneShot(slot);
      return;
    }
  }
  if (vmdSlotActions('__random').indexOf(a) >= 0) vmdEndOneShot('__random');
}

/**
 * 结束一次性动作并回到小层（walk / idle）。
 *
 * ★ 注意本函数【只负责切层 + 登记恢复】，不直接恢复骨骼 ——
 *   直接恢复会被随后的 mixer/saveBones 覆盖，而且打断场景下会漏掉。
 *   恢复统一交给 vmdDeferRestore（见那里的说明）。
 */
function vmdEndOneShot(slot) {
  /* 已经被别的动作接管了 → 仍然要登记恢复（旧实现直接 return 就是漏恢复的根源） */
  if (vmd.oneShotSlot !== slot) {
    if (vmd.oneShotBackup) {
      vmdDeferRestore(vmd.oneShotBackup, (VMD_FADE_S + 0.05) * 1000);
      vmd.oneShotBackup = null;
    }
    return;
  }
  if (vmd.oneShotTimer) { clearTimeout(vmd.oneShotTimer); vmd.oneShotTimer = null; }
  vmdFadeOutList(slot, VMD_FADE_S);
  vmd.oneShotSlot = null;

  /* 回到小层：走路中回走路，否则回待机 */
  const back = (vmd.walking && vmdSlotReady('walk')) ? 'walk' : 'idle';
  vmd.layer = back;
  if (vmdSlotReady(back)) vmdPlayList(back, VMD_FADE_S);

  /* ★ 等淡出结束再恢复姿态，避免看到"啪"地弹一下 */
  if (vmd.oneShotBackup) {
    vmdDeferRestore(vmd.oneShotBackup, (VMD_FADE_S + 0.05) * 1000);
    vmd.oneShotBackup = null;
  }
  vmdLog('一次性动作 ' + slot + ' 结束 -> ' + back);
}

/* ---------------------------------------------------------------- 骨骼快照 */

function vmdSnapshotBones() {
  try {
    const bones = model.skeleton.bones;
    const buf = new Float32Array(bones.length * 7);
    for (let i = 0; i < bones.length; i++) {
      const b = bones[i];
      b.position.toArray(buf, i * 7);
      b.quaternion.toArray(buf, i * 7 + 3);
    }
    return buf;
  } catch (e) {
    return null;
  }
}

function vmdRestoreBones(buf) {
  try {
    if (!buf || !model) return;
    const bones = model.skeleton.bones;
    if (buf.length < bones.length * 7) return;
    for (let i = 0; i < bones.length; i++) {
      const b = bones[i];
      b.position.fromArray(buf, i * 7);
      b.quaternion.fromArray(buf, i * 7 + 3);
    }
  } catch (e) {}
}

/**
 * 延迟恢复骨骼姿态（一次性动作播完后回到待机姿态）。
 *
 * ★★ 为什么不用"结束事件里直接恢复"（踩过的坑，2026-10-06）★★
 *   旧实现把恢复绑在 `vmdEndOneShot(slot)` 里，而那个函数开头是
 *   `if (vmd.oneShotSlot !== slot) return;` —— 于是：
 *     · 动作被【打断】（漫游开始 / 又播了另一个动作）→ 前一个的恢复被跳过
 *     · 新动作会【覆盖】`vmd.oneShotBackup` → 旧备份直接丢了
 *   结果：骨骼永久停在那个动作的最后一帧（用户看到"奇怪的动作"）。
 *
 * 现在改成"挂起式恢复"：无论从哪条路径离开 oneshot 层，
 * 都只是登记一次待恢复；真正的恢复在 vmdUpdate 里做，
 * 条件是【已过淡出时间】且【当前不在 oneshot 层】（否则会打断新动作）。
 */
function vmdDeferRestore(buf, delayMs) {
  if (!buf) return;
  /* 已经有一个待恢复的更早姿态 → 保留更早的那个（那才是"干净"的原始姿态） */
  if (vmd.restoreBuf) return;
  vmd.restoreBuf = buf;
  vmd.restoreAt = performance.now() + Math.max(0, delayMs);
}

/* ---------------------------------------------------------------- 每帧 */

function vmdUpdate(dt) {
  /* 待恢复的骨骼：到点了、且当前不在一次性动作里，就恢复。 */
  if (vmd.restoreBuf && vmd.layer !== 'oneshot'
      && performance.now() >= vmd.restoreAt) {
    vmdRestoreBones(vmd.restoreBuf);
    vmd.restoreBuf = null;
  }
  if (!vmd.mixer || !vmd.helper) return;
  try {
    /* ★ helper.update 内部就是 MMD 标准顺序：
         restoreBones → mixer.update → saveBones → IK → grant
       物理不归它管（已 physics:false），我们照旧在它之后自己跑。 */
    vmd.helper.update(dt);
  } catch (e) {
    vmd.lastErr = String(e && e.message);
    vmdErr('update failed: ' + vmd.lastErr);
  }
  vmdApplyYaw(dt);   // ★ 必须传 dt：转身是平滑插值的，不传就永远转不过去
}

/**
 * 转身（侧身朝行进方向）。
 *
 * ★ 用 Y 轴旋转，不用 scale.x = -1 —— 镜像会翻法线/贴图，
 *   模型会变成"反面朝外"的怪异样子。
 * ★ 自转开着时不抢 rotation.y（那是自转在管；漫游开启时自转已被强制关闭）。
 * ★ 平滑过渡：直接跳到 90° 会"啪"地闪一下，改成按速度插值过去。
 */
function vmdApplyYaw(dt) {
  if (!model) return;
  const want = (vmd.yawTarget || 0) * (Math.PI / 2);   // ±90° 或 0
  const cur = model.rotation.y;

  if (Math.abs(cur - want) < 0.002) {
    if (vmd.yawApplied !== want) {
      model.rotation.y = want;
      vmd.appliedYaw = want;
      /* ★ 注意这里用独立的 yawApplied 做"是否已上报"的标记。
         以前用 appliedYaw 判断，而插值分支里每帧都会更新它 ——
         最后一步正好落在目标值时 appliedYaw === want，
         于是这条"转向完成"的日志永远打不出来（排查时被误导过一次）。 */
      vmd.yawApplied = want;
      vmdLog('模型朝向 -> ' + yawText(vmd.yawTarget || 0)
        + '（rotation.y=' + want.toFixed(3) + '）');
    }
    return;
  }
  /* 自转在跑就别插手（否则两边抢 rotation.y，模型会乱转） */
  if (SPIN_SPEED > 0 && vmd.yawTarget === 0) { vmd.appliedYaw = cur; return; }
  if (!(dt > 0)) return;

  /* 角速度约 450°/秒 —— 转 90° 大约 0.2 秒，够快又不突兀 */
  const speed = Math.PI * 2.5;
  const delta = want - cur;
  const step = Math.sign(delta) * Math.min(Math.abs(delta), speed * dt);
  model.rotation.y = cur + step;
  vmd.appliedYaw = model.rotation.y;
}

function yawText(d) {
  if (d > 0) return '朝右(+90°)';
  if (d < 0) return '朝左(-90°)';
  return '正向(0°)';
}

/* ---------------------------------------------------------------- 诊断 */

/**
 * 通知原生：走动期间需要高帧率。
 *
 * ★ 为什么要这么做（实测"走路一卡一卡的"）：
 *   走路动作是 30fps 录制的（40 帧 = 1.33 秒一圈）。
 *   如果用户的「帧率上限」设成 20，渲染循环会跳帧 ——
 *   动画按 dt 走但渲染不连续，看起来就是一顿一顿。
 *   走动只有几秒，临时放宽到 30 的代价可以接受，走完立刻恢复。
 */
function vmdNotifyWalkFps(walking) {
  try {
    if (B && B.setWalkFps) B.setWalkFps(walking);
  } catch (e) {}
}

/**
 * 走路"一卡一卡"的成因排查（实测）。
 *
 * 已知成因有两类，判别方法不同：
 *  ① 帧率上限 < 30：走路动画是 30fps 录的，帧率被限到 20 就会跳帧播放。
 *     → 原生侧会在走动期间临时放宽上限（见 vmdNotifyWalkFps）。
 *  ② 循环点姿态不一致：VMD 首帧和末帧姿态不同，LoopRepeat 回到 0 时会"抽一下"。
 *     → 这里算出首末帧的骨骼位移/旋转差，超过阈值就打警告。
 */
function vmdCheckWalkSmoothness() {
  try {
    const acts = vmdSlotActions('walk');
    if (!acts.length) return;
    let maxPosDiff = 0, maxRotDiff = 0;
    for (const a of acts) {
      const clip = a.getClip();
      for (const t of clip.tracks) {
        const v = t.values;
        if (!v || v.length < 3) continue;
        if (t.name.endsWith('.position')) {
          const n = v.length - 3;
          const d = Math.abs(v[0] - v[n]) + Math.abs(v[1] - v[n + 1]) + Math.abs(v[2] - v[n + 2]);
          if (d > maxPosDiff) maxPosDiff = d;
        } else if (t.name.endsWith('.quaternion')) {
          const n = v.length - 4;
          const d = Math.abs(v[0] - v[n]) + Math.abs(v[1] - v[n + 1])
                  + Math.abs(v[2] - v[n + 2]) + Math.abs(v[3] - v[n + 3]);
          if (d > maxRotDiff) maxRotDiff = d;
        }
      }
    }
    vmdLog('walk 循环点差异: 位移=' + maxPosDiff.toFixed(3)
      + ' 旋转=' + maxRotDiff.toFixed(3)
      + (maxPosDiff > 0.05 || maxRotDiff > 0.05
         ? '  ← 偏大，循环处会"抽一下"（该动作首末帧姿态不一致）' : '  ← 正常'));
  } catch (e) {}
}

/**
 * 手工复位：把骨骼恢复到绑定姿态。
 * 万一遇到残留的怪异姿态，试播页/调试可以用它一键救回来。
 */
window.__vmdResetPose = function () {
  vmdResetPose();
  return true;
};

/* ---------------------------------------------------------------- 对外接口 */

/** 播某个插槽（原生：摸头/摸身/摸脚/见面/试播按钮）。 */
window.__playVmdSlot = function (slot) {
  if (VMD_SLOT_KEYS.indexOf(slot) < 0) return false;
  if (!vmd.mixer) {
    vmdLog('__playVmdSlot(' + slot + ')：VMD 未就绪（没配动作 / 模型还没加载完）');
    return false;
  }
  if (slot === 'idle') { vmdSwitchLayer('idle', 0); return true; }
  if (slot === 'walk') { vmdSwitchLayer('walk', 0); return true; }
  return vmdPlayOneShot(slot);
};

/**
 * 漫游状态：走路时切走路循环 + 侧身朝向。
 *
 * ★★ 朝向到底该转多少度 —— 两次搞错后的结论（2026-10-06）★
 *   模型 rotation.y = 0 时是【面向镜头】的（这是 MMD 观看器的通常摆法）。
 *   用户要的是「侧身朝行进方向走」，不是 180° 掉头：
 *     · 往右走 → rotation.y = +90°（正面朝屏幕右）
 *     · 往左走 → rotation.y = -90°（正面朝屏幕左）
 *     · 待机   → 0°（正面对着镜头）
 *   历史弯路：先做成「往左转 180°」（结果往左走背对屏幕），
 *   又改成「往右转 180°」（结果往右走背对屏幕）——
 *   两次都不对，因为用户要的本来就是 90° 侧身。
 *
 * @param walking 是否在走
 * @param dir     +1 = 往右走，-1 = 往左走，0 = 不转身
 */
window.__setVmdWalking = function (walking, dir) {
  vmd.walking = !!walking;
  const d = (dir === 1 || dir === -1) ? dir : 0;
  vmd.walkDir = d;
  /* 用户在设置里关掉"走动时自动转身"就一律不转 */
  const allowed = !(vmd.cfg && vmd.cfg.faceLeft === false);
  const want = (vmd.walking && allowed) ? d : 0;
  if (vmd.yawTarget !== want) {
    vmd.yawTarget = want;
    vmdLog('漫游朝向: ' + yawText(want)
      + (want ? '（侧身朝行进方向）' : ''));
  }
  if (!vmd.mixer) return;
  if (vmd.layer === 'oneshot') return;      // 一次性动作播放期间不打断
  vmdSwitchLayer(vmd.walking ? 'walk' : 'idle', VMD_FADE_S);
};

/** 单独设置朝向（走完一段 / 漫游停止时复位成 0 = 正面对着镜头）。 */
window.__setVmdFacing = function (dir) {
  const d = (dir === 1 || dir === -1) ? dir : 0;
  if (vmd.yawTarget === d && vmd.walkDir === d) return;   // ★ 去重，别刷屏
  vmd.walkDir = d;
  vmd.yawTarget = d;
  vmdLog('朝向复位 -> ' + yawText(d));
};

/**
 * 待机随机表演：从原生给的候选池里随机抽一个播一次。
 * ★ 抽签放在页面（页面才知道每个动作多长、加载成没成），
 *   但【什么时候该播】由原生决定（拖拽/对话/互动/漫游中不播）。
 */
window.__playVmdRandom = function () {
  try {
    const pool = (vmd.cfg && Array.isArray(vmd.cfg.pool)) ? vmd.cfg.pool : [];
    if (!pool.length) { vmdLog('随机表演：候选池为空'); return false; }
    if (!vmd.mixer || !model) { vmdLog('随机表演：VMD 未就绪'); return false; }

    /* 优先复用"已经配好、能直接播"的插槽（省一次解码） */
    const candidates = [];
    for (const slot of VMD_SLOT_KEYS) {
      if (!VMD_ONESHOT_SLOTS[slot]) continue;
      const urls = vmdSlotUrls(slot);
      if (urls.length && urls.some(u => pool.indexOf(u) >= 0) && vmdSlotReady(slot)) {
        candidates.push(slot);
      }
    }
    if (candidates.length) {
      const pick = candidates[Math.floor(Math.random() * candidates.length)];
      vmdLog('随机表演(复用已加载动作): ' + pick);
      return vmdPlayOneShot(pick);
    }

    /* 否则现场加载一个（随机池里但没绑到任何插槽的动作） */
    const url = pool[Math.floor(Math.random() * pool.length)];
    vmdLog('随机表演(临时加载): ' + decodeURIComponent(url.split('/').pop()));
    const loader = new MMDLoader();
    loader.loadAnimation(url, model,
      clip => {
        try {
          if (vmd.cfg && vmd.cfg.rootLock !== false) vmdLockRootMotion(clip);
          const a = vmd.mixer.clipAction(clip);
          a.setLoop(THREE.LoopOnce, 1);
          a.clampWhenFinished = true;
          vmd.acts.__random = [a];
          vmd.durs.__random = clip.duration;
          vmdPlayOneShot('__random');
        } catch (e) {
          vmdErr('随机表演播放失败: ' + (e && e.message));
        }
      },
      undefined,
      e => vmdErr('随机表演加载失败: ' + (e && e.message)));
    return true;
  } catch (e) {
    vmdErr('随机表演异常: ' + (e && e.message));
    return false;
  }
};

/**
 * 漫游开启/关闭时由原生调用。
 * ★ 开启漫游必须强制关掉自转：自转在写 model.rotation.y，
 *   而漫游的「往右/往左转身」也在写同一个值 —— 两者会互相打架。
 */
window.__setSpinForRoam = function (on) {
  if (on) {
    if (vmd.spinPrev === null) vmd.spinPrev = SPIN_SPEED;
    SPIN_SPEED = 0;
    SPIN = false;
    vmd.spinForced = true;
    vmdLog('漫游开启 -> 强制关闭自转（原速度 ' + vmd.spinPrev + '，关闭漫游后恢复）');
    /* 面板上的滑块也跟着归零，避免界面与实际不一致
       （纯设置 value 不会触发 input 事件，所以文本要手动写） */
    const sp = document.getElementById('sSpin');
    const spv = document.getElementById('vSpin');
    if (sp) sp.value = '0';
    if (spv) spv.textContent = '0.00';
  } else if (vmd.spinForced) {
    SPIN_SPEED = (vmd.spinPrev === null) ? 0 : vmd.spinPrev;
    SPIN = SPIN_SPEED > 0;
    const sp = document.getElementById('sSpin');
    const spv = document.getElementById('vSpin');
    if (sp) sp.value = String(SPIN_SPEED);
    if (spv) spv.textContent = SPIN_SPEED.toFixed(2);
    vmd.spinPrev = null;
    vmd.spinForced = false;
    vmdLog('漫游关闭 -> 恢复自转速度 ' + SPIN_SPEED);
  }
};

/** 诊断信息（供原生/日志排查）。 */
window.__vmdInfo = function () {
  try {
    const o = {
      enabled: !!(vmd.cfg && vmd.cfg.enabled),
      ready: !!vmd.mixer,
      model: vmd.model ? 'yes' : 'no',
      layer: vmd.layer,
      walking: vmd.walking,
      walkDir: vmd.walkDir,
      yawTarget: vmd.yawTarget,
      slots: {},
      err: vmd.lastErr,
    };
    for (const slot of VMD_SLOT_KEYS) {
      const arr = vmdSlotActions(slot);
      o.slots[slot] = arr.length
        ? (arr.length + '段/' + (vmd.durs[slot] || 0).toFixed(2) + 's'
           + (vmdAnyRunning(slot) ? ' [运行]' : ''))
        : '-';
    }
    return JSON.stringify(o);
  } catch (e) {
    return '{"err":"' + String(e && e.message) + '"}';
  }
};
