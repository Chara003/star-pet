import fs from 'fs'; import { MMDParser } from './mmdparser.mjs';
const b=fs.readFileSync(new URL('../../in/kei.pmx',import.meta.url));
const d=new MMDParser.Parser().parsePmx(b.buffer.slice(b.byteOffset,b.byteOffset+b.byteLength),true);
const R=d.rigidBodies,B=d.bones;
const tp={}; for(const r of R){const x=tp[r.boneIndex]; tp[r.boneIndex]=x===undefined?r.type:Math.max(r.type,x);}
const bodyName=i=>B[i]?.name||'?';
const phys=i=>{const t=tp[i]; return t===1||t===2;};
let g=0,gp=0, gn=0;
for(let i=0;i<B.length;i++){
  const bo=B[i];
  if(bo.grant){ g++; if(phys(i)){gp++; if(gn<15){console.log('付与目标=物理骨骼:',bo.name,'type',tp[i],'ratio',bo.grant.ratio,'affectRot',bo.grant.affectRotation,'affectPos',bo.grant.affectPosition,'← 源',bodyName(bo.grant.parentIndex)); gn++;}}}
}
console.log('授予(付与)骨骼数=',g,'其中是物理骨骼的=',gp);
// IK
let ik=0, ikPhys=0;
for(let i=0;i<B.length;i++){
  const bo=B[i]; if(!bo.ik) continue; ik++;
  const links=bo.ik.links||[];
  for(const l of links) if(phys(l.index)) { ikPhys++; console.log('IK链含物理骨骼: target',bo.name,'link',bodyName(l.index),'type',tp[l.index]); }
}
console.log('IK骨骼数=',ik,'IK链上的物理骨骼=',ikPhys);
