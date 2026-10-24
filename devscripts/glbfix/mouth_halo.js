const fs = require('fs');
const buf = fs.readFileSync(process.argv[2] || './model.glb');
const l0 = buf.readUInt32LE(12);
const g = JSON.parse(buf.slice(20, 20 + l0).toString('utf8'));
let off = 20 + l0, bin = null;
while (off < buf.length) { const l = buf.readUInt32LE(off), t = buf.readUInt32LE(off + 4);
  if (t === 0x004E4942) bin = buf.slice(off + 8, off + 8 + l); off += 8 + l; }
const CT={5120:Int8Array,5121:Uint8Array,5122:Int16Array,5123:Uint16Array,5125:Uint32Array,5126:Float32Array};
const NC={SCALAR:1,VEC2:2,VEC3:3,VEC4:4,MAT4:16};
function rawAcc(i){ const a=g.accessors[i],bv=g.bufferViews[a.bufferView],TA=CT[a.componentType],n=NC[a.type];
  const st=(bv.byteOffset||0)+(a.byteOffset||0); return new TA(bin.buffer,bin.byteOffset+st,a.count*n); }

console.log('===== ① 嘴巴各 mesh 的 morph target 到底做了什么 =====');
for (let mi=0; mi<3; mi++){
  const m = g.meshes[mi];
  console.log('mesh'+mi+' "'+m.name+'" targets='+(m.extras&&m.extras.targetNames?JSON.stringify(m.extras.targetNames):'(无名字)')+' 权重数='+(m.weights?JSON.stringify(m.weights):'-'));
  for (const prim of m.primitives){
    const tg = prim.targets;
    if (!tg) { console.log('   (无 targets)'); continue; }
    tg.forEach((t,ti)=>{
      for (const k in t){
        const d = rawAcc(t[k]);
        const n = d.length/3;
        let mn=[1e9,1e9,1e9], mx=[-1e9,-1e9,-1e9];
        for(let i=0;i<n;i++) for(let c=0;c<3;c++){ const v=d[i*3+c]; mn[c]=Math.min(mn[c],v); mx[c]=Math.max(mx[c],v); }
        console.log('   target'+ti+' '+k+': 位移范围 ['+mn.map(x=>x.toFixed(3))+'] .. ['+mx.map(x=>x.toFixed(3))+']');
      }
    });
  }
}
console.log('\n===== ② 动画里 Mouth 的 weights 值（Cafe_Idle） =====');
const idle = g.animations.find(a=>a.name==='Cafe_Idle');
idle.channels.filter(c=>{
  const nm = g.nodes[c.target.node].name||'';
  return /^Mouth_/.test(nm) && c.target.path==='weights';
}).slice(0,6).forEach(c=>{
  const smp = idle.samplers[c.sampler];
  const out = rawAcc(smp.output), inp = rawAcc(smp.input);
  console.log('  '+g.nodes[c.target.node].name.padEnd(12)+' keys='+out.length+' 值='+Array.from(out).map(x=>+x.toFixed(2)).join(','));
});

console.log('\n===== ③ 光环 mesh 与材质 =====');
const haloNode = g.nodes.findIndex(n=>n.name==='CH0335_Halo' && n.mesh!==undefined);
const hm = g.nodes[haloNode].mesh;
console.log('  node'+haloNode+' mesh='+hm+' "'+g.meshes[hm].name+'"');
g.meshes[hm].primitives.forEach(p=>{
  console.log('   material='+p.material+' 属性='+Object.keys(p.attributes).join(','));
  const mat = g.materials[p.material];
  console.log('   材质: name='+mat.name+' alphaMode='+(mat.alphaMode||'OPAQUE')
    +' doubleSided='+!!mat.doubleSided
    +' baseColor='+JSON.stringify((mat.pbrMetallicRoughness||{}).baseColorFactor||[1,1,1,1])
    +' texture='+JSON.stringify((mat.pbrMetallicRoughness||{}).baseColorTexture||null)
    +' 全部键='+Object.keys(mat).join(','));
});
console.log('\n===== ④ 所有带贴图材质的贴图索引（看光环用哪张） =====');
g.materials.forEach((m,i)=>{
  const bt = (m.pbrMetallicRoughness||{}).baseColorTexture;
  if (!bt) return;
  const img = g.textures[bt.index];
  const src = g.images[img.source];
  console.log('  mat'+String(i).padStart(2)+' '+String(m.name).padEnd(26)+' alphaMode='+(m.alphaMode||'OPAQUE').padEnd(7)
    +' tex='+bt.index+' image='+img.source+' mime='+(src?src.mimeType:'?')+' bv='+(src?src.bufferView:'?'));
});
