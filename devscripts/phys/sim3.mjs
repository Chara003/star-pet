// ★ 真实复现 index.html 的加载链路，做三组对照，判定"缩放放哪里"才不震荡
//   node sim3.mjs [mode] [steps]
//     mode = mesh     : 缩放设在 mesh 自己身上（当前 App 做法）
//            holder   : 缩放设在父级 Group 上（mesh.scale 恒 1）
//            none     : 完全不缩放（靠相机适配）
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { createRequire } from 'module';

const require = createRequire(import.meta.url);
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const AmmoFactory = require('./ammo.wasm.js');
const Ammo = await AmmoFactory({ wasmBinary: fs.readFileSync(path.join(__dirname, 'ammo.wasm.wasm')) });
globalThis.Ammo = Ammo;
const THREE = await import('./three.mjs');
const { MMDParser } = await import('./mmdparser.mjs');
const { MMDPhysics } = await import('./MMDPhysics.node.mjs');

const MODE = process.argv[2] || 'mesh';
const NOROOT = process.argv[4] || '';   // 逗号分隔的开关: noscene,nopose,nowarm
const STEPS = Number(process.argv[3] || 600);
const TARGET_HEIGHT = 1.6;

// ---------- 解析 ----------
const buf = fs.readFileSync(new URL('../../in/kei.pmx', import.meta.url));
const data = new MMDParser.Parser().parsePmx(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength), true);
const B = data.bones;

const boneType = {};
for (const rb of data.rigidBodies) {
  const v = boneType[rb.boneIndex];
  boneType[rb.boneIndex] = v === undefined ? rb.type : Math.max(rb.type, v);
}
const gbones = B.map((bd, i) => {
  const o = { parent: bd.parentIndex, name: bd.name, pos: bd.position.slice(0, 3),
              rotq: [0, 0, 0, 1], rbt: boneType[i] ?? -1 };
  if (o.parent !== -1) {
    o.pos[0] -= B[o.parent].position[0];
    o.pos[1] -= B[o.parent].position[1];
    o.pos[2] -= B[o.parent].position[2];
  }
  return o;
});
const rigidBodies = data.rigidBodies.map(rb => {
  const p = { ...rb, position: rb.position.slice(), rotation: rb.rotation.slice() };
  if (p.boneIndex !== -1) {
    const bn = B[p.boneIndex];
    p.position[0] -= bn.position[0];
    p.position[1] -= bn.position[1];
    p.position[2] -= bn.position[2];
  }
  return p;
});
const constraints = data.constraints.map(c => ({
  ...c,
  translationLimitation1: c.translationLimitation1.slice(),
  translationLimitation2: c.translationLimitation2.slice(),
  rotationLimitation1: c.rotationLimitation1.slice(),
  rotationLimitation2: c.rotationLimitation2.slice(),
  springPosition: c.springPosition.slice(),
  springRotation: c.springRotation.slice(),
}));
for (const c of constraints) {
  const a = rigidBodies[c.rigidBodyIndex1], b = rigidBodies[c.rigidBodyIndex2];
  if (a.type !== 0 && b.type === 2 && a.boneIndex !== -1 && b.boneIndex !== -1
      && B[b.boneIndex].parentIndex === a.boneIndex) b.type = 1;
}

// ---------- 部位筛选 + 约束闭包 ----------
const RE = /hair|髪|胸|bust|skirt|スカート|しっぽ|尻尾|尾|ネクタイ|tie/i;
const seed = new Set();
rigidBodies.forEach((rb, i) => {
  const nm = rb.boneIndex >= 0 ? (B[rb.boneIndex].name || '') : '';
  if (RE.test(nm)) seed.add(i);
});
let ch = true, r = 0;
while (ch && r < 50) {
  ch = false; r++;
  for (const c of constraints) {
    const a = c.rigidBodyIndex1, b = c.rigidBodyIndex2;
    if (seed.has(a) && !seed.has(b)) { seed.add(b); ch = true; }
    else if (seed.has(b) && !seed.has(a)) { seed.add(a); ch = true; }
  }
}
const map = {}; const useB = [];
rigidBodies.forEach((rb, i) => { if (seed.has(i)) { map[i] = useB.length; useB.push(rb); } });
const useC = constraints.filter(c => map[c.rigidBodyIndex1] !== undefined && map[c.rigidBodyIndex2] !== undefined)
  .map(c => ({ ...c, rigidBodyIndex1: map[c.rigidBodyIndex1], rigidBodyIndex2: map[c.rigidBodyIndex2] }));

// ---------- 建场景 ----------
const scene = new THREE.Scene();
const bones = gbones.map(g => {
  const b = new THREE.Bone();
  b.name = g.name; b.position.fromArray(g.pos); b.quaternion.fromArray(g.rotq);
  return b;
});
gbones.forEach((g, i) => { if (g.parent !== -1 && bones[g.parent]) bones[g.parent].add(bones[i]); });
const geom = new THREE.BufferGeometry();
geom.setAttribute('position', new THREE.Float32BufferAttribute([0, 0, 0], 3));
const mesh = new THREE.SkinnedMesh(geom, new THREE.MeshBasicMaterial());
gbones.forEach((g, i) => { if (g.parent === -1) mesh.add(bones[i]); });
mesh.skeleton = new THREE.Skeleton(bones);
mesh.updateMatrixWorld(true);

