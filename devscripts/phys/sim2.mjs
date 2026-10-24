// 离线复现/对照实验台：
//   node sim2.mjs [steps] [filter] [collide] [scale] [posY] [animate] [gravity]
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { createRequire } from 'module';

const require = createRequire(import.meta.url);
const __dirname = path.dirname(fileURLToPath(import.meta.url));

const AmmoFactory = require('./ammo.wasm.js');
const wasmBinary = fs.readFileSync(path.join(__dirname, 'ammo.wasm.wasm'));
const Ammo = await AmmoFactory({ wasmBinary });
globalThis.Ammo = Ammo;

const THREE = await import('./three.mjs');
const { MMDParser } = await import('./mmdparser.mjs');
const { MMDPhysics } = await import('./MMDPhysics.node.mjs');

const P = {
  steps: Number(process.argv[2] ?? 400),
  filter: Number(process.argv[3] ?? 1),
  collide: Number(process.argv[4] ?? 1),
  scale: Number(process.argv[5] ?? 1),
  posY: Number(process.argv[6] ?? 0),
  animate: Number(process.argv[7] ?? 0),
  gravity: Number(process.argv[8] ?? -98),
  postScale: Number(process.argv[9] ?? 1),
};

// ---------- 解析 + 复现 MMDLoader ----------
const buf = fs.readFileSync(new URL('../../in/kei.pmx', import.meta.url));
const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
const data = new MMDParser.Parser().parsePmx(ab, true);

