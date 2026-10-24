import fs from 'fs'; import { MMDParser } from './mmdparser.mjs';
const b=fs.readFileSync(new URL('../../in/kei.pmx',import.meta.url));
const d=new MMDParser.Parser().parsePmx(b.buffer.slice(b.byteOffset,b.byteOffset+b.byteLength),true);
const R=d.rigidBodies,C=d.constraints,B=d.bones;
const nm=i=>`#${i} ${R[i].boneIndex>=0?B[R[i].boneIndex].name:'?'} t${R[i].type}`;
const targets=[174,175,176,177,178,179,180,269];
for(const t of targets){
  const list=C.map((c,i)=>[c,i]).filter(([c])=>c.rigidBodyIndex1===t||c.rigidBodyIndex2===t);
  console.log(nm(t), '← 约束数', list.length);
  for(const [c,i] of list.slice(0,6)){
    const o=c.rigidBodyIndex1===t?c.rigidBodyIndex2:c.rigidBodyIndex1;
    console.log('    c#'+i, '↔', nm(o), 'springP', c.springPosition.join(','), 'tr', c.translationLimitation1.map(n=>+n.toFixed(2)).join(','), '..', c.translationLimitation2.map(n=>+n.toFixed(2)).join(','));
  }
}
