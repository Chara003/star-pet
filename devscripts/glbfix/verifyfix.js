/* 验证：vmdResetPose 用 Skeleton.pose() vs applyGlbRestPose() 对头发的影响 */
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
/* ---- 矩阵工具 ---- */
function qmat(q){const[x,y,z,w]=q;const x2=x+x,y2=y+y,z2=z+z;
  const xx=x*x2,xy=x*y2,xz=x*z2,yy=y*y2,yz=y*z2,zz=z*z2,wx=w*x2,wy=w*y2,wz=w*z2;
  return [1-(yy+zz),xy-wz,xz+wy, xy+wz,1-(xx+zz),yz-wx, xz-wy,yz+wx,1-(xx+yy)];}
function mul(a,b){const o=new Array(16);for(let r=0;r<4;r++)for(let c=0;c<4;c++){let v=0;for(let k=0;k<4;k++)v+=a[k*4+r]*b[c*4+k];o[c*4+r]=v;}return o;}
function trsMat(t,r,s){const m=qmat(r);
  return [m[0]*s[0],m[1]*s[0],m[2]*s[0],0, m[3]*s[1],m[4]*s[1],m[5]*s[1],0, m[6]*s[2],m[7]*s[2],m[8]*s[2],0, t[0],t[1],t[2],1];}
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
function decompose(m){ // 返回 {t, q(近似欧拉→四元数), s}
  const t=[m[12],m[13],m[14]];
  const sx=Math.hypot(m[0],m[1],m[2]), sy=Math.hypot(m[4],m[5],m[6]), sz=Math.hypot(m[8],m[9],m[10]);
  const s=[sx,sy,sz];
  const r=[m[0]/sx,m[1]/sx,m[2]/sx, m[4]/sy,m[5]/sy,m[6]/sy, m[8]/sz,m[9]/sz,m[10]/sz];
  // 旋转矩阵 → 四元数
  const tr=r[0]+r[4]+r[8];
  let q;
  if(tr>0){const S=Math.sqrt(tr+1)*2;q=[(r[7]-r[5])/S,(r[2]-r[6])/S,(r[3]-r[1])/S,0.25*S];}
  else if(r[0]>r[4]&&r[0]>r[8]){const S=Math.sqrt(1+r[0]-r[4]-r[8])*2;q=[0.25*S,(r[1]+r[3])/S,(r[2]+r[6])/S,(r[7]-r[5])/S];}
  else if(r[4]>r[8]){const S=Math.sqrt(1+r[4]-r[0]-r[8])*2;q=[(r[1]+r[3])/S,0.25*S,(r[5]+r[7])/S,(r[2]-r[6])/S];}
  else{const S=Math.sqrt(1+r[8]-r[0]-r[4])*2;q=[(r[2]+r[6])/S,(r[5]+r[7])/S,0.25*S,(r[3]-r[1])/S];}
  return {t,q,s};
}
/* ---- 1) 算出 Skeleton.pose() 会写下的 local TRS ---- */
const sk0=g.skins[0], ibm=acc(sk0.inverseBindMatrices);
const joints=sk0.joints;
const poseWorld = joints.map((jn,k)=> inv(ibm.slice(k*16,k*16+16)));   // bind-time world
const poseLocal = joints.map(()=>null);
joints.forEach((jn,k)=>{
  const pi = joints.indexOf(parent[jn]);      // 父是不是 joint
  if (pi >= 0) poseLocal[k] = mul(inv(poseWorld[pi]), poseWorld[k]);
  else poseLocal[k] = poseWorld[k].slice();
});
console.log('=== Skeleton.pose() 会写下的 local TRS（前 6 个 joint）===');
joints.slice(0,6).forEach((jn,k)=>{
  const d = decompose(poseLocal[k]);
  console.log('  '+g.nodes[jn].name.padEnd(22)+' T=['+d.t.map(x=>x.toFixed(3))+']  S=['+d.s.map(x=>x.toFixed(3))+']');
});
console.log('\n=== node 默认 TRS（applyGlbRestPose 会写的，前 6 个）===');
joints.slice(0,6).forEach(jn=>{
  const n=g.nodes[jn];
  console.log('  '+n.name.padEnd(22)+' T=['+(n.translation||[0,0,0]).map(x=>x.toFixed(3))+']  S=['+(n.scale||[1,1,1]).map(x=>x.toFixed(3))+']');
});
/* ---- 2) 看谁被动画写过 position ---- */
const idle = g.animations.find(a=>a.name==='Cafe_Idle');
const animT = new Set(), animR = new Set();
idle.channels.forEach(c=>{ const nm=g.nodes[c.target.node].name;
  if (c.target.path==='translation') animT.add(c.target.node);
  if (c.target.path==='rotation') animR.add(c.target.node); });