const boneTypeTable = {};
for (let i = 0; i < data.metadata.rigidBodyCount; i++) {
  const body = data.rigidBodies[i];
  let v = boneTypeTable[body.boneIndex];
  v = v === undefined ? body.type : Math.max(body.type, v);
  boneTypeTable[body.boneIndex] = v;
}
const gbones = [];
for (let i = 0; i < data.metadata.boneCount; i++) {
  const bd = data.bones[i];
  const b = { index: i, parent: bd.parentIndex, name: bd.name,
              pos: bd.position.slice(0, 3), rigidBodyType: boneTypeTable[i] ?? -1 };
  if (b.parent !== -1) {
    b.pos[0] -= data.bones[b.parent].position[0];
    b.pos[1] -= data.bones[b.parent].position[1];
    b.pos[2] -= data.bones[b.parent].position[2];
  }
  gbones.push(b);
}
const rigidBodies = data.rigidBodies.map(rb => {
  const p = { ...rb, position: rb.position.slice(), rotation: rb.rotation.slice() };
  if (p.boneIndex !== -1) {
    const bone = data.bones[p.boneIndex];
    p.position[0] -= bone.position[0];
    p.position[1] -= bone.position[1];
    p.position[2] -= bone.position[2];
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
      && data.bones[b.boneIndex].parentIndex === a.boneIndex) b.type = 1;
}

// ---------- 部位筛选 ----------
const PART_RE = /hair|髪|胸|bust|skirt|スカート|しっぽ|尻尾|尾|ネクタイ|tie/i;
function filterByPart(bodies, cons) {
  const map = {}; const out = [];
  for (let i = 0; i < bodies.length; i++) {
    const rb = bodies[i], bi = rb.boneIndex;
    const nm = (bi >= 0 && bi < data.bones.length) ? String(data.bones[bi].name || '') : '';
    if (PART_RE.test(nm)) { map[i] = out.length; out.push(rb); }
  }
  if (!out.length) return { bodies, cons };
  const oc = [];
  for (const c of cons) {
    const a = map[c.rigidBodyIndex1], b = map[c.rigidBodyIndex2];
    if (a === undefined || b === undefined) continue;
    oc.push({ ...c, rigidBodyIndex1: a, rigidBodyIndex2: b });
  }
  return { bodies: out, cons: oc };
}

// ---------- 建骨骼 ----------
function buildMesh() {
  const bones = gbones.map(g => {
    const b = new THREE.Bone();
    b.name = g.name; b.position.fromArray(g.pos); b.quaternion.fromArray([0, 0, 0, 1]);
    return b;
  });
  gbones.forEach((g, i) => { if (g.parent !== -1 && bones[g.parent]) bones[g.parent].add(bones[i]); });
  const geom = new THREE.BufferGeometry();
  geom.setAttribute('position', new THREE.Float32BufferAttribute([0, 0, 0], 3));
  const mesh = new THREE.SkinnedMesh(geom, new THREE.MeshBasicMaterial());
  gbones.forEach((g, i) => { if (g.parent === -1) mesh.add(bones[i]); });
  mesh.skeleton = new THREE.Skeleton(bones);
  mesh.updateMatrixWorld(true);
  return { mesh, bones };
}

// ---------- 跑 ----------
const useB = P.filter ? filterByPart(rigidBodies, constraints).bodies : rigidBodies;
const useC = P.filter ? filterByPart(rigidBodies, constraints).cons : constraints;
if (!P.collide) useB.forEach(b => { b.groupTarget = 0; });

const { mesh, bones } = buildMesh();
/* 复现 index.html 的 createPhysicsUnscaled：先在 scale=1 建物理，建完再恢复缩放 */
if (!P.postScale) { mesh.scale.setScalar(P.scale); mesh.position.y = P.posY; mesh.updateMatrixWorld(true); }
const phys = new MMDPhysics(mesh, useB, useC);
if (P.postScale) { mesh.scale.setScalar(P.scale); mesh.position.y = P.posY; mesh.updateMatrixWorld(true); }
phys.gravity.set(0, P.gravity, 0);
phys.world.setGravity(new Ammo.btVector3(0, P.gravity, 0));

const dyn = [];
for (let i = 0; i < gbones.length; i++) {
  if (gbones[i].rigidBodyType === 1 || gbones[i].rigidBodyType === 2) dyn.push(bones[i]);
}
const prev = dyn.map(b => b.quaternion.clone());
const rest = dyn.map(b => b.position.clone());
const restW = dyn.map(b => b.getWorldPosition(new THREE.Vector3()));

const animBones = ['センター', '上半身', '上半身2', '頭', '下半身']
  .map(n => bones.find(b => b.name === n)).filter(Boolean);

const series = [];
let peak = 0, peakName = '', maxDrift = 0, driftName = '', maxWDrift = 0, wDriftName = '';
for (let s = 0; s < P.steps; s++) {
  if (P.animate && animBones.length) {
    const t = s / 65;
    animBones[1].rotation.z = Math.sin(t * 1.7) * 0.06;
    animBones[0].position.x = Math.sin(t * 1.1) * 0.08;
    animBones[3].rotation.y = Math.sin(t * 2.3) * 0.10;
  }
  mesh.updateMatrixWorld(true);
  phys.update(1 / 65);
  mesh.updateMatrixWorld(true);
  let sum = 0;
  for (let i = 0; i < dyn.length; i++) {
    const q = dyn[i].quaternion, p = prev[i];
    const d = Math.abs(q.x - p.x) + Math.abs(q.y - p.y) + Math.abs(q.z - p.z) + Math.abs(q.w - p.w);
    sum += d;
    if (d > peak) { peak = d; peakName = dyn[i].name; }
    p.copy(q);
    const dp = dyn[i].position.distanceTo(rest[i]);
    if (s > 5 && dp > maxDrift) { maxDrift = dp; driftName = dyn[i].name; }
    const wp = dyn[i].getWorldPosition(new THREE.Vector3());
    const dw = wp.distanceTo(restW[i]);
    if (s > 5 && dw > maxWDrift) { maxWDrift = dw; wDriftName = dyn[i].name; }
  }
  series.push(sum);
}
const tail = series.slice(Math.floor(series.length * 0.6));
const avg = tail.reduce((a, b) => a + b, 0) / Math.max(1, tail.length);
const head = series.slice(0, 8).map(n => n.toFixed(1)).join(',');
const t2 = [];
for (let i = 0; i < series.length; i += Math.max(1, Math.floor(series.length / 6))) t2.push(series[i].toFixed(1));
console.log(`P=${JSON.stringify(P)}`);
console.log(`  bodies=${useB.length} cons=${useC.length} dyn=${dyn.length}`);
console.log(`  末段平均Δq=${avg.toFixed(2)} 峰值=${peak.toFixed(2)}(${peakName})`);
console.log(`  最大局部位移=${maxDrift.toFixed(2)}(${driftName}) 最大世界位移=${maxWDrift.toFixed(2)}(${wDriftName})`);
console.log(`  曲线=${t2.join(' | ')}`);
