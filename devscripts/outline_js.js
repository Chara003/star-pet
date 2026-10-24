/* ============================================================
   MMD 描边（outline）
   ------------------------------------------------------------
   原理：MMD 的描边 = 把模型【背面】沿法线向外膨胀一圈再画一遍。
     · 复制一份 mesh（共享 geometry + skeleton → 骨骼动画自动同步）
     · 材质换成 BackSide 纯色
     · 顶点着色器里 `transformed += objectNormal * 厚度`
       ★ 注入点必须在 `skinning_vertex` 之后 —— 这时 objectNormal
         已经被骨骼变换过了；在它之前注入会让描边跟着【绑定姿态】走错。

   ★★ 为什么放在【独立的 outlineRoot】而不是挂到 model 下 ★★
     model 下有 20 多处 `model.traverse()` 在处理材质
     （alphaTest / 色彩 / 材质表情 / 配件…），多出来的 mesh 会被误处理。
     放到 scene 下的独立 Group 就完全绕开了这些遍历，
     代价只是每帧同步一次模型的位置/旋转/缩放。

   ★ 宽度不另起炉灶，直接用 PMX 自带的描边数据
     （material.userData.outlineParameters.thickness / color / visible）
     换算成本项目的世界单位 —— MMDLoader 给的 thickness 是
     `edgeSize / 300`（约 0.003），直接拿来用会看不见，
     因为它是在【原始模型尺度】下的偏移量，而我们把模型归一化成了 1.6 高。
   ============================================================ */

/** 描边开关。 */
let OUTLINE_ON = true;
/** 描边宽度 0~100（50 = 手感基准，100 = 两倍粗）。 */
let OUTLINE_W = 50;

let outlineRoot = null;
/** [{ mat, k, visible }] —— k = 该材质的相对粗细系数。 */
let outlineMats = [];

/** 把 0~100 换算成"局部空间的膨胀量"。
 *  ★ 必须除以 baseModelScale：偏移是在顶点局部空间做的，
 *    之后会被 model.scale 缩放，所以要先折算回局部空间。 */
function outlineLocalThickness() {
  const sc = (typeof baseModelScale === 'number' && baseModelScale > 0.0001)
    ? baseModelScale : 1;
  /* w=100 → 世界宽度 0.016（约等于归一化模型高度的 1%） */
  const worldW = (OUTLINE_W / 100) * 0.016;
  return worldW / sc;
}

/** 删除已有描边（换模型 / 关开关时用）。 */
function disposeOutlines() {
  try {
    if (outlineRoot) {
      outlineRoot.traverse(o => {
        if (o.isMesh && o.material) {
          const ms = Array.isArray(o.material) ? o.material : [o.material];
          for (const m of ms) { try { m.dispose(); } catch (e) {} }
        }
      });
      if (outlineRoot.parent) outlineRoot.parent.remove(outlineRoot);
    }
  } catch (e) {}
  outlineRoot = null;
  outlineMats = [];
  nlog('outline: 已移除');
}

/**
 * 为当前模型建立描边。
 * 建议在模型材质全部处理完之后调用（alphaTest / toon 之后）。
 */
