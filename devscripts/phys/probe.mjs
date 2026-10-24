// 长跑 + 速度/漂移探针
import fs from 'fs'; import path from 'path'; import { fileURLToPath } from 'url'; import { createRequire } from 'module';
const require = createRequire(import.meta.url); const __dirname = path.dirname(fileURLToPath(import.meta.url));
const AmmoFactory = require('./ammo.wasm.js');
const Ammo = await AmmoFactory({ wasmBinary: fs.readFileSync(path.join(__dirname,'ammo.wasm.wasm')) });
globalThis.Ammo = Ammo;
const THREE = await import('./three.mjs');
const { MMDParser } = await import('./mmdparser.mjs');
const { MMDPhysics } = await import('./MMDPhysics.node.mjs');
const _b = fs.readFileSync(new URL('../../in/kei.pmx', import.meta.url));
const data = new MMDParser.Parser().parsePmx(_b.buffer.slice(_b.byteOffset,_b.byteOffset+_b.byteLength), true);
const R = data.rigidBodies, C = data.constraints, B = data.bones;
for (const r of R) if (r.boneIndex!==-1){const b=B[r.boneIndex]; r.position=[r.position[0]-b.position[0],r.position[1]-b.position[1],r.position[2]-b.position[2]];}
for (let i=0;i<R.length;i++){const r=R[i]; if(!r.rotation.slice) r.rotation=r.rotation.slice(0);}
let tmap={}; for(let i=0;i<R.length;i++){const b=R[i].boneIndex; let v=tmap[b]; tmap[b]=v===undefined?R[i].type:Math.max(R[i].type,v);}
const gb=B.map((bd,i)=>{const o={parent:bd.parentIndex,name:bd.name,pos:bd.position.slice(0,3),rbt:tmap[i]??-1}; if(o.parent!==-1){o.pos[0]-=B[o.parent].position[0];o.pos[1]-=B[o.parent].position[1];o.pos[2]-=B[o.parent].position[2];} return o;});
const bodies=[]; const idxMap=[];
const RE=/hair|髪|胸|bust|skirt|スカート|しっぽ|尻尾|尾|ネクタイ|tie/i;
const mode=process.argv[2]||'filter';
const keep=new Set();
for(let i=0;i<R.length;i++){const nm=R[i].boneIndex>=0?B[R[i].boneIndex].name:''; if(RE.test(nm)) keep.add(i);}
if(mode==='closure'){
  let ch=true,r=0;
  while(ch&&r<50){ch=false;r++;for(const c of C){const a=c.rigidBodyIndex1,b=c.rigidBodyIndex2;
    if(keep.has(a)&&!keep.has(b)){keep.add(b);ch=true;} else if(keep.has(b)&&!keep.has(a)){keep.add(a);ch=true;}}}
  console.log('closure size',keep.size);
}
for(let i=0;i<R.length;i++){
  const nm=R[i].boneIndex>=0?B[R[i].boneIndex].name:'';
  const sel = mode==='all' ? true : (mode==='closure' ? keep.has(i) : RE.test(nm));
  if(sel){idxMap[i]=bodies.length;bodies.push({...R[i],position:R[i].position.slice(),rotation:R[i].rotation.slice()});}
}
const cons=C.filter(c=>idxMap[c.rigidBodyIndex1]!==undefined&&idxMap[c.rigidBodyIndex2]!==undefined)
  .map(c=>({...c,rigidBodyIndex1:idxMap[c.rigidBodyIndex1],rigidBodyIndex2:idxMap[c.rigidBodyIndex2],
    translationLimitation1:c.translationLimitation1.slice(),translationLimitation2:c.translationLimitation2.slice(),
    rotationLimitation1:c.rotationLimitation1.slice(),rotationLimitation2:c.rotationLimitation2.slice(),
    springPosition:c.springPosition.slice(),springRotation:c.springRotation.slice()}));
const bones=gb.map(g=>{const b=new THREE.Bone();b.name=g.name;b.position.fromArray(g.pos);return b;});
gb.forEach((g,i)=>{if(g.parent!==-1&&bones[g.parent])bones[g.parent].add(bones[i]);});
const geom=new THREE.BufferGeometry(); geom.setAttribute('position',new THREE.Float32BufferAttribute([0,0,0],3));
const mesh=new THREE.SkinnedMesh(geom,new THREE.MeshBasicMaterial());
gb.forEach((g,i)=>{if(g.parent===-1)mesh.add(bones[i]);});
mesh.skeleton=new THREE.Skeleton(bones); mesh.updateMatrixWorld(true);
const phys=new MMDPhysics(mesh,bodies,cons);
phys.gravity.set(0,-98,0); phys.world.setGravity(new Ammo.btVector3(0,-98,0));
const dyn=[]; for(let i=0;i<gb.length;i++) if(gb[i].rbt===1||gb[i].rbt===2) dyn.push(bones[i]);
const prev=dyn.map(b=>b.quaternion.clone());
const N=3000; let peak=0,pn='';
const marks=[];
for(let s=0;s<N;s++){
  mesh.updateMatrixWorld(true); phys.update(1/65); mesh.updateMatrixWorld(true);
  let sum=0;
  for(let i=0;i<dyn.length;i++){const q=dyn[i].quaternion,p=prev[i];
    const d=Math.abs(q.x-p.x)+Math.abs(q.y-p.y)+Math.abs(q.z-p.z)+Math.abs(q.w-p.w); sum+=d;
    if(d>peak){peak=d;pn=dyn[i].name;} p.copy(q);}
  if((s+1)%500===0) marks.push(s+1+':Δq='+sum.toFixed(1));
}
console.log(mode,'bodies',bodies.length,'cons',cons.length,'peak',peak.toFixed(2),pn);
console.log('  ',marks.join(' '));
// 速度探针
let vs=[];
for(const b of phys.bodies){const v=b.body.getLinearVelocity(); vs.push([Math.hypot(v.x(),v.y(),v.z()), b.bone?b.bone.name:'?']);}
vs.sort((a,b)=>b[0]-a[0]);
console.log('   最快刚体:', vs.slice(0,5).map(v=>v[1]+'='+v[0].toFixed(2)).join(' '));
let pos=[];
for(const b of phys.bodies){if(!b.bone)continue; const p=b.bone.position; pos.push([Math.hypot(p.x,p.y,p.z), b.bone.name]);}
pos.sort((a,b)=>b[0]-a[0]);
console.log('   最远骨骼(局部):', pos.slice(0,5).map(v=>v[1]+'='+v[0].toFixed(1)).join(' '));
