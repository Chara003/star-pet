const fs=require('fs');
const buf=fs.readFileSync(process.argv[2] || './model.glb');
const l0=buf.readUInt32LE(12);
const g=JSON.parse(buf.slice(20,20+l0).toString('utf8'));
let off=20+l0,bin=null;
while(off<buf.length){const l=buf.readUInt32LE(off),t=buf.readUInt32LE(off+4);
 if(t===0x004E4942)bin=buf.slice(off+8,off+8+l);off+=8+l;}
const CT={5120:Int8Array,5121:Uint8Array,5122:Int16Array,5123:Uint16Array,5125:Uint32Array,5126:Float32Array};
const NC={SCALAR:1,VEC2:2,VEC3:3,VEC4:4,MAT4:16};
function rawAcc(i){const a=g.accessors[i],bv=g.bufferViews[a.bufferView],TA=CT[a.componentType],n=NC[a.type];
 const st=(bv.byteOffset||0)+(a.byteOffset||0);return new TA(bin.buffer,bin.byteOffset+st,a.count*n);}
const anim=g.animations.find(a=>a.name==='Formation_Pickup');
function curve(nodeName,path){
  const ni=g.nodes.findIndex(n=>n.name===nodeName);
  const ch=anim.channels.find(c=>c.target.node===ni && c.target.path===path);
  if(!ch) return null;
  const smp=anim.samplers[ch.sampler];
  return {times:Array.from(rawAcc(smp.input)), vals:Array.from(rawAcc(smp.output)),
          comp:NC[g.accessors[smp.output].type], cubic:smp.interpolation==='CUBICSPLINE'};
}
function series(c, ci){
  const {times,vals,comp,cubic}=c, stride=cubic?comp*3:comp;
  return times.map((t,k)=>{const b=k*stride+(cubic?comp:0);return vals[b+ci];});
}
/* 用 Bip001（整个骨架的父）的 translation 看身体高度 */
for (const nm of ['Bip001','bone_root']){
  const c=curve(nm,'translation');
  if(!c){ console.log(nm+': 无 translation 轨道'); continue; }
  console.log('=== '+nm+' translation ===');
  console.log('  t: '+c.times.map(t=>t.toFixed(2)).join(', '));
  for(let ci=0;ci<Math.min(3,c.comp);ci++)
    console.log('  ['+['x','y','z'][ci]+']: '+series(c,ci).map(v=>v.toFixed(3)).join(', '));
}
/* 也看 Pelvis 的 rotation（判断"被拎起来"时身体是否还在扭动） */
const hips=curve('Bip001 Pelvis','rotation');
if(hips){
  console.log('\n=== Bip001 Pelvis rotation（四元数 w 分量，|w|≈1 表示几乎没转）===');
  const stride=hips.cubic?4*3:4;
  const idx=3;
  
  function strd(k){return k*stride+(hips.cubic?4:0);}
  const w=hips.times.map((t,k)=>hips.vals[strd(k)+3]);
  console.log('  t: '+hips.times.map(t=>t.toFixed(2)).join(', '));
  console.log('  w: '+w.map(v=>Math.abs(v).toFixed(3)).join(', '));
}
/* 逐 1 秒采样：列出每个时刻"位移总量"，找动作稳定段 */
console.log('\n=== 逐秒采样 Bip001 位移（看动作节奏）===');
const ct=curve('Bip001','translation');
if(ct){
  const stride=ct.cubic?3*3:3;
  const get=k=>{const b=k*stride+(ct.cubic?3:0);return [ct.vals[b],ct.vals[b+1],ct.vals[b+2]];};
  const p0=get(0);
  for(let t=0;t<=14;t+=1){
    let i=0; while(i<ct.times.length-1 && ct.times[i+1]<t) i++;
    const p=get(i);
    const d=Math.hypot(p[0]-p0[0],p[1]-p0[1],p[2]-p0[2]);
    const bar='#'.repeat(Math.min(50,Math.round(d*100)));
    console.log('  t='+String(t).padStart(2)+'s  y='+p[1].toFixed(3).padStart(7)+'  |位移|='+d.toFixed(3)+'  '+bar);
  }
}