const holder = new THREE.Group();
holder.add(mesh);

// ---------- normalizeModel 的等价实现 ----------
function measure(obj) {
  obj.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(obj);
  return box;
}
const raw = measure(mesh);          // 注意：setFromObject 对 SkinnedMesh 用未蒙皮顶点，这里仅量级参考
const h = raw.getSize(new THREE.Vector3()).y || 1;
const s = TARGET_HEIGHT / h;
console.log(`mode=${MODE} rawH=${h.toFixed(2)} scale=${s.toFixed(4)}`);

if (MODE === 'mesh') {
  mesh.scale.multiplyScalar(s);
  mesh.updateMatrixWorld(true);
  const b2 = measure(mesh);
  const c2 = b2.getCenter(new THREE.Vector3());
  mesh.position.x -= c2.x; mesh.position.z -= c2.z; mesh.position.y -= b2.min.y;
} else if (MODE === 'holder') {
  holder.scale.multiplyScalar(s);
} // none: 都不动

if(!NOROOT.includes('noscene')) scene.add(holder);
scene.updateMatrixWorld(true);

// ---------- createPhysicsUnscaled ----------
if(!NOROOT.includes('nopose')) mesh.pose && mesh.pose();
const saved = mesh.scale.clone();
mesh.scale.set(1, 1, 1);
mesh.updateMatrixWorld(true);
const phys = new MMDPhysics(mesh, useB, useC);
mesh.scale.copy(saved);
mesh.updateMatrixWorld(true);
phys.gravity.set(0, -98, 0);
phys.world.setGravity(new Ammo.btVector3(0, -98, 0));

// ---------- warmup(60)（App 里就在恢复缩放之后调） ----------
if(!NOROOT.includes('nowarm')) for (let i = 0; i < 60; i++) phys.update(1/60);

const dyn = [];
gbones.forEach((g, i) => { if (g.rbt === 1 || g.rbt === 2) dyn.push(bones[i]); });
const prev = dyn.map(b => b.quaternion.clone());

let peak = 0, pn = '', maxLocal = 0, ln = '', maxWorld = 0, wn = '';
const series = [];
for (let step = 0; step < STEPS; step++) {
  scene.updateMatrixWorld(true);
  phys.update(1 / 65);
  scene.updateMatrixWorld(true);
  let sum = 0;
  for (let i = 0; i < dyn.length; i++) {
    const q = dyn[i].quaternion, p = prev[i];
    const d = Math.abs(q.x - p.x) + Math.abs(q.y - p.y) + Math.abs(q.z - p.z) + Math.abs(q.w - p.w);
    sum += d;
    if (d > peak) { peak = d; pn = dyn[i].name; }
    p.copy(q);
    const lp = dyn[i].position.length();
    if (lp > maxLocal) { maxLocal = lp; ln = dyn[i].name; }
    const wp = dyn[i].getWorldPosition(new THREE.Vector3()).length();
    if (wp > maxWorld) { maxWorld = wp; wn = dyn[i].name; }
  }
  series.push(sum);
}
// ---- 接触统计（与 App 的 auditPhysicsContacts 同判据）----
try {
  const d = phys.world.getDispatcher();
  const n = d.getNumManifolds();
  const pts = phys.bodies.map(rb => { const o = rb.body.getCenterOfMassTransform().getOrigin();
    return {x:o.x(),y:o.y(),z:o.z(), nm:(rb.bone?rb.bone.name:'?')+'(t'+rb.params.type+')'}; });
  const nearest = (o) => { const x=o.x(),y=o.y(),z=o.z(); let bi=-1,bd=1e9;
    for(let k=0;k<pts.length;k++){const q=pts[k];const dd=(q.x-x)**2+(q.y-y)**2+(q.z-z)**2; if(dd<bd){bd=dd;bi=k;}}
    return {nm:bi>=0?pts[bi].nm:'?', d2:bd}; };
  let dd2=0, dk=0, kk=0; const det=[];
  for (let i = 0; i < n; i++) {
    const m = d.getManifoldByIndexInternal(i);
    const a = nearest(m.getBody0().getWorldTransform().getOrigin());
    const b = nearest(m.getBody1().getWorldTransform().getOrigin());
    const aK=/\(t0\)$/.test(a.nm), bK=/\(t0\)$/.test(b.nm);
    if (aK&&bK) kk++; else if (aK||bK) dk++; else dd2++;
    if (det.length<8) det.push(a.nm+' × '+b.nm);
  }
  console.log(`  接触: 流形=${n} 动-动=${dd2} 动-静=${dk} 静-静=${kk}`);
  console.log(`  ${det.join(' | ')}`);
} catch(e) { console.log('  接触统计失败', e.message); }

const tail = series.slice(Math.floor(series.length * 0.6));
const avg = tail.reduce((a, b) => a + b, 0) / Math.max(1, tail.length);
const curve = [];
for (let i = 0; i < series.length; i += Math.max(1, Math.floor(series.length / 8))) curve.push(series[i].toFixed(1));

console.log(`  末段平均Δq=${avg.toFixed(2)} 峰值=${peak.toFixed(2)}(${pn})`);
console.log(`  最大局部|pos|=${maxLocal.toFixed(1)}(${ln})  最大世界|pos|=${maxWorld.toFixed(2)}(${wn})`);
console.log(`  曲线: ${curve.join(' | ')}`);
