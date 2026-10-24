import fs from 'fs'; import { MMDParser } from './mmdparser.mjs';
const b=fs.readFileSync(new URL('../../in/kei.pmx',import.meta.url));
const d=new MMDParser.Parser().parsePmx(b.buffer.slice(b.byteOffset,b.byteOffset+b.byteLength),true);
const R=d.rigidBodies,B=d.bones;
const m={};
for(let i=0;i<R.length;i++){const k=R[i].boneIndex; (m[k]=m[k]||[]).push(i);}
let dups=0;
for(const k in m) if(m[k].length>1){dups++; if(dups<=10) console.log('bone',k,B[k]?.name,'被',m[k].length,'个刚体绑定:',m[k].map(i=>'#'+i+'t'+R[i].type).join(' '));}
console.log('同一骨骼被多个刚体绑定的骨骼数=',dups);
const nm={};
for(let i=0;i<B.length;i++){ (nm[B[i].name]=nm[B[i].name]||[]).push(i); }
let nd=0; for(const k in nm) if(nm[k].length>1){nd++; if(nd<=10) console.log('重名骨骼',k,nm[k]);}
console.log('重名骨骼名数=',nd, '骨骼总数',B.length);
