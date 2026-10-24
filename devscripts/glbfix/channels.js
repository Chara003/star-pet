const fs = require('fs');
const buf = fs.readFileSync(process.argv[2] || './model.glb');
const l0 = buf.readUInt32LE(12);
const g = JSON.parse(buf.slice(20, 20 + l0).toString('utf8'));
console.log('=== 每个动作：按 path 分类的通道数，以及"只覆盖一种 path"的节点 ===');
g.animations.forEach((a,idx)=>{
  const byNode = {};   // node -> Set(path)
  let cnt = {translation:0, rotation:0, scale:0, weights:0};
  a.channels.forEach(c=>{
    const nm = g.nodes[c.target.node].name;
    if (!byNode[nm]) byNode[nm] = new Set();
    byNode[nm].add(c.target.path);
    cnt[c.target.path] = (cnt[c.target.path]||0)+1;
  });
  const rotOnly = [], posOnly = [], both = [];
  for (const nm in byNode){
    const s = byNode[nm];
    if (s.has('rotation') && !s.has('translation')) rotOnly.push(nm);
    else if (s.has('translation') && !s.has('rotation')) posOnly.push(nm);
    else if (s.has('translation') && s.has('rotation')) both.push(nm);
  }
  const hairRotOnly = rotOnly.filter(n=>/hair|skirt|Strap/.test(n));
  console.log(String(idx).padStart(2)+' '+a.name.padEnd(24)
    + ' T='+String(cnt.translation||0).padStart(3)+' R='+String(cnt.rotation||0).padStart(3)+' S='+String(cnt.scale||0).padStart(3)
    + ' | 只有R的节点='+String(rotOnly.length).padStart(3)+'(hair/skirt='+hairRotOnly.length+')'
    + ' 只有T的节点='+posOnly.length+' | 全量节点='+(a.channels.length));
});
