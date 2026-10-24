/* 分析 Formation_Pickup 的时间结构：身体高度随时间变化（找"被拎起来"的时刻）*/
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
function samplerAt(anim, nodeName, path, t){
  const ni=g.nodes.findIndex(n=>n.name===nodeName);
  const ch=anim.channels.find(c=>c.target.node===ni && c.target.path===path);
  if(!ch) return null;
  const smp=anim.samplers[ch.sampler];
  const times=Array.from(rawAcc(smp.input)), vals=Array.from(rawAcc(smp.output));
  const comp=NC[g.accessors[smp.output].type];
  const cubic = smp.interpolation==='CUBICSPLINE';
  const stride = cubic ? comp*3 : comp;
  const nk = times.length;
  const get=k=>{ const base=k*stride + (cubic?comp:0); return vals.slice(base, base+comp); };
  if (t<=times[0]) return get(0);
  if (t>=times[nk-1]) return get(nk-1);
  let i=1; while(times[i]<t) i++;
  const t0=times[i-1], t1=times[i];
  if (cubic) {
    const p=(t-t0)/(t1-t0), pp=p*p, ppp=pp*p;
    const s2=-2*ppp+3*pp, s3=ppp-pp, s0=1-s2, s1=s3-pp+p;
    const o0=(i-1)*stride, o1=i*stride, td=t1-t0, out=[];
    for(let c=0;c<comp;c++){
      const P0=vals[o0+c+comp], M0=vals[o0+c+comp*2]*td;
      const P1=vals[o1+c+comp], M1=vals[o1+c]*td;
      out.push(s0*P0+s1*M0+s2*P1+s3*M1);
    }
    return out;
  }
  const a=get(i-1), b=get(i), p=(t-t0)/(t1-t0);
  return a.map((v,c)=>v+(b[c]-v)*p);
}
const anim=g.animations.find(a=>a.name==='Formation_Pickup');
console.log('=== Formation_Pickup 结构 ===');
console.log('  时长 = ' + Math.max(...anim.samplers.map(s=>{const a=rawAcc(s.input);return a[a.length-1];})).toFixed(2) + 's');
console.log('  通道数 = ' + anim.channels.length);
/* 用 Pelvis / センター(根) 的高度看"被拎起来" */
const rootCandidates=['bone_root','Bip001','Bip001 Pelvis'];
for (const rn of rootCandidates){
  const ni=g.nodes.findIndex(n=>n.name===rn);
  if(ni<0) continue;
  const hasT = anim.channels.some(c=>c.target.node===ni && c.target.path==='translation');
  console.log('  ' + rn + ' 有 translation 轨道? ' + hasT);
}
const ri=g.nodes.findIndex(n=>n.name==='bone_root');
const tl=anim.channels.find(c=>c.target.node===ri && c.target.path==='translation');
console.log('\n=== bone_root 的 Y 随时间（看哪一段是"悬空"）===');
const times=Array.from(rawAcc(anim.samplers[tl.sampler].input));
const vals=Array.from(rawAcc(anim.samplers[tl.sampler].output));
const comp=NC[g.accessors[anim.samplers[tl.sampler].output].type];
const cubic=anim.samplers[tl.sampler].interpolation==='CUBICSPLINE';
console.log('  关键帧时间: '+times.map(t=>t.toFixed(2)).join(', '));
console.log('  插值: '+(anim.samplers[tl.sampler].interpolation||'LINEAR')+'  comp='+comp+' 数据点数='+vals.length);
const stride=cubic?comp*3:comp;
const ys=times.map((_,k)=>{const b=k*stride+(cubic?comp:0);return vals[b+1];});
console.log('  Y 值: ' + ys.map(v=>v.toFixed(3)).join(', '));
const durs=times.map((t,i)=>i? t-times[i-1]:0);
console.log('  每段时长: ' + durs.map(d=>d.toFixed(2)).join(', '));
if (times.length>1){
  let best=0; for(let i=1;i<times.length;i++) if(ys[i]>ys[best]) best=i;
  console.log('  ★ Y 最大在 t=' + times[best].toFixed(2) + 's（y=' + ys[best].toFixed(3) + '）→ 这是"被拎到最高"的时刻');
}
/* 也看看总位移范围（判断动作是否"前移动"） */
console.log('\n=== Formation_Pickup 的 translation 通道（哪些骨骼在移动）===');
anim.channels.filter(c=>c.target.path==='translation').forEach(c=>{
  const out=rawAcc(anim.samplers[c.sampler].output);
  let mx=0; for(const v of out) mx=Math.max(mx,Math.abs(v));
  if(mx>0.02) console.log('  ' + (g.nodes[c.target.node].name||'?').padEnd(24) + ' 最大位移=' + mx.toFixed(3));
});
