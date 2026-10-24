const fs = require('fs');
const buf = fs.readFileSync(process.argv[2] || './model.glb');
const l0 = buf.readUInt32LE(12);
const g = JSON.parse(buf.slice(20, 20 + l0).toString('utf8'));
let off = 20 + l0, bin = null;
while (off < buf.length) { const l = buf.readUInt32LE(off), t = buf.readUInt32LE(off + 4);
  if (t === 0x004E4942) bin = buf.slice(off + 8, off + 8 + l); off += 8 + l; }
const CT={5120:Int8Array,5121:Uint8Array,5122:Int16Array,5123:Uint16Array,5125:Uint32Array,5126:Float32Array};
const NC={SCALAR:1,VEC2:2,VEC3:3,VEC4:4,MAT4:16};
function accInfo(i){const a=g.accessors[i];return {count:a.count,type:a.type,ct:a.componentType,bv:a.bufferView,off:a.byteOffset,bvOff:(g.bufferViews[a.bufferView]||{}).byteOffset,bvLen:(g.bufferViews[a.bufferView]||{}).byteLength,stride:(g.bufferViews[a.bufferView]||{}).byteStride};}
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
function trsMat(t,r,s){const m=qmat(r);
  return [m[0]*s[0],m[1]*s[0],m[2]*s[0],0, m[3]*s[1],m[4]*s[1],m[5]*s[1],0, m[6]*s[2],m[7]*s[2],m[8]*s[2],0, t[0],t[1],t[2],1];}
function fmt(m){ const r=[]; for(let row=0;row<4;row++) r.push('['+[0,1,2,3].map(c=>m[c*4+row].toFixed(3).padStart(7)).join(' ')+']'); return r.join('\n  '); }
const order=[]; (function walk(i){order.push(i);(g.nodes[i].children||[]).forEach(walk);})(1);
for(let i=0;i<N;i++) if(!order.includes(i)) order.push(i);
const wm=new Array(N);
for(const i of order){ const n=g.nodes[i];
  const m=trsMat(n.translation||[0,0,0], n.rotation||[0,0,0,1], n.scale||[1,1,1]);
  wm[i]=parent[i]>=0? mul(wm[parent[i]], m) : m; }

const sk=g.skins[0];
console.log('skin0: joints='+sk.joints.length+'  ibm accessor='+JSON.stringify(accInfo(sk.inverseBindMatrices)));
const ibm=acc(sk.inverseBindMatrices);
console.log('ibm 读出长度 = '+ibm.length+' (期望 '+(sk.joints.length*16)+')');
// 找 Bip001 Head 在 joints 里的下标
const hi = sk.joints.findIndex(j=>g.nodes[j].name==='Bip001 Head');
console.log('\n--- Bip001 Head 在 joints['+hi+'] (node '+sk.joints[hi]+') ---');
console.log('  wm (node TRS 世界矩阵):\n  '+fmt(wm[sk.joints[hi]]));
console.log('  ibm['+hi+'] :\n  '+fmt(ibm.slice(hi*16,hi*16+16)));
console.log('  boneMatrix = wm * ibm :\n  '+fmt(mul(wm[sk.joints[hi]], ibm.slice(hi*16,hi*16+16))));
console.log('\n--- bone_root 在 joints 里吗? '+(sk.joints.indexOf(8))+'  Bip001 在? '+(sk.joints.indexOf(7)));
console.log('--- CH0335_Mesh(1) 在 joints 里吗? '+(sk.joints.indexOf(1)));
const pj = sk.joints.findIndex(j=>g.nodes[j].name==='Bip001 Pelvis');
console.log('\n--- Bip001 Pelvis joints['+pj+'] ---');
console.log('  wm:\n  '+fmt(wm[sk.joints[pj]]));
console.log('  boneMatrix:\n  '+fmt(mul(wm[sk.joints[pj]], ibm.slice(pj*16,pj*16+16))));
// 关键：jm 里有没有那个 90 度
console.log('\n=== 结论检查：把 body 的顶点用 boneMatrix 算，平移量是多少 ===');
const bj = sk.joints.findIndex(j=>g.nodes[j].name==='Bip001 Head');
const bm = mul(wm[sk.joints[bj]], ibm.slice(bj*16,bj*16+16));
console.log('  Bip001 Head boneMatrix 平移 = ('+bm[12].toFixed(3)+','+bm[13].toFixed(3)+','+bm[14].toFixed(3)+')');
