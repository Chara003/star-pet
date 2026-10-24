/* GLB CPU 蒙皮复算：比较 静止姿态 vs 动画姿态 的顶点位置，找出“炸开”的部件 */
const fs = require('fs');
const P = process.argv[2] || './model.glb';
const MODE = process.argv[3] || 'cubic';   // cubic | linear(错误解读) | rest
const T = parseFloat(process.argv[4] || '0');
const buf = fs.readFileSync(P);
const l0 = buf.readUInt32LE(12);
const g = JSON.parse(buf.slice(20, 20 + l0).toString('utf8'));
let off = 20 + l0, bin = null;
while (off < buf.length) { const l = buf.readUInt32LE(off), t = buf.readUInt32LE(off + 4);
  if (t === 0x004E4942) bin = buf.slice(off + 8, off + 8 + l); off += 8 + l; }
const CT = {5120:Int8Array,5121:Uint8Array,5122:Int16Array,5123:Uint16Array,5125:Uint32Array,5126:Float32Array};
const NC = {SCALAR:1,VEC2:2,VEC3:3,VEC4:4,MAT4:16};
function acc(i){
  const a = g.accessors[i], bv = g.bufferViews[a.bufferView];
  const TA = CT[a.componentType], n = NC[a.type], st = (bv.byteOffset||0)+(a.byteOffset||0);
  const stride = bv.byteStride || (TA.BYTES_PER_ELEMENT*n);
  const out = new Float32Array(a.count*n);
  for (let k=0;k<a.count;k++) for (let c=0;c<n;c++)
    out[k*n+c] = new TA(bin.buffer, bin.byteOffset+st+k*stride+c*TA.BYTES_PER_ELEMENT, 1)[0];
  return out;
}
/* ---------- 节点 ---------- */
const N = g.nodes.length;
const parent = new Array(N).fill(-1);
g.nodes.forEach((n,i)=>(n.children||[]).forEach(c=>parent[c]=i));
function restTRS(i){ const n=g.nodes[i]; return {
  t:(n.translation||[0,0,0]).slice(), r:(n.rotation||[0,0,0,1]).slice(), s:(n.scale||[1,1,1]).slice() }; }
/* ---------- 动画求值 ---------- */
function animValues(animIdx, node, path){
  const a = g.animations[animIdx];
  const ch = a.channels.find(c=>c.target.node===node && c.target.path===path);
  if (!ch) return null;
  const smp = a.samplers[ch.sampler];
  return { times: acc(smp.input), values: acc(smp.output), interp: smp.interpolation || 'LINEAR',
           comp: NC[g.accessors[smp.output].type],
           nKeys: g.accessors[smp.input].count };
}
function qnorm(q){const L=Math.hypot(q[0],q[1],q[2],q[3])||1;return [q[0]/L,q[1]/L,q[2]/L,q[3]/L];}
function slerp(a,b,t){
  let d=a[0]*b[0]+a[1]*b[1]+a[2]*b[2]+a[3]*b[3];
  if(d<0){b=[-b[0],-b[1],-b[2],-b[3]];d=-d;}
  if(d>0.9995) return qnorm([a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t,a[2]+(b[2]-a[2])*t,a[3]+(b[3]-a[3])*t]);
  const th=Math.acos(d), s=Math.sin(th), w1=Math.sin((1-t)*th)/s, w2=Math.sin(t*th)/s;
  return [a[0]*w1+b[0]*w2,a[1]*w1+b[1]*w2,a[2]*w1+b[2]*w2,a[3]*w1+b[3]*w2];
}
/* 按 three 的方式取值 */
function sample(tr, t, path){
  const {times, values, interp, comp, nKeys} = tr;
  if (interp === 'CUBICSPLINE'){
    const stride = comp*3;
    // 找 i1
    let i1 = 0; while (i1 < nKeys && times[i1] <= t) i1++;
    if (i1 === 0) i1 = 1; if (i1 >= nKeys) i1 = nKeys-1;
    const t1 = times[i1], t0 = times[i1-1], td = t1-t0, p = td>0 ? (t-t0)/td : 0;
    const pp=p*p, ppp=pp*p, s2=-2*ppp+3*pp, s3=ppp-pp, s0=1-s2, s1=s3-pp+p;
    const o1 = i1*stride, o0 = o1-stride;
    const res = [];
    for (let i=0;i<comp;i++){
      const p0=values[o0+i+comp], m0=values[o0+i+comp*2]*td;
      const p1=values[o1+i+comp], m1=values[o1+i]*td;
      res.push(s0*p0+s1*m0+s2*p1+s3*m1);
    }
    return path==='rotation' ? qnorm(res) : res;
  }
  // LINEAR / STEP（读 4 分量对齐）
  let i1=0; while(i1<nKeys && times[i1]<=t) i1++;
  if (i1===0) i1=1; if (i1>=nKeys) i1=nKeys-1;
  const t1=times[i1], t0=times[i1-1], p = t1>t0 ? (t-t0)/(t1-t0) : 0;
  const o1=i1*comp, o0=o1-comp;
  if (path==='rotation') return slerp([values[o0],values[o0+1],values[o0+2],values[o0+3]],
                                      [values[o1],values[o1+1],values[o1+2],values[o1+3]], p);
  const r=[]; for(let i=0;i<comp;i++) r.push(values[o0+i]*(1-p)+values[o1+i]*p);
  return r;
}
/* ---------- 矩阵 ---------- */
function qmat(q){const[x,y,z,w]=q;const x2=x+x,y2=y+y,z2=z+z;
  const xx=x*x2,xy=x*y2,xz=x*z2,yy=y*y2,yz=y*z2,zz=z*z2,wx=w*x2,wy=w*y2,wz=w*z2;
  return [1-(yy+zz),xy-wz,xz+wy, xy+wz,1-(xx+zz),yz-wx, xz-wy,yz+wx,1-(xx+yy)];}
