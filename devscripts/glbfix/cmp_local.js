/* 直接对比：Skeleton.pose() 写下的 local TRS vs node 默认 TRS（逐根骨骼） */
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
for (let si=0; si<g.skins.length; si++){
  const sk=g.skins[si], ibm=acc(sk.inverseBindMatrices), joints=sk.joints;
  const jointIdx = new Map(joints.map((j,k)=>[j,k]));
  const poseWorld = joints.map((jn,k)=> inv(ibm.slice(k*16,k*16+16)));
  let diff=0, maxd=0, worst='', noParentJoint=0;
  joints.forEach((jn,k)=>{
    const pi = jointIdx.has(parent[jn]) ? jointIdx.get(parent[jn]) : -1;
    if (pi < 0) noParentJoint++;
    const poseLocal = (pi>=0) ? mul(inv(poseWorld[pi]), poseWorld[k]) : poseWorld[k].slice();
    const n = g.nodes[jn];
    const nodeLocal = (function(t,r,s){const m=qmat(r);
      return [m[0]*s[0],m[1]*s[0],m[2]*s[0],0, m[3]*s[1],m[4]*s[1],m[5]*s[1],0, m[6]*s[2],m[7]*s[2],m[8]*s[2],0, t[0],t[1],t[2],1];})(
      n.translation||[0,0,0], n.rotation||[0,0,0,1], n.scale||[1,1,1]);
    let d=0; for(let q=0;q<16;q++) d=Math.max(d, Math.abs(poseLocal[q]-nodeLocal[q]));
    if (d>0.001){ diff++; if(d>maxd){maxd=d;worst=n.name;} }
  });
  console.log('skin'+si+' joints='+joints.length
    +'  与 node TRS 不一致的 joint 数 = '+diff+' (最大偏差 '+maxd.toFixed(3)+(worst?', '+worst:'')+')'
    +'  其中"父不是joint"的='+noParentJoint);
}