/* ---- 3) 两套 world 计算 ---- */
function buildWorld(usePoseForUncovered){
  const order=[]; (function walk(i){order.push(i);(g.nodes[i].children||[]).forEach(walk);})(1);
  for(let i=0;i<N;i++) if(!order.includes(i)) order.push(i);
  const wm=new Array(N);
  for(const i of order){
    let t,r,s;
    const k = joints.indexOf(i);
    const covered = animT.has(i) || animR.has(i);
    if (k>=0 && usePoseForUncovered && !covered){
      const d = decompose(poseLocal[k]); t=d.t; r=d.q; s=d.s;
    } else {
      const n=g.nodes[i]; t=(n.translation||[0,0,0]).slice(); r=(n.rotation||[0,0,0,1]).slice(); s=(n.scale||[1,1,1]).slice();
    }
    const m=trsMat(t,r,s);
    wm[i]=parent[i]>=0? mul(wm[parent[i]], m) : m;
  }
  return wm;
}
function hairBox(wm){
  let mn=[1e9,1e9,1e9],mx=[-1e9,-1e9,-1e9],n=0;
  g.nodes.forEach((nd,i)=>{
    if(!/^bone_hair/.test(nd.name||'')) return;
    const p=[wm[i][12],wm[i][13],wm[i][14]];
    for(let c=0;c<3;c++){mn[c]=Math.min(mn[c],p[c]);mx[c]=Math.max(mx[c],p[c]);}
    n++;
  });
  return {mn,mx,n};
}
for (const [label, usePose] of [['applyGlbRestPose（修复后：未覆盖的用 node TRS）', false],
                                ['Skeleton.pose()（修复前：未覆盖的用 bind pose）', true]]) {
  const wm = buildWorld(usePose);
  const b = hairBox(wm);
  console.log('\n===== '+label+' =====');
  console.log('  hair('+b.n+'根) 世界坐标 bbox: ['+b.mn.map(x=>x.toFixed(3))+'] .. ['+b.mx.map(x=>x.toFixed(3))+']');
  console.log('  跨度: X='+(b.mx[0]-b.mn[0]).toFixed(3)+'  Y='+(b.mx[1]-b.mn[1]).toFixed(3)+'  Z='+(b.mx[2]-b.mn[2]).toFixed(3));
}
/* ASCII 对比 */
function asciiHair(wm, title){
  const pts=[];
  g.nodes.forEach((nd,i)=>{ if(/^bone_hair/.test(nd.name||'')) pts.push([wm[i][12],wm[i][13],wm[i][14]]); });
  let mnx=1e9,mxx=-1e9,mny=1e9,mxy=-1e9;
  pts.forEach(p=>{mnx=Math.min(mnx,p[0]);mxx=Math.max(mxx,p[0]);mny=Math.min(mny,p[1]);mxy=Math.max(mxy,p[1]);});
  const W=48,H=16,grid=Array.from({length:H},()=>new Array(W).fill(' '));
  pts.forEach(p=>{const u=Math.floor((p[0]-mnx)/((mxx-mnx)||1)*(W-1));
    const v=Math.floor((p[1]-mny)/((mxy-mny)||1)*(H-1)); grid[H-1-v][u]='#';});
  console.log('\n--- '+title+'（X-Y 投影；宽=横向）x跨度='+(mxx-mnx).toFixed(2)+' y跨度='+(mxy-mny).toFixed(2));
  grid.forEach(r=>console.log('  |'+r.join('')+'|'));
}
asciiHair(buildWorld(false), '修复后');
asciiHair(buildWorld(true),  '修复前');