function mul(a,b){const o=new Array(16);for(let r=0;r<4;r++)for(let c=0;c<4;c++){let v=0;
  for(let k=0;k<4;k++) v+=a[k*4+r]*b[c*4+k]; o[c*4+r]=v;} return o;}
function trsMat(t,r,s){const m=qmat(r);
  return [m[0]*s[0],m[1]*s[0],m[2]*s[0],0, m[3]*s[1],m[4]*s[1],m[5]*s[1],0,
          m[6]*s[2],m[7]*s[2],m[8]*s[2],0, t[0],t[1],t[2],1];}
function inv(m){const o=new Array(16);
  const a00=m[0],a01=m[1],a02=m[2],a03=m[3],a10=m[4],a11=m[5],a12=m[6],a13=m[7],
        a20=m[8],a21=m[9],a22=m[10],a23=m[11],a30=m[12],a31=m[13],a32=m[14],a33=m[15];
  const b00=a00*a11-a01*a10,b01=a00*a12-a02*a10,b02=a00*a13-a03*a10,b03=a01*a12-a02*a11,
        b04=a01*a13-a03*a11,b05=a02*a13-a03*a12,b06=a20*a31-a21*a30,b07=a20*a32-a22*a30,
        b08=a20*a33-a23*a30,b09=a21*a32-a22*a31,b10=a21*a33-a23*a31,b11=a22*a33-a23*a32;
  let det=b00*b11-b01*b10+b02*b09+b03*b08-b04*b07+b05*b06; if(!det) return null; det=1/det;
  o[0]=(a11*b11-a12*b10+a13*b09)*det; o[1]=(a02*b10-a01*b11-a03*b09)*det;
  o[2]=(a31*b05-a32*b04+a33*b03)*det; o[3]=(a22*b04-a21*b05-a23*b03)*det;
  o[4]=(a12*b08-a10*b11-a13*b07)*det; o[5]=(a00*b11-a02*b08+a03*b07)*det;
  o[6]=(a32*b02-a30*b05-a33*b01)*det; o[7]=(a20*b05-a22*b02+a23*b01)*det;
  o[8]=(a10*b10-a11*b08+a13*b06)*det; o[9]=(a01*b08-a00*b10-a03*b06)*det;
  o[10]=(a30*b04-a31*b02+a33*b00)*det; o[11]=(a21*b02-a20*b04-a23*b00)*det;
  o[12]=(a11*b07-a10*b09-a12*b06)*det; o[13]=(a00*b09-a01*b07+a02*b06)*det;
  o[14]=(a31*b01-a30*b03-a32*b00)*det; o[15]=(a20*b03-a21*b01+a22*b00)*det;
  return o;}
function xform(m,v){return [m[0]*v[0]+m[4]*v[1]+m[8]*v[2]+m[12], m[1]*v[0]+m[5]*v[1]+m[9]*v[2]+m[13],
  m[2]*v[0]+m[6]*v[1]+m[10]*v[2]+m[14]];}
