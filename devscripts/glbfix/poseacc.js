const fs = require('fs');
const buf = fs.readFileSync(process.argv[2] || './model.glb');
const l0 = buf.readUInt32LE(12);
const g = JSON.parse(buf.slice(20, 20 + l0).toString('utf8'));
console.log('=== 各 mesh 的 POSITION accessor min/max（文件里存的，就是"建模空间"）===');
g.meshes.forEach((m,i)=>{
  m.primitives.forEach((p,pi)=>{
    const a = g.accessors[p.attributes.POSITION];
    if (!a.min || !a.max) { console.log('  mesh'+i+' prim'+pi+' 无 min/max'); return; }
    const size = [0,1,2].map(k=>+(a.max[k]-a.min[k]).toFixed(3));
    const name = g.nodes.find(n=>n.mesh===i);
    console.log('  mesh'+String(i).padStart(2)+' prim'+pi+' '+(name?name.name:'?').padEnd(22)
      + ' min='+JSON.stringify(a.min.map(x=>+x.toFixed(3)))
      + ' max='+JSON.stringify(a.max.map(x=>+x.toFixed(3)))
      + '  size='+JSON.stringify(size));
  });
});
console.log('\n=== 骨骼 joint 的 node 默认 TRS 里，旋转非 identity 的 ===');
(g.skins[0].joints||[]).forEach((ji,k)=>{
  const n = g.nodes[ji];
  if (!n.rotation) return;
  const q = n.rotation, ang = 2*Math.acos(Math.min(1,Math.abs(q[3])))*180/Math.PI;
  if (ang < 0.5) return;
  if (k > 12 && k < 118) return; // 只显示前 12 和最后几个
  console.log('  joint'+String(k).padStart(3)+' '+String(n.name).padEnd(24)
    + ' angle='+ang.toFixed(1)+'°  q='+JSON.stringify(q.map(x=>+x.toFixed(4))));
});
console.log('\n=== 关键：哪些 node 有"立起来"级别的旋转（>45°） ===');
g.nodes.forEach((n,i)=>{
  if (!n.rotation) return;
  const q = n.rotation, ang = 2*Math.acos(Math.min(1,Math.abs(q[3])))*180/Math.PI;
  if (ang < 45) return;
  const isJoint = g.skins.some(s=>s.joints.includes(i));
  console.log('  node'+String(i).padStart(3)+' '+String(n.name).padEnd(26)
    + ' angle='+ang.toFixed(1)+'°  '+(isJoint?'[joint]':'[非joint]'));
});
