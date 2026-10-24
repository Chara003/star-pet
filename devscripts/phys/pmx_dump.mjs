// 离线解析 PMX，复现 MMDLoader + MMDPhysics 的初始化几何，检查刚体重叠
import fs from 'fs';
import { MMDParser } from './mmdparser.mjs';

const buf = fs.readFileSync(new URL('../../in/kei.pmx', import.meta.url));
const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
const parser = new MMDParser.Parser();
const data = parser.parsePmx(ab, true);

console.log('format', data.metadata.format, 'coord', data.metadata.coordinateSystem);
console.log('bones', data.metadata.boneCount, 'rigidBodies', data.metadata.rigidBodyCount,
            'constraints', data.metadata.constraintCount, 'morphs', data.metadata.morphCount);
console.log('boneIndexSize', data.metadata.boneIndexSize, 'rigidBodyIndexSize', data.metadata.rigidBodyIndexSize);

// ---- 骨骼世界位置（bind pose = 纯平移，PMX 骨骼无旋转） ----
const bones = data.bones;
/* ★ PMX 的 bone.position 就是【全局】位置（MMDLoader 建 gbone 时才减父骨骼转成相对）。
   所以骨骼世界位置 = data.bones[i].position 本身，不要再累加。 */
const world = new Array(bones.length);
for (let i = 0; i < bones.length; i++) {
  world[i] = [bones[i].position[0], bones[i].position[1], bones[i].position[2]];
}

// 高度范围
let minY = Infinity, maxY = -Infinity;
for (const w of world) { if (w[1] < minY) minY = w[1]; if (w[1] > maxY) maxY = w[1]; }
console.log('bone Y range', minY.toFixed(3), maxY.toFixed(3));

// ---- 复现 MMDLoader 的刚体位置换算 ----
const rbs = data.rigidBodies.map((rb, i) => {
  const p = rb.position.slice(0, 3);
  if (rb.boneIndex !== -1) {
    const bp = bones[rb.boneIndex].position;
    p[0] -= bp[0]; p[1] -= bp[1]; p[2] -= bp[2];
  }
  return {
    i, name: rb.name, boneIndex: rb.boneIndex,
    boneName: rb.boneIndex === -1 ? '(none)' : bones[rb.boneIndex].name,
    type: rb.type, groupIndex: rb.groupIndex, groupTarget: rb.groupTarget,
    shapeType: rb.shapeType, width: rb.width, height: rb.height, depth: rb.depth,
    pos: p, rot: rb.rotation.slice(0, 3), weight: rb.weight,
    friction: rb.friction, restitution: rb.restitution,
  };
});

// ---- 结论：body 世界位置 = boneWorld + rot(offset) ----
function eulerQuat(x, y, z) {
  // three 的 Euler XYZ 顺序
  const c1 = Math.cos(x / 2), c2 = Math.cos(y / 2), c3 = Math.cos(z / 2);
  const s1 = Math.sin(x / 2), s2 = Math.sin(y / 2), s3 = Math.sin(z / 2);
  return [
    s1 * c2 * c3 + c1 * s2 * s3,
    c1 * s2 * c3 - s1 * c2 * s3,
    c1 * c2 * s3 + s1 * s2 * c3,
    c1 * c2 * c3 - s1 * s2 * s3,
  ];
}
function rotVec(q, v) {
  const [x, y, z, w] = q;
  const ix = w * v[0] + y * v[2] - z * v[1];
  const iy = w * v[1] + z * v[0] - x * v[2];
  const iz = w * v[2] + x * v[1] - y * v[0];
  const iw = -x * v[0] - y * v[1] - z * v[2];
  return [
    ix * w + iw * -x + iy * -z - iz * -y,
    iy * w + iw * -y + iz * -x - ix * -z,
    iz * w + iw * -z + ix * -y - iy * -x,
  ];
}
for (const rb of rbs) {
  const bw = rb.boneIndex === -1 ? [0, 0, 0] : world[rb.boneIndex];
  const q = eulerQuat(rb.rot[0], rb.rot[1], rb.rot[2]);
  const r = rotVec(q, rb.pos);
  rb.world = [bw[0] + r[0], bw[1] + r[1], bw[2] + r[2]];
  rb.declGlobal = rb.boneIndex === -1 ? rb.pos : [
    rb.pos[0] + bones[rb.boneIndex].position[0],
    rb.pos[1] + bones[rb.boneIndex].position[1],
    rb.pos[2] + bones[rb.boneIndex].position[2],
  ];
}

