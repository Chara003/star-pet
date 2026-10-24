const fs = require('fs');
const path = process.argv[2] || './model.glb';
const buf = fs.readFileSync(path);
if (buf.readUInt32LE(0) !== 0x46546C67) throw new Error('not glb');
let off = 12, json = null, bin = null;
while (off < buf.length) {
  const len = buf.readUInt32LE(off), type = buf.readUInt32LE(off + 4);
  const data = buf.slice(off + 8, off + 8 + len);
  if (type === 0x4E4F534A) json = JSON.parse(data.toString('utf8'));
  else if (type === 0x004E4942) bin = data;
  off += 8 + len;
}
const g = json;
console.log('asset', JSON.stringify(g.asset).slice(0,200));
console.log('nodes', g.nodes.length, 'meshes', g.meshes.length, 'skins', (g.skins||[]).length,
  'animations', (g.animations||[]).length, 'materials', (g.materials||[]).length);
// names + dedup check
const sanitize = n => (n||'').replace(/\s/g,'_').replace(/[\.\[\]]/g,'');
const names = g.nodes.map((n,i)=>({i,n:n.name||''}));
const seen = new Map();
const dup = [];
for (const {i,n} of names) {
  const s = sanitize(n);
  if (seen.has(s)) dup.push([s, seen.get(s), i]);
  else seen.set(s, i);
}
console.log('--- duplicate sanitized names:', dup.length);
dup.slice(0,30).forEach(d=>console.log('   ', d[0], 'idx', d[1], d[2]));
// extras
console.log('--- nodes with extras:');
g.nodes.forEach((n,i)=>{ if(n.extras) console.log('   ', i, n.name, JSON.stringify(n.extras).slice(0,150)); });
// skins
(g.skins||[]).forEach((s,i)=>{
  const roots = (s.joints||[]).slice(0,3).map(j=>g.nodes[j].name);
  console.log('skin['+i+'] joints='+s.joints.length+' first='+roots.join(',')+' skeletonNode='+s.skeleton);
});
