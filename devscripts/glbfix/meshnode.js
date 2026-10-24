const fs = require('fs');
const buf = fs.readFileSync(process.argv[2] || './model.glb');
const l0 = buf.readUInt32LE(12);
const g = JSON.parse(buf.slice(20, 20 + l0).toString('utf8'));
let off = 20 + l0, bin = null;
while (off < buf.length) { const l = buf.readUInt32LE(off), t = buf.readUInt32LE(off + 4);
  if (t === 0x004E4942) bin = buf.slice(off + 8, off + 8 + l); off += 8 + l; }
const N = g.nodes.length;
const parent = new Array(N).fill(-1);
g.nodes.forEach((n,i)=>(n.children||[]).forEach(c=>parent[c]=i));
console.log('=== 所有"带 mesh 的节点"的 TRS + 是否有 parent ===');
g.nodes.forEach((n,i)=>{
  if (n.mesh === undefined) return;
  const t = n.translation || [0,0,0], r = n.rotation || [0,0,0,1], s = n.scale || [1,1,1];
  const deg = (2*Math.acos(Math.min(1,Math.abs(r[3])))*180/Math.PI).toFixed(1);
  console.log('  node'+String(i).padStart(3)+' '+String(n.name).padEnd(24)
    + ' mesh='+String(n.mesh).padStart(2)+' skin='+(n.skin===undefined?'-':n.skin)
    + ' | parent='+(parent[i]>=0?g.nodes[parent[i]].name:'(scene root)')
    + ' | T='+JSON.stringify(t.map(x=>+x.toFixed(3)))
    + ' R='+JSON.stringify(r.map(x=>+x.toFixed(4)))+'('+deg+'°)'
    + ' S='+JSON.stringify(s.map(x=>+x.toFixed(3))));
});
console.log('\n=== 材质 alphaMode / baseColorFactor（看是否"应该不可见"） ===');
(g.materials||[]).forEach((m,i)=>{
  const p = m.pbrMetallicRoughness || {};
  const bc = p.baseColorFactor || [1,1,1,1];
  console.log('  mat'+String(i).padStart(2)+' '+String(m.name).padEnd(26)
    + ' alphaMode='+(m.alphaMode||'OPAQUE').padEnd(7)
    + ' doubleSided='+!!m.doubleSided
    + ' baseColor='+JSON.stringify(bc.map(x=>+x.toFixed(3))));
});
console.log('\n=== morph mesh 的节点是否被动画覆盖（track 里出现次数） ===');
g.animations.forEach((a,idx)=>{
  if (idx>1) return;
  const hit = a.channels.filter(c=>/^Mouth_/.test(g.nodes[c.target.node].name||'')).length;
  const skip = a.channels.filter(c=>['skip'].includes('')).length;
  const w = a.channels.filter(c=>c.target.path==='weights').length;
  console.log('  '+a.name+': Mouth_* 通道='+hit+'  weights 通道='+w);
});
console.log('\n=== weights 通道的目标节点（谁在驱动 morph） ===');
g.animations.slice(0,3).forEach(a=>{
  const set = new Set(a.channels.filter(c=>c.target.path==='weights').map(c=>g.nodes[c.target.node].name));
  console.log('  '+a.name+': '+(set.size?[...set].join(','):'(无)'));
});
