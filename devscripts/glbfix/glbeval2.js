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
const CT = {5120: Int8Array,5121: Uint8Array,5122: Int16Array,5123: Uint16Array,5125: Uint32Array,5126: Float32Array};
const NC = {SCALAR:1,VEC2:2,VEC3:3,VEC4:4,MAT4:16};
function acc(i){
  const a = g.accessors[i]; const bv = g.bufferViews[a.bufferView];
  const TA = CT[a.componentType], n = NC[a.type];
  const start = (bv.byteOffset||0) + (a.byteOffset||0);
  return new TA(bin.buffer.slice(bin.byteOffset+start, bin.byteOffset+start+a.count*n*TA.BYTES_PER_ELEMENT));
}
const anim = g.animations.find(a=>a.name==='Cafe_Idle');
const wanted = ['bone_hair_BL_01','Bip001 Head','Bip001 Neck','Bip001 Spine','bone_root','Bip001 Pelvis'];
anim.channels.forEach(ch=>{
  const nm = g.nodes[ch.target.node].name;
  if (!wanted.includes(nm)) return;
  const smp = anim.samplers[ch.sampler];
  const out = acc(smp.output), inp = acc(smp.input);
  const n = NC[g.accessors[smp.output].type];
  const first = Array.from(out.slice(0,n)).map(x=>+x.toFixed(5));
  const mid = Array.from(out.slice(Math.floor(out.length/n/2)*n, Math.floor(out.length/n/2)*n+n)).map(x=>+x.toFixed(5));
  console.log(nm, ch.target.path, 'keys='+(out.length/n), 't0=', JSON.stringify(first), 'mid=', JSON.stringify(mid));
});
// count how many translation channels are all zeros
let zeroT=0, nonZeroT=0, ex='';
anim.channels.forEach(ch=>{
  if (ch.target.path!=='translation') return;
  const out = acc(anim.samplers[ch.sampler].output);
  let mx=0; for(const v of out) mx=Math.max(mx,Math.abs(v));
  if (mx < 1e-5) zeroT++; else { nonZeroT++; if(!ex) ex = g.nodes[ch.target.node].name+' max='+mx.toFixed(4); }
});
console.log('translation channels: allZero='+zeroT+' nonZero='+nonZeroT, ex);
// rotation channels: all identity?
let idR=0, nonIdR=0;
anim.channels.forEach(ch=>{
  if (ch.target.path!=='rotation') return;
  const out = acc(anim.samplers[ch.sampler].output);
  let dev=0;
  for(let i=0;i<out.length;i+=4){ dev=Math.max(dev, Math.abs(out[i])+Math.abs(out[i+1])+Math.abs(out[i+2])+Math.abs(1-Math.abs(out[i+3]))); }
  if (dev<1e-4) idR++; else nonIdR++;
});
console.log('rotation channels: identity='+idR+' moving='+nonIdR);