/* ---------- 计算世界矩阵 ---------- */
function worldMats(animIdx, t, mode){
  const order=[]; (function walk(i){order.push(i);(g.nodes[i].children||[]).forEach(walk);})(g.scene!==undefined?g.scenes[g.scene||0].nodes[0]:g.scenes[0].nodes[0]);
  for(let i=0;i<N;i++) if(!order.includes(i)) order.push(i);
  const wm=new Array(N);
  for(const i of order){
    let {t:tt,r,s}=restTRS(i);
    if (animIdx>=0){
      const vr = animValues(animIdx, i, 'rotation');
      const vt = animValues(animIdx, i, 'translation');
      if (vr && mode!=='rest') {
        if (mode==='linear'){ // 错误解读：把 cubic 数据当线性读
          const comp = 4, nKeys = vr.nKeys;
          const vals = vr.values;
          let i1=0; while(i1<nKeys && vr.times[i1]<=t) i1++;
          if(i1===0)i1=1; if(i1>=nKeys)i1=nKeys-1;
          const t1=vr.times[i1],t0=vr.times[i1-1],p=t1>t0?(t-t0)/(t1-t0):0;
          const o1=i1*comp,o0=o1-comp;
          r = slerp([vals[o0],vals[o0+1],vals[o0+2],vals[o0+3]],[vals[o1],vals[o1+1],vals[o1+2],vals[o1+3]],p);
        } else r = sample(vr, t, 'rotation');
      }
      if (vt && mode!=='rest') tt = sample(vt, t, 'translation');
    }
    const m = trsMat(tt,r,s);
    wm[i] = parent[i]>=0 ? mul(wm[parent[i]], m) : m;
  }
  return wm;
}
function report(label, wm){
  console.log('===== ' + label);
  for (let si=0; si<(g.skins||[]).length; si++){
    const skin=g.skins[si];
    const ibm = acc(skin.inverseBindMatrices);
    const joints = skin.joints;
    const boneMat = joints.map((jn,k)=> mul(wm[jn], ibm.slice(k*16,k*16+16)));
    // 找用这个 skin 的 mesh
    for (let mi=0; mi<g.nodes.length; mi++){
      const node=g.nodes[mi];
      if (node.skin!==si || node.mesh===undefined) continue;
      const mesh=g.meshes[node.mesh];
      for (const prim of mesh.primitives){
        const pos=acc(prim.attributes.POSITION);
        if (!prim.attributes.JOINTS_0) continue;
        const J=acc(prim.attributes.JOINTS_0), W=acc(prim.attributes.WEIGHTS_0);
        const nv = g.accessors[prim.attributes.POSITION].count;
        let mn=[1e9,1e9,1e9], mx=[-1e9,-1e9,-1e9], maxR=0;
        const stride = NC[g.accessors[prim.attributes.POSITION].type];
        for (let v=0;v<nv;v++){
          let acc3=[0,0,0];
          const nj = NC[g.accessors[prim.attributes.JOINTS_0].type];
          for (let k=0;k<nj;k++){
            const w = W[v*nj+k]; if (w<=0) continue;
            const j = J[v*nj+k]; if (j>=joints.length) continue;
            const p = [pos[v*stride],pos[v*stride+1],pos[v*stride+2]];
            const q = xform(boneMat[j], p);
            acc3[0]+=q[0]*w; acc3[1]+=q[1]*w; acc3[2]+=q[2]*w;
          }
          for(let c=0;c<3;c++){ mn[c]=Math.min(mn[c],acc3[c]); mx[c]=Math.max(mx[c],acc3[c]); }
          maxR=Math.max(maxR, Math.hypot(acc3[0],acc3[1],acc3[2]));
        }
        console.log('  skin'+si+' node'+mi+' '+node.name+' prim('+nv+'v): bbox=['+
          mn.map(x=>x.toFixed(2)).join(',')+'] .. ['+mx.map(x=>x.toFixed(2)).join(',')+'] size='+
          [mx[0]-mn[0],mx[1]-mn[1],mx[2]-mn[2]].map(x=>x.toFixed(2)).join('x'));
      }
    }
  }
}
const ai = MODE==='rest' ? -1 : g.animations.findIndex(a=>a.name==='Cafe_Idle');
console.log('mode='+MODE+' t='+T+' anim='+(ai>=0?g.animations[ai].name:'rest'));
report('REST（绑定/静止姿态）', worldMats(-1, 0, 'rest'));
report('动画 @t='+T+'（'+MODE+'）', worldMats(ai, T, MODE));
