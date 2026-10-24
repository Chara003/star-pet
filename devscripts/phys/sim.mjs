// 离线复现 MMDPhysics 的震荡：解析 PMX → 建骨骼 → 建物理 → 逐步积分
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { createRequire } from 'module';

const require = createRequire(import.meta.url);
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// (do not define self: emscripten would take the browser path)

const AmmoFactory = require('./ammo.wasm.js');
const wasmBinary = fs.readFileSync(path.join(__dirname, 'ammo.wasm.wasm'));
const Ammo = await AmmoFactory({ wasmBinary });
globalThis.Ammo = Ammo;
console.log('ammo ok', typeof Ammo.btDiscreteDynamicsWorld);

const THREE = await import('./three.mjs');
const { MMDParser } = await import('./mmdparser.mjs');
const { MMDPhysics } = await import('./MMDPhysics.node.mjs');

// ---------- 1. 解析 ----------
const args = process.argv.slice(2);
const file = args[0] || new URL('../../in/kei.pmx', import.meta.url).pathname;
const buf = fs.readFileSync(file);
const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
const data = new MMDParser.Parser().parsePmx(ab, true);

// ---------- 2. 复现 MMDLoader.GeometryBuilder ----------
const boneTypeTable = {};
for (let i = 0; i < data.metadata.rigidBodyCount; i++) {
  const body = data.rigidBodies[i];
  let value = boneTypeTable[body.boneIndex];
  value = value === undefined ? body.type : Math.max(body.type, value);
  boneTypeTable[body.boneIndex] = value;
}
const gbones = [];
for (let i = 0; i < data.metadata.boneCount; i++) {
  const boneData = data.bones[i];
  const b = {
    index: i, parent: boneData.parentIndex, name: boneData.name,
    pos: boneData.position.slice(0, 3), rotq: [0, 0, 0, 1],
    rigidBodyType: boneTypeTable[i] !== undefined ? boneTypeTable[i] : -1,
  };
  if (b.parent !== -1) {
    b.pos[0] -= data.bones[b.parent].position[0];
    b.pos[1] -= data.bones[b.parent].position[1];
    b.pos[2] -= data.bones[b.parent].position[2];
  }
  gbones.push(b);
}

const rigidBodies = [];
for (let i = 0; i < data.metadata.rigidBodyCount; i++) {
  const rb = data.rigidBodies[i];
  const p = {};
  for (const k in rb) p[k] = rb[k];
  if (data.metadata.format === 'pmx' && p.boneIndex !== -1) {
    const bone = data.bones[p.boneIndex];
    p.position[0] -= bone.position[0];
    p.position[1] -= bone.position[1];
    p.position[2] -= bone.position[2];
  }
  rigidBodies.push(p);
}
const constraints = [];
for (let i = 0; i < data.metadata.constraintCount; i++) {
  const c = data.constraints[i];
  const p = {};
  for (const k in c) p[k] = c[k];
  const bodyA = rigidBodies[p.rigidBodyIndex1];
  const bodyB = rigidBodies[p.rigidBodyIndex2];
  if (bodyA.type !== 0 && bodyB.type === 2) {
    if (bodyA.boneIndex !== -1 && bodyB.boneIndex !== -1 &&
        data.bones[bodyB.boneIndex].parentIndex === bodyA.boneIndex) {
      bodyB.type = 1;
    }
  }
  constraints.push(p);
}

// ---------- 3. 建 three 骨骼树 ----------
function buildMesh() {
  const bones = gbones.map(g => {
    const b = new THREE.Bone();
    b.name = g.name;
    b.position.fromArray(g.pos);
    b.quaternion.fromArray(g.rotq);
    return b;
  });
  gbones.forEach((g, i) => {
    if (g.parent !== -1 && bones[g.parent]) bones[g.parent].add(bones[i]);
  });
  const geom = new THREE.BufferGeometry();
  geom.setAttribute('position', new THREE.Float32BufferAttribute([0, 0, 0], 3));
  const mesh = new THREE.SkinnedMesh(geom, new THREE.MeshBasicMaterial());
  const roots = gbones.map((g, i) => (g.parent === -1 ? bones[i] : null)).filter(Boolean);
  roots.forEach(r => mesh.add(r));
  mesh.skeleton = new THREE.Skeleton(bones);
  mesh.updateMatrixWorld(true);
  return { mesh, bones };
}

