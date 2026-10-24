/* 分通道模拟：position / quaternion 各自独立判断"有没有被动画写" */
const fs = require('fs');
const buf = fs.readFileSync(process.argv[2] || './model.glb');
const l0 = buf.readUInt32LE(12);
const g = JSON.parse(buf.slice(20, 20 + l0).toString('utf8'));
let off = 20 + l0, bin = null;
while (off < buf.length) { const l = buf.readUInt32LE(off), t = buf.readUInt32LE(off + 4);
  if (t === 0x004E4942) bin = buf.slice(off + 8, off + 8 + l); off += 8 + l; }
const CT={5120:Int8Array,5121:Uint8Array,5122:Int16Array,5123:Uint16Array,5125:Uint32Array,5126:Float32Array};
const NC={SCALAR:1,VEC2:2,VEC3:3,VEC4:4,MAT4:16};
function acc(i){ const a=g.accessors[i],bv=g.bufferViews[a.bufferView],TA=CT[a.componentType],n=NC[a.type];
  const st=(bv.byteOffset||0)+(a.byteOffset||0); const stride=bv.byteStride||TA.BYTES_PER_ELEMENT*n;
  const out=new Float32Array(a.count*n);
  for(let k=0;k<a.count;k++)for(let c=0;c<n;c++) out[k*n+c]=new TA(bin.buffer,bin.byteOffset+st+k*stride+c*TA.BYTES_PER_ELEMENT,1)[0];
  return out; }
const N=g.nodes.length; const parent=new Array(N).fill(-1);
g.nodes.forEach((n,i)=>(n.children||[]).forEach(c=>parent[c]=i));
function qmat(q){const[x,y,z,w]=q;const x2=x+x,y2=y+y,z2=z+z;
  const xx=x*x2,xy=x*y2,xz=x*z2,yy=y*y2,yz=y*z2,zz=z*z2,wx=w*x2,wy=w*y2,wz=w*z2;
  return [1-(yy+zz),xy-wz,xz+wy, xy+wz,1-(xx+zz),yz-wx, xz-wy,yz+wx,1-(xx+yy)];}
function mul(a,b){const o=new Array(16);for(let r=0;r<4;r++)for(let c=0;c<4;c++){let v=0;for(let k=0;k<4;k++)v+=a[k*4+r]*b[c*4+k];o[c*4+r]=v;}return o;}
function inv(m){const o=new Array(16);
  const a00=m[0],a01=m[1],a02=m[2],a03=m[3],a10=m[4],a11=m[5],a12=m[6],a13=m[7],
        a20=m[8],a21=m[9],a22=m[10],a23=m[11],a30=m[12],a31=m[13],a32=m[14],a33=m[15];
  const b00=a00*a11-a01*a10,b01=a00*a12-a02*a10,b02=a00*a13-a03*a10,b03=a01*a12-a02*a11,
        b04=a01*a13-a03*a11,b05=a02*a13-a03*a12,b06=a20*a31-a21*a30,b07=a20*a32-a22*a30,
        b08=a20*a33-a23*a30,b09=a21*a32-a22*a31,b10=a21*a33-a23*a31,b11=a22*a33-a23*a32;
  let det=b00*b11-b01*b10+b02*b09+b03*b08-b04*b07+b05*b06; if(!det)return null; det=1/det;
  o[0]=(a11*b11-a12*b10+a13*b09)*det;o[1]=(a02*b10-a01*b11-a03*b09)*det;
  o[2]=(a31*b05-a32*b04+a33*b03)*det;o[3]=(a22*b04-a21*b05-a23*b03)*det;
  o[4]=(a12*b08-a10*b11-a13*b07)*det;o[5]=(a00*b11-a02*b08+a03*b07)*det;
  o[6]=(a32*b02-a30*b05-a33*b01)*det;o[7]=(a20*b05-a22*b02+a23*b01)*det;
  o[8]=(a10*b10-a11*b08+a13*b06)*det;o[9]=(a01*b08-a00*b10-a03*b06)*det;
  o[10]=(a30*b04-a31*b02+a33*b00)*det;o[11]=(a21*b02-a20*b04-a23*b00)*det;
  o[12]=(a11*b07-a10*b09-a12*b06)*det;o[13]=(a00*b09-a01*b07+a02*b06)*det;
  o[14]=(a31*b01-a30*b03-a32*b00)*det;o[15]=(a20*b03-a21*b01+a22*b00)*det;
  return o;}
function trsMat(t,r,s){const m=qmat(r);
  return [m[0]*s[0],m[1]*s[0],m[2]*s[0],0, m[3]*s[1],m[4]*s[1],m[5]*s[1],0, m[6]*s[2],m[7]*s[2],m[8]*s[2],0, t[0],t[1],t[2],1];}