function buildOutlines() {
  disposeOutlines();
  if (!OUTLINE_ON || !model) return;
  try {
    const src = model.material;
    if (!src) { nlog('outline: 模型没有材质，跳过'); return; }
    const srcMats = Array.isArray(src) ? src : [src];

    const root = new THREE.Group();
    root.name = '__outlineRoot';

    /* 每个原材质对应一个描边材质（各自持有自己的厚度 uniform） */
    const mats = [];
    for (let i = 0; i < srcMats.length; i++) {
      const sm = srcMats[i];
      const op = (sm && sm.userData) ? sm.userData.outlineParameters : null;
      const visible = op ? !!op.visible : false;
      /* 相对系数：模型自带 thickness 越小（如 0.003），这里越小 */
      const k = op ? Math.max(0.25, Math.min(3.0, (op.thickness || 0.003) / 0.003)) : 1;
      const col = (op && op.color) ? op.color : [0, 0, 0];
      const alpha = (op && op.alpha !== undefined) ? op.alpha : 1;

      const m = new THREE.MeshBasicMaterial({
        color: new THREE.Color(col[0], col[1], col[2]),
        side: THREE.BackSide,
        transparent: alpha < 0.999,
        opacity: alpha,
        depthWrite: true,
        fog: false,
      });
      m.visible = visible;                    // ★ 模型自带的 edgeFlag 要尊重
      m.userData.__outline = true;
      /* 每个材质一个 uniform 对象，滑块改动时逐个更新 */
      m.userData.__u = { value: outlineLocalThickness() * k };
      m.userData.__k = k;
      m.onBeforeCompile = (shader) => {
        shader.uniforms.uOutlineW = m.userData.__u;
        shader.vertexShader =
          'uniform float uOutlineW;\n' + shader.vertexShader;
        shader.vertexShader = shader.vertexShader.replace(
          '#include <skinning_vertex>',
          '#include <skinning_vertex>\n\ttransformed += objectNormal * uOutlineW;'
        );
      };
      mats.push(m);
      outlineMats.push({ mat: m, k: k, visible: visible });
    }

    const om = new THREE.SkinnedMesh(model.geometry, mats);
    om.name = '__outlineMesh';
    om.frustumCulled = false;                 // 膨胀后包围盒会略大，别被裁
    om.castShadow = false;
    om.receiveShadow = false;
    om.userData.__outline = true;
    /* ★ 共享同一个 skeleton / bindMatrix → 骨骼动画与表情完全同步 */
    try { om.bind(model.skeleton, model.bindMatrix); } catch (e) {
      nerr('outline bind failed: ' + (e && e.message));
    }
    root.add(om);
    scene.add(root);
    outlineRoot = root;

    const vis = outlineMats.filter(o => o.visible).length;
    nlog('outline: 建立完成，材质 ' + mats.length + ' 个（可见 ' + vis + '），宽度 '
      + OUTLINE_W + '（局部厚度 ' + (outlineLocalThickness()).toFixed(4) + '）');
    window.__outlineInfo = function () {
      return JSON.stringify({
        on: OUTLINE_ON, width: OUTLINE_W,
        mats: outlineMats.length,
        visible: outlineMats.filter(o => o.visible).length,
        localThickness: +outlineLocalThickness().toFixed(5),
      });
    };
  } catch (e) {
    nerr('buildOutlines failed: ' + (e && e.message));
    disposeOutlines();
  }
}

/** 开关描边。 */
window.__setOutline = function (on) {
  OUTLINE_ON = !!on;
  nlog('outline -> ' + OUTLINE_ON);
  buildOutlines();
  return OUTLINE_ON;
};

/** 设置描边宽度 0~100。 */
window.__setOutlineWidth = function (w) {
  const x = parseFloat(w);
  if (!isFinite(x)) return OUTLINE_W;
  OUTLINE_W = Math.max(0, Math.min(100, Math.round(x)));
  const t = outlineLocalThickness();
  for (const o of outlineMats) {
    try { o.mat.userData.__u.value = t * o.k; } catch (e) {}
  }
  nlog('outline 宽度 -> ' + OUTLINE_W + '（局部厚度 ' + t.toFixed(4) + '）');
  return OUTLINE_W;
};

/** 每帧同步：位置/朝向/缩放 + 表情权重。 */
function updateOutlineTransform() {
  if (!outlineRoot || !model) return;
  try {
    outlineRoot.position.copy(model.position);
    outlineRoot.quaternion.copy(model.quaternion);
    outlineRoot.scale.copy(model.scale);
    /* ★ 表情是顶点形变，morphTargetInfluences 在 mesh 上而不是 geometry 上，
       不复制的话描边会不贴合（表情动、描边不动）。 */
    const om = outlineRoot.children[0];
    if (om && om.morphTargetInfluences && model.morphTargetInfluences) {
      const a = model.morphTargetInfluences, b = om.morphTargetInfluences;
      const n = Math.min(a.length, b.length);
      for (let i = 0; i < n; i++) b[i] = a[i];
    }
  } catch (e) {}
}
