import fs from 'fs'; import { MMDParser } from './mmdparser.mjs';
const b=fs.readFileSync(new URL('../../in/kei.pmx',import.meta.url));
const d=new MMDParser.Parser().parsePmx(b.buffer.slice(b.byteOffset,b.byteOffset+b.byteLength),true);
const R=d.rigidBodies,C=d.constraints,B=d.bones;
const RE=/hair|髪|胸|bust|skirt|スカート|しっぽ|尻尾|尾|ネクタイ|tie/i;
const seed=new Set();
for(let i=0;i<R.length;i++){const n=R[i].boneIndex>=0?B[R[i].boneIndex].name:''; if(RE.test(n)) seed.add(i);}
console.log('seed',seed.size);
// 闭包：把约束另一端的刚体也拉进来（无向）
let changed=true; const keep=new Set(seed);
let rounds=0;
while(changed&&rounds<50){changed=false;rounds++;
  for(const c of C){const a=c.rigidBodyIndex1,b2=c.rigidBodyIndex2;
    if(keep.has(a)&&!keep.has(b2)){keep.add(b2);changed=true;}
    if(keep.has(b2)&&!keep.has(a)){keep.add(a);changed=true;}}}
console.log('closure',keep.size,'rounds',rounds);
// 每轮统计
const cnt={}; for(const i of keep){const n=B[R[i].boneIndex]?.name||'?'; const k=n.replace(/[0-9\-_.]+$/,''); cnt[k]=(cnt[k]||0)+1;}
console.log(Object.entries(cnt).sort((a,b)=>b[1]-a[1]).slice(0,25));
// 只保留"链式闭包"：仅当另一端 type==0（运动学锚点）时才拉进来，避免把整个身体拉进来
const keep2=new Set(seed);
// 迭代：如果一端在 keep2 且另一端 type==0 → 加入（锚点必须保留）；再迭代一次
for(let r=0;r<5;r++){ for(const c of C){const a=c.rigidBodyIndex1,b2=c.rigidBodyIndex2;
  if(keep2.has(a)&&R[b2].type===0) keep2.add(b2);
  if(keep2.has(b2)&&R[a].type===0) keep2.add(a);} }
console.log('只补运动学锚点后',keep2.size);
