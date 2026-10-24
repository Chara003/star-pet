const fs=require('fs');
const P=process.argv[2]||'./model.glb';
const ANIM=process.argv[3]||'Cafe_Idle';
const b=fs.readFileSync(P);
const l0=b.readUInt32LE(12);
const g=JSON.parse(b.slice(20,20+l0).toString('utf8'));
let off=20+l0,bin=null;
while(off<b.length){const l=b.readUInt32LE(off),t=b.readUInt32LE(off+4);
 if(t===0x004E4942)bin=b.slice(off+8,off+8+l);off+=8+l;}
const CT={5120:Int8Array,5121:Uint8Array,5122:Int16Array,5123:Uint16Array,5125:Uint32Array,5126:Float32Array};
const NC={SCALAR:1,VEC2:2,VEC3:3,VEC4:4,MAT4:16};
function acc(i){const a=g.accessors[i],bv=g.bufferViews[a.bufferView],TA=CT[a.componentType],n=NC[a.type];
 const st=(bv.byteOffset||0)+(a.byteOffset||0);const out=new Float32Array(a.count*n);
 for(let k=0;k<a.count*n;k++) out[k]=new TA(bin.buffer,bin.byteOffset+st+k*TA.BYTES_PER_ELEMENT,1)[0];
 return out;}
const anim=g.animations.find(a=>a.name===ANIM);
if(!anim){ console.log('没有 '+ANIM); process.exit(1); }
const chans=anim.channels.filter(c=>c.target.path==='weights');
console.log('动作 '+ANIM+'：weights 通道 '+chans.length+' 条');
const rows=[];
chans.forEach(c=>{
  const nm=g.nodes[c.target.node].name;
  const smp=anim.samplers[c.sampler];
  rows.push({nm, times:Array.from(acc(smp.input)), vals:Array.from(acc(smp.output)),
             interp:smp.interpolation||'LINEAR'});
});
rows.sort((a,c)=>a.nm.localeCompare(c.nm));
console.log('\n=== 每个嘴的【关键帧】（keys=1 = 全程恒定）===');
rows.forEach(r=>{
  const konst = r.times.length===1;
  console.log('  '+r.nm.padEnd(12)+' keys='+String(r.times.length).padStart(2)
    +(konst?'  恒定='+r.vals[0].toFixed(2)+'  '+(r.vals[0]>=0.5?'（塌缩/看不见）':'（★可见）')
           :'  值='+r.vals.map(v=>v.toFixed(2)).join(',')));
});
/* 逐 0.25s 采样：有几个嘴是"接近 0"（= 可见） */
console.log('\n=== 逐 0.25s：哪些嘴可见（权重<0.5）===');
const dur = Math.max(...rows.flatMap(r=>r.times));
function sample(r,t){
  const T=r.times, V=r.vals;
  if(T.length===1) return V[0];
  if(t<=T[0]) return V[0];
  if(t>=T[T.length-1]) return V[V.length-1];
  let i=1; while(T[i]<t) i++;
  const p=(t-T[i-1])/(T[i]-T[i-1]);
  return V[i-1]+(V[i]-V[i-1])*p;
}
let badPhases=0, total=0;
for(let t=0;t<=dur;t+=0.25){
  total++;
  const vis=rows.filter(r=>sample(r,t)<0.5).map(r=>r.nm.replace('Mouth_',''));
  if(!vis.length) badPhases++;
  const bars = rows.map(r=>{const v=sample(r,t); return v<0.15?'O':(v<0.5?'o':'.');}).join('');
  console.log('  t='+t.toFixed(2).padStart(5)+'s  [' + bars + ']  可见: ' + (vis.join(',')||'（无！→ 嘴会消失）'));
}
console.log('\n（O=全可见 o=半塌缩 .=不可见；顺序=' + rows.map(r=>r.nm.replace('Mouth_','')).join(',') + '）');
console.log('★ 完全没有可见嘴的采样点: ' + badPhases + ' / ' + total);
