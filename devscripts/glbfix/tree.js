const fs = require('fs');
const buf = fs.readFileSync(process.argv[2] || './model.glb');
const l0 = buf.readUInt32LE(12);
const g = JSON.parse(buf.slice(20, 20 + l0).toString('utf8'));
const N = g.nodes.length;
const parent = new Array(N).fill(-1);
g.nodes.forEach((n,i)=>(n.children||[]).forEach(c=>parent[c]=i));
console.log('=== scene roots ===');
g.scenes[0].nodes.forEach(i=>{
  const n = g.nodes[i];
  console.log('  node'+i, n.name, 'mesh='+n.mesh, 'skin='+n.skin, 'children='+(n.children||[]).length);
});
console.log('=== 树的顶层骨架 ===');
function show(i, d, maxd){
  const n = g.nodes[i];
  console.log('  '.repeat(d) + '[' + i + '] ' + n.name + (n.mesh!==undefined?('  mesh='+n.mesh):'') + (n.skin!==undefined?(' skin='+n.skin):''));
  if (d >= maxd) return;
  (n.children||[]).forEach(c=>show(c, d+1, maxd));
}
show(195, 0, 2);
console.log('=== 道具骨骼的父链 ===');
['bone_case','bone_brush','bone_Railgunup_02','bone_prop_root','bone_prop_pillar','Bip001_Weapon','bone_Strap_01'].forEach(nm=>{
  const i = g.nodes.findIndex(n=>n.name===nm);
  if (i<0) { console.log('  ' + nm + ' 不存在'); return; }
  let c=[], k=i;
  while(k>=0){ c.push(g.nodes[k].name); k=parent[k]; if(c.length>15)break; }
  console.log('  ' + nm + ' -> ' + c.join(' <- '));
});
