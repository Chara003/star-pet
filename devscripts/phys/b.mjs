import fs from 'fs';
import { MMDParser } from './mmdparser.mjs';
const buf = fs.readFileSync(new URL('../../in/kei.pmx', import.meta.url));
const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength);
const data = new MMDParser.Parser().parsePmx(ab, true);
const names = ['センター','上半身','上半身2','頭','右胸','左胸','後ろ髪1-3','スカート1.P','上着_髪壁'];
console.log('idx  name            position(parentIndex)');
for (let i=0;i<data.bones.length;i++){
  if (names.includes(data.bones[i].name)) console.log(i, data.bones[i].name, JSON.stringify(data.bones[i].position.map(n=>+n.toFixed(3))), 'parent', data.bones[i].parentIndex);
}
console.log('\n--- 刚体 0..7 原始 ---');
for (let i=0;i<8;i++){
  const r=data.rigidBodies[i];
  console.log(`#${i} bone=${r.boneIndex}(${data.bones[r.boneIndex]?.name}) pos=${r.position.map(n=>+n.toFixed(3))} rot=${r.rotation.map(n=>+n.toFixed(3))} type=${r.type}`);
}
console.log('\n--- 刚体 37..43 (胸) ---');
for (let i=37;i<=44;i++){
  const r=data.rigidBodies[i];
  console.log(`#${i} bone=${r.boneIndex}(${data.bones[r.boneIndex]?.name}) pos=${r.position.map(n=>+n.toFixed(3))} rot=${r.rotation.map(n=>+n.toFixed(3))} type=${r.type} shape=${r.shapeType} w=${r.width.toFixed(3)} gy=${r.height.toFixed(3)} gz=${r.depth}`);
}
console.log('\n--- 约束 0..5 ---');
for (let i=0;i<6;i++){
  const c=data.constraints[i];
  console.log(`c#${i} ${c.rigidBodyIndex1}-${c.rigidBodyIndex2} pos=${c.position.map(n=>+n.toFixed(3))} rot=${c.rotation.map(n=>+n.toFixed(3))} trans=[${c.translationLimitation1.map(n=>+n.toFixed(2))}]..[${c.translationLimitation2.map(n=>+n.toFixed(2))}] springP=${c.springPosition} springR=${c.springRotation}`);
}
