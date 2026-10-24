/* 把"蒙皮后的顶点"投影成 ASCII 图，一眼看模型是竖是躺 */
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
function trsMat(t,r,s){const m=qmat(r);
  return [m[0]*s[0],m[1]*s[0],m[2]*s[0],0, m[3]*s[1],m[4]*s[1],m[5]*s[1],0, m[6]*s[2],m[7]*s[2],m[8]*s[2],0, t[0],t[1],t[2],1];}
function xf(m,v){return [m[0]*v[0]+m[4]*v[1]+m[8]*v[2]+m[12], m[1]*v[0]+m[5]*v[1]+m[9]*v[2]+m[13], m[2]*v[0]+m[6]*v[1]+m[10]*v[2]+m[14]];}
function worldMats(){
  const order=[]; (function walk(i){order.push(i);(g.nodes[i].children||[]).forEach(walk);})(1);
  for(let i=0;i<N;i++) if(!order.includes(i)) order.push(i);
  const wm=new Array(N);
  for(const i of order){ const n=g.nodes[i];
    const m=trsMat(n.translation||[0,0,0], n.rotation||[0,0,0,1], n.scale||[1,1,1]);
    wm[i]=parent[i]>=0? mul(wm[parent[i]], m) : m; }
  return wm;
}
const wm = worldMats();
function skinPoints(si, nodeIdx){
  const sk=g.skins[si];
  const ibm=acc(sk.inverseBindMatrices);
  const bms = sk.joints.map((jn,k)=>mul(wm[jn], ibm.slice(k*16,k*16+16)));
  const nd = g.nodes[nodeIdx];
  const prims = g.meshes[nd.mesh].primitives;
  const pts=[];
  const mw = wm[nodeIdx] || null;   // mesh 节点自己的世界矩阵（通常是 scene root = identity）
  for (const prim of prims){
    if (prim.attributes.JOINTS_0===undefined) continue;
    const pos=acc(prim.attributes.POSITION), J=acc(prim.attributes.JOINTS_0), W=acc(prim.attributes.WEIGHTS_0);
    const nv=g.accessors[prim.attributes.POSITION].count;
    const nj=NC[g.accessors[prim.attributes.JOINTS_0].type];
    const stride=NC[g.accessors[prim.attributes.POSITION].type];
    for(let v=0;v<nv;v++){
      let p=[0,0,0];
      for(let k=0;k<nj;k++){ const w=W[v*nj+k]; if(w<=0)continue;
        const j=J[v*nj+k]; if(j>=bms.length)continue;
        const q=xf(bms[j],[pos[v*stride],pos[v*stride+1],pos[v*stride+2]]);
        p[0]+=q[0]*w; p[1]+=q[1]*w; p[2]+=q[2]*w; }
      pts.push(p);
    }
  }
  return pts;
}
function ascii(pts, ax, ay, W=64, H=26){
  let mnx=1e9,mxx=-1e9,mny=1e9,mxy=-1e9;
  pts.forEach(p=>{ mnx=Math.min(mnx,p[ax]);mxx=Math.max(mxx,p[ax]);mny=Math.min(mny,p[ay]);mxy=Math.max(mxy,p[ay]); });
  const grid=Array.from({length:H},()=>new Array(W).fill(' '));
  pts.forEach(p=>{
    const u=Math.floor((p[ax]-mnx)/((mxx-mnx)||1)*(W-1));
    const v=Math.floor((p[ay]-mny)/((mxy-mny)||1)*(H-1));
    grid[H-1-v][u]='#';
  });
  console.log('  x范围['+mnx.toFixed(2)+','+mxx.toFixed(2)+'] y范围['+mny.toFixed(2)+','+mxy.toFixed(2)+']');
  grid.forEach(r=>console.log('  |'+r.join('')+'|'));
}
console.log('===== CH0335_Body 蒙皮后顶点：X-Y 投影（屏幕视角，Y 向上）=====');
ascii(skinPoints(0,200), 0, 1);
console.log('\n===== 同一批顶点：Z-Y 投影（侧面视角）=====');
ascii(skinPoints(0,200), 2, 1);
