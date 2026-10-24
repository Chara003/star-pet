const fs = require('fs');
const buf = fs.readFileSync(process.argv[2] || './model.glb');
let off = 12, g = null, bin = null;
while (off < buf.length) {
  const len = buf.readUInt32LE(off), type = buf.readUInt32LE(off + 4);
  const data = buf.slice(off + 8, off + 8 + len);
  if (type === 0x4E4F534A) g = JSON.parse(data.toString('utf8'));
  else if (type === 0x004E4942) bin = data;
  off += 8 + len;
}
// accessor reader
const CT = {5120: Int8Array,5121: Uint8Array,5122: Int16Array,5123: Uint16Array,5125: Uint32Array,5126: Float32Array};
const NC = {SCALAR:1,VEC2:2,VEC3:3,VEC4:4,MAT4:16};
function acc(i){
  const a = g.accessors[i];
  const bv = g.bufferViews[a.bufferView];
  const TA = CT[a.componentType], n = NC[a.type];
  const start = (bv.byteOffset||0) + (a.byteOffset||0);
  return new TA(bin.buffer, bin.byteOffset + start, a.count*n);
}
console.log('=== animations ===');
g.animations.forEach((an,i)=>{
  const targets = new Set();
  let tv = 0;
  an.channels.forEach(ch=>{ targets.add(ch.target.node); tv += (ch.target.path==='translation'?1:0); });
  const hair = [...targets].filter(t=>(g.nodes[t].name||'').includes('hair'));
  const skirt = [...targets].filter(t=>(g.nodes[t].name||'').includes('skirt'));
  console.log(i, JSON.stringify(an.name), 'ch='+an.channels.length, 'nodes='+targets.size,
    'transCh='+tv, 'hair='+hair.length, 'skirt='+skirt.length);
});
console.log('=== node names ===');
const byName = n => g.nodes.findIndex(x=>x.name===n);
console.log('bone_hair_* count', g.nodes.filter(n=>(n.name||'').includes('hair')).length);
console.log('bone_skirt_* count', g.nodes.filter(n=>(n.name||'').includes('skirt')).length);
// skins membership
(g.skins||[]).forEach((s,i)=>{
  const set = new Set(s.joints);
  const names = s.joints.map(j=>g.nodes[j].name);
  console.log('skin'+i, 'joints='+names.length, names.slice(0,8).join(','), '...');
});
// check overlap between skins
for (let i=0;i<g.skins.length;i++) for (let j=i+1;j<g.skins.length;j++){
  const a=new Set(g.skins[i].joints); const ov=g.skins[j].joints.filter(x=>a.has(x));
  if (ov.length) console.log('overlap skin'+i+'/'+j+':', ov.map(x=>g.nodes[x].name).join(','));
}
