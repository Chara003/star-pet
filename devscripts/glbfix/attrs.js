const fs = require('fs');
const buf = fs.readFileSync(process.argv[2] || './model.glb');
const l0 = buf.readUInt32LE(12);
const g = JSON.parse(buf.slice(20, 20 + l0).toString('utf8'));
console.log('=== 每个 primitive 的属性（NORMAL 有没有）===');
g.meshes.forEach((m,i)=>{
  const nm = (g.nodes.find(n=>n.mesh===i)||{}).name;
  m.primitives.forEach((p,pi)=>{
    const keys = Object.keys(p.attributes).join(',');
    console.log('  mesh'+String(i).padStart(2)+' '+(nm||'?').padEnd(22)+' prim'+pi
      +' ['+keys+']'+(p.attributes.NORMAL===undefined?'   ★★ 没有 NORMAL':''));
  });
});
console.log('\n=== 有没有 EXTENSIONS / KHR_materials_unlit 之类 ===');
console.log('  extensionsUsed =', JSON.stringify(g.extensionsUsed||[]));
console.log('  extensionsRequired =', JSON.stringify(g.extensionsRequired||[]));
console.log('\n=== 模型顶层有没有做旋转/缩放（scene root 的 TRS）===');
g.scenes[0].nodes.forEach(i=>{
  const n=g.nodes[i];
  const r=n.rotation||[0,0,0,1];
  const ang=(2*Math.acos(Math.min(1,Math.abs(r[3])))*180/Math.PI).toFixed(1);
  const s=(n.scale||[1,1,1]);
  const t=(n.translation||[0,0,0]);
  if (ang!=='0.0' || JSON.stringify(s)!=='[1,1,1]' || JSON.stringify(t)!=='[0,0,0]')
    console.log('  node'+i+' '+n.name+' R='+ang+'° S='+JSON.stringify(s)+' T='+JSON.stringify(t));
});
