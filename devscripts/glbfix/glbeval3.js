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
function dumpAcc(i){
  const a = g.accessors[i];
  console.log('  accessor', i, JSON.stringify({type:a.type,ct:a.componentType,count:a.count,byteOffset:a.byteOffset,bv:a.bufferView,normalized:a.normalized,sparse:!!a.sparse, min:a.min,max:a.max}));
  if (a.bufferView!==undefined) console.log('  bufferView', JSON.stringify(g.bufferViews[a.bufferView]));
}
const anim = g.animations.find(a=>a.name==='Cafe_Idle');
const ch = anim.channels.find(c=>g.nodes[c.target.node].name==='Bip001 Spine' && c.target.path==='rotation');
const smp = anim.samplers[ch.sampler];
console.log('sampler', JSON.stringify(smp));
dumpAcc(smp.input); dumpAcc(smp.output);
// read raw
const a = g.accessors[smp.output]; const bv = g.bufferViews[a.bufferView];
const start = (bv.byteOffset||0) + (a.byteOffset||0);
const f = new Float32Array(bin.buffer, bin.byteOffset + start, a.count*4);
console.log('first 6 rotation keys:', Array.from(f.slice(0,24)).map(x=>+x.toFixed(4)));
const ti = new Float32Array(bin.buffer, bin.byteOffset + (g.bufferViews[g.accessors[smp.input].bufferView].byteOffset||0), g.accessors[smp.input].count);
console.log('times:', Array.from(ti).map(x=>+x.toFixed(3)));
