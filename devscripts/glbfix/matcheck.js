const fs = require('fs');
const buf = fs.readFileSync(process.argv[2] || './model.glb');
const l0 = buf.readUInt32LE(12);
const g = JSON.parse(buf.slice(20, 20 + l0).toString('utf8'));
console.log('=== 使用 matrix 字段（而非 TRS）的节点 ===');
let c=0;
g.nodes.forEach((n,i)=>{ if (n.matrix){ c++; console.log('  node'+i+' '+n.name+' matrix='+JSON.stringify(n.matrix.map(x=>+x.toFixed(4)))); } });
if(!c) console.log('  （没有）');
console.log('\n=== scene roots 的完整定义（看有没有 matrix） ===');
g.scenes[0].nodes.forEach(i=>{
  const n=g.nodes[i];
  console.log('  node'+String(i).padStart(3)+' '+String(n.name).padEnd(24)+' keys='+Object.keys(n).join(','));
});
console.log('\n=== 节点 0 / 1 / 8 的组织结构 ===');
[0,1,8,7].forEach(i=>{
  const n=g.nodes[i];
  console.log('  node'+i+' '+n.name+' keys='+Object.keys(n).join(',')
    +' children='+JSON.stringify(n.children||[]));
});
console.log('\n=== CH0335_Mesh(1) 的所有子节点 ===');
(g.nodes[1].children||[]).forEach(c=>{
  const n=g.nodes[c];
  console.log('  child node'+String(c).padStart(3)+' '+String(n.name).padEnd(26)
    +' mesh='+(n.mesh===undefined?'-':n.mesh)+' children='+(n.children||[]).length
    +' keys='+Object.keys(n).join(','));
});