// ---------- 4. 部位筛选（与 index.html 一致） ----------
const PART_RE = /hair|髪|胸|bust|skirt|スカート|しっぽ|尻尾|尾|ネクタイ|tie/i;
function filterByPart(bodies, cons) {
  const map = {}; const out = [];
  for (let i = 0; i < bodies.length; i++) {
    const rb = bodies[i]; const bi = rb.boneIndex;
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

// ---------- 5. 跑 ----------
function cloneBodies(src) { return src.map(r => ({ ...r, position: r.position.slice(), rotation: r.rotation.slice() })); }

async function run(label, opts) {
  const { useFilter, noCollide, gravity, steps } = opts;
  const rb = cloneBodies(rigidBodies);
  let cs = constraints.map(c => ({ ...c, translationLimitation1: c.translationLimitation1.slice(), translationLimitation2: c.translationLimitation2.slice(), rotationLimitation1: c.rotationLimitation1.slice(), rotationLimitation2: c.rotationLimitation2.slice(), springPosition: c.springPosition.slice(), springRotation: c.springRotation.slice() }));
  let useB = rb, useC = cs;
  if (useFilter) { const f = filterByPart(rb, cs); useB = f.bodies; useC = f.cons; }
  if (noCollide) useB.forEach(b => { b.groupTarget = 0; });

  const { mesh, bones } = buildMesh();
  const phys = new MMDPhysics(mesh, useB, useC);
  phys.gravity.set(0, gravity, 0);
  phys.world.setGravity(new Ammo.btVector3(0, gravity, 0));

  // 动态骨骼
  const dyn = [];
  for (let i = 0; i < gbones.length; i++) {
    if (gbones[i].rigidBodyType === 1 || gbones[i].rigidBodyType === 2) dyn.push(bones[i]);
  }
  const prev = dyn.map(b => b.quaternion.clone());

  const dq = [];
  let peak = 0, peakName = '';
  let maxDrift = 0;
  const restPos = dyn.map(b => b.position.clone());
  for (let s = 0; s < steps; s++) {
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
      const dp = dyn[i].position.distanceTo(restPos[i]);
      if (dp > maxDrift && s > 10) maxDrift = dp;
    }
    dq.push(sum);
  }
  const tail = dq.slice(Math.floor(dq.length * 0.6));
  const avg = tail.reduce((a, b) => a + b, 0) / Math.max(1, tail.length);
  console.log(`${label}: bodies=${useB.length} cons=${useC.length} dyn=${dyn.length} `
    + `| 末段平均Δq=${avg.toFixed(3)} 峰值=${peak.toFixed(3)}(${peakName}) `
    + `| 最大位移=${maxDrift.toFixed(3)} | 首10步=${dq.slice(0, 10).map(n => n.toFixed(1)).join(',')}`);
  return { avg, peak, maxDrift, dq, bones, mesh, phys, dyn };
}

const steps = Number(args[1] || 400);
console.log('model native h ~', (Math.max(...data.bones.map(b => b.position[1]))).toFixed(2));
await run('[全部刚体 碰撞开]', { useFilter: false, noCollide: false, gravity: -98, steps });
await run('[全部刚体 碰撞关]', { useFilter: false, noCollide: true, gravity: -98, steps });
await run('[筛选后 碰撞开]', { useFilter: true, noCollide: false, gravity: -98, steps });
await run('[筛选后 碰撞关]', { useFilter: true, noCollide: true, gravity: -98, steps });