// ---- 部位分类（与 index.html 的 PHYS_PART_RE 一致）----
const PART_RE = /hair|髪|胸|bust|skirt|スカート|しっぽ|尻尾|尾|ネクタイ|tie/i;
const kept = rbs.filter(r => PART_RE.test(r.boneName));
console.log('\n=== 部位匹配: kept', kept.length, '/', rbs.length, '===');
const byPrefix = {};
for (const r of kept) {
  const k = r.boneName.replace(/[0-9０-９\-_.]+$/, '');
  byPrefix[k] = (byPrefix[k] || 0) + 1;
}
console.log(byPrefix);

// 全部刚体类型分布
const typeCount = {};
for (const r of rbs) typeCount[r.type] = (typeCount[r.type] || 0) + 1;
console.log('type 分布 (0=跟随骨骼,1=物理+骨骼位置,2=物理):', typeCount);
const shapeCount = {};
for (const r of rbs) shapeCount[r.shapeType] = (shapeCount[r.shapeType] || 0) + 1;
console.log('shape 分布 (0=球,1=盒,2=胶囊):', shapeCount);

// ---- 重叠检测：按 AABB 粗判，且尊重碰撞组掩码 ----
function aabb(r) {
  const c = r.world;
  let hx, hy, hz;
  if (r.shapeType === 0) { hx = hy = hz = r.width; }
  else if (r.shapeType === 1) { hx = r.width; hy = r.height; hz = r.depth; }
  else { hx = hy = r.width; hz = r.width; hy = r.width + r.height / 2; }
  // 保守：忽略旋转，用最大半轴
  const m = Math.max(hx, hy, hz);
  return [c[0] - m, c[1] - m, c[2] - m, c[0] + m, c[1] + m, c[2] + m];
}
function overlapDepth(a, b) {
  const dx = Math.min(a[3], b[3]) - Math.max(a[0], b[0]);
  const dy = Math.min(a[4], b[4]) - Math.max(a[1], b[1]);
  const dz = Math.min(a[5], b[5]) - Math.max(a[2], b[2]);
  if (dx <= 0 || dy <= 0 || dz <= 0) return 0;
  return Math.min(dx, dy, dz);
}

function scan(label, list) {
  let pairs = 0, worst = 0, worstPair = null;
  for (let i = 0; i < list.length; i++) {
    for (let j = i + 1; j < list.length; j++) {
      const a = list[i], b = list[j];
      if (a.type === 0 || b.type === 0) continue;          // 运动学刚体不参与碰撞响应
      const collide = ((a.groupTarget >> b.groupIndex) & 1) && ((b.groupTarget >> a.groupIndex) & 1);
      if (!collide) continue;
      const d = overlapDepth(aabb(a), aabb(b));
      if (d > 0) { pairs++; if (d > worst) { worst = d; worstPair = [a, b]; } }
    }
  }
  console.log(`\n[${label}] 会真正碰撞的重叠对 = ${pairs}，最深 = ${worst.toFixed(4)}`);
  if (worstPair) console.log('   最深一对:', worstPair[0].boneName, '#' + worstPair[0].i,
    '<->', worstPair[1].boneName, '#' + worstPair[1].i,
    'pos', worstPair[0].world.map(n=>n.toFixed(2)).join(','), '|', worstPair[1].world.map(n=>n.toFixed(2)).join(','));
  return pairs;
}
scan('全部刚体（作者原样）', rbs);
scan('过滤后（cur 代码的实际集合）', kept);

// 过滤后约束还剩多少
const idxMap = new Map();
kept.forEach((r, k) => idxMap.set(r.i, k));
let consKept = 0;
for (const c of data.constraints) {
  if (idxMap.has(c.rigidBodyIndex1) && idxMap.has(c.rigidBodyIndex2)) consKept++;
}
console.log('\n约束: 原始', data.metadata.constraintCount, '→ 过滤后', consKept);

// ---- 输出前若干刚体细节 ----
console.log('\n=== 前 8 个刚体 ===');
for (const r of rbs.slice(0, 8)) {
  console.log(`#${r.i} type=${r.type} g=${r.groupIndex}/${r.groupTarget.toString(2)} shape=${r.shapeType} `
    + `w=${r.width.toFixed(3)} h=${r.height.toFixed(3)} d=${r.depth.toFixed(3)} bone=${r.boneName}`);
  console.log(`   declGlobal=${r.declGlobal.map(n=>n.toFixed(3)).join(',')} `
    + `world(pos)=${r.world.map(n=>n.toFixed(3)).join(',')} offset=${r.pos.map(n=>n.toFixed(3)).join(',')}`);
}
// 打印 kept 清单
console.log('\n=== 保留清单 ===');
for (const r of kept) {
  console.log(`#${r.i} ${r.boneName} type=${r.type} shape=${r.shapeType} w=${r.width.toFixed(3)} h=${r.height.toFixed(3)} d=${r.depth.toFixed(3)} world=${r.world.map(n=>n.toFixed(2)).join(',')}`);
}
