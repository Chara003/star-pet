const fs = require('fs');
const buf = fs.readFileSync(process.argv[2] || './model.glb');
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
console.log('=== 属性类型检查 ===');
g.meshes.forEach((m,i)=>{
  m.primitives.forEach(p=>{
    ['POSITION','JOINTS_0','WEIGHTS_0'].forEach(k=>{
      if (p.attributes[k]===undefined) return;
      const a = g.accessors[p.attributes[k]];
      console.log('  mesh'+i+' '+k+' ct='+a.componentType+' type='+a.type+' normalized='+!!a.normalized+' count='+a.count);
    });
    return;
  });
  if (i>2) return;
});
console.log('\n=== 假如所有 boneMatrix=I（= 纯权重求和）的结果，应等于 POSITION min/max ===');
(function(){
  const node = g.nodes.find(n=>n.name==='CH0335_Body' && n.mesh!==undefined);
  const mi = node.mesh;
  const prim = g.meshes[mi].primitives[0];
  const pos = acc(prim.attributes.POSITION), J = acc(prim.attributes.JOINTS_0), W = acc(prim.attributes.WEIGHTS_0);
  const nv = g.accessors[prim.attributes.POSITION].count;
  const nj = NC[g.accessors[prim.attributes.JOINTS_0].type];
  const stride = NC[g.accessors[prim.attributes.POSITION].type];
  let mn=[1e9,1e9,1e9], mx=[-1e9,-1e9,-1e9], wsumMin=9, wsumMax=0;
  for (let v=0;v<nv;v++){
    let p=[0,0,0], ws=0;
    for(let k=0;k<nj;k++){ const w=W[v*nj+k]; ws+=w;
      p[0]+=pos[v*stride]*w; p[1]+=pos[v*stride+1]*w; p[2]+=pos[v*stride+2]*w; }
    wsumMin=Math.min(wsumMin,ws); wsumMax=Math.max(wsumMax,ws);
    for(let c=0;c<3;c++){ mn[c]=Math.min(mn[c],p[c]); mx[c]=Math.max(mx[c],p[c]); }
  }
  console.log('  权重和范围: '+wsumMin.toFixed(3)+' ~ '+wsumMax.toFixed(3)+(Math.abs(wsumMax-1)>0.01?'   ★★ 权重和不是1 → 我的读取或 normalized 有问题':'   ✓ 权重和为1'));
  console.log('  结果 bbox: ['+mn.map(x=>x.toFixed(3))+'] .. ['+mx.map(x=>x.toFixed(3))+']');
  console.log('  POSITION min/max: ['+g.accessors[prim.attributes.POSITION].min.map(x=>x.toFixed(3))+'] .. ['+g.accessors[prim.attributes.POSITION].max.map(x=>x.toFixed(3))+']');
})();