function poseLocalTRS(){   // 算出 Skeleton.pose() 会写下的 local T/R/S
  const out = {};
  for (const sk of g.skins){
    const ibm=acc(sk.inverseBindMatrices), joints=sk.joints;
    const jmap = new Map(joints.map((j,k)=>[j,k]));
    const pw = joints.map((jn,k)=> inv(ibm.slice(k*16,k*16+16)));
    joints.forEach((jn,k)=>{
      const pi = jmap.has(parent[jn]) ? jmap.get(parent[jn]) : -1;
      const lm = (pi>=0) ? mul(inv(pw[pi]), pw[k]) : pw[k].slice();
      // decompose（只取平移和四元数，够用）
      const t=[lm[12],lm[13],lm[14]];
      const sx=Math.hypot(lm[0],lm[1],lm[2]), sy=Math.hypot(lm[4],lm[5],lm[6]), sz=Math.hypot(lm[8],lm[9],lm[10]);
      const r=[lm[0]/sx,lm[1]/sx,lm[2]/sx, lm[4]/sy,lm[5]/sy,lm[6]/sy, lm[8]/sz,lm[9]/sz,lm[10]/sz];
      const tr=r[0]+r[4]+r[8]; let q;
      if(tr>0){const S=Math.sqrt(tr+1)*2;q=[(r[7]-r[5])/S,(r[2]-r[6])/S,(r[3]-r[1])/S,0.25*S];}
      else if(r[0]>r[4]&&r[0]>r[8]){const S=Math.sqrt(1+r[0]-r[4]-r[8])*2;q=[0.25*S,(r[1]+r[3])/S,(r[2]+r[6])/S,(r[7]-r[5])/S];}
      else if(r[4]>r[8]){const S=Math.sqrt(1+r[4]-r[0]-r[8])*2;q=[(r[1]+r[3])/S,0.25*S,(r[5]+r[7])/S,(r[2]-r[6])/S];}
      else{const S=Math.sqrt(1+r[8]-r[0]-r[4])*2;q=[(r[2]+r[6])/S,(r[5]+r[7])/S,0.25*S,(r[3]-r[1])/S];}
      out[jn] = { t, q, s:[sx,sy,sz] };
    });
  }
  return out;
}
const POSE = poseLocalTRS();
const idle = g.animations.find(a=>a.name==='Cafe_Idle');
const hasT = new Set(), hasR = new Set();
// 原始轨道的 t=0 值（用 first keyframe 的 splineVertex；简单起见只用来判断"有没有"）
idle.channels.forEach(c=>{ if(c.target.path==='translation') hasT.add(c.target.node);
  if(c.target.path==='rotation') hasR.add(c.target.node); });
function build(resetMode){   // resetMode: 'rest' | 'pose'
  const order=[]; (function walk(i){order.push(i);(g.nodes[i].children||[]).forEach(walk);})(1);
  for(let i=0;i<N;i++) if(!order.includes(i)) order.push(i);
  const wm=new Array(N);
  for(const i of order){
    const n=g.nodes[i];
    let t=(n.translation||[0,0,0]).slice(), r=(n.rotation||[0,0,0,1]).slice(), s=(n.scale||[1,1,1]).slice();
    const pl = POSE[i];
    if (resetMode==='pose' && pl){ t=pl.t.slice(); r=pl.q.slice(); s=pl.s.slice(); }
    /* ★ 动画【只覆盖有对应轨道的通道】—— 这正是 bug 的关键 */
    if (hasT.has(i)) t = t;      // 我们这里不做插值，只关心"没轨道的会留在复位值"
    if (hasR.has(i)) r = r;
    const m=trsMat(t,r,s);
    wm[i]=parent[i]>=0? mul(wm[parent[i]], m) : m;
  }
  return wm;
}
function hairStat(wm, label){
  const pts=[]; g.nodes.forEach((nd,i)=>{ if(/^bone_hair/.test(nd.name||'')) pts.push([wm[i][12],wm[i][13],wm[i][14]]); });
  let mn=[1e9,1e9,1e9],mx=[-1e9,-1e9,-1e9];
  pts.forEach(p=>{for(let c=0;c<3;c++){mn[c]=Math.min(mn[c],p[c]);mx[c]=Math.max(mx[c],p[c]);}});
  console.log('\n== '+label);
  console.log('   hair bbox ['+mn.map(x=>x.toFixed(3))+'] .. ['+mx.map(x=>x.toFixed(3))+']'
    +'  跨度 X='+(mx[0]-mn[0]).toFixed(3)+' Y='+(mx[1]-mn[1]).toFixed(3)+' Z='+(mx[2]-mn[2]).toFixed(3));
  const W=56,H=18; let mnx=1e9,mxx=-1e9,mny=1e9,mxy=-1e9;
  pts.forEach(p=>{mnx=Math.min(mnx,p[0]);mxx=Math.max(mxx,p[0]);mny=Math.min(mny,p[1]);mxy=Math.max(mxy,p[1]);});
  const grid=Array.from({length:H},()=>new Array(W).fill(' '));
  pts.forEach(p=>{const u=Math.floor((p[0]-mnx)/((mxx-mnx)||1)*(W-1));const v=Math.floor((p[1]-mny)/((mxy-mny)||1)*(H-1));grid[H-1-v][u]='#';});
  grid.forEach(rw=>console.log('   |'+rw.join('')+'|'));
}
hairStat(build('rest'), '修复后（vmdResetPose 用 glbRestPose）');
hairStat(build('pose'), '修复前（vmdResetPose 用 Skeleton.pose()）');
