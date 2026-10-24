const fs = require('fs');
const buf = fs.readFileSync(process.argv[2] || './model.glb');
const l0 = buf.readUInt32LE(12);
const g = JSON.parse(buf.slice(20, 20 + l0).toString('utf8'));
const hairNodes = g.nodes.map((n,i)=>[n.name,i]).filter(([n])=>/hair|skirt/.test(n||''));
console.log('hair/skirt 节点数 = ' + hairNodes.length);
let withT=0, withR=0, onlyR=0, onlyT=0, none=0;
const detail = [];
hairNodes.forEach(([nm,i])=>{
  let hasT=false, hasR=false;
  g.animations.forEach(a=>a.channels.forEach(c=>{
    if (c.target.node!==i) return;
    if (c.target.path==='translation') hasT=true;
    if (c.target.path==='rotation') hasR=true;
  }));
  if (hasT) withT++;
  if (hasR) withR++;
  if (hasR && !hasT) onlyR++;
  if (hasT && !hasR) onlyT++;
  if (!hasT && !hasR) none++;
  if (detail.length<8) detail.push(nm+' T='+hasT+' R='+hasR);
});
console.log('  有任何动作带 translation 的: ' + withT);
console.log('  有任何动作带 rotation  的: ' + withR);
console.log('  只有 rotation（没有 translation）: ' + onlyR + '   ★★ 这些骨骼的 position 不会被任何动画写');
console.log('  只有 translation: ' + onlyT + '   都没有: ' + none);
console.log('  样例: ' + detail.join(' | '));
// 单看 Cafe_Idle
const idle = g.animations.find(a=>a.name==='Cafe_Idle');
const setT = new Set(), setR = new Set();
idle.channels.forEach(c=>{
  const nm = g.nodes[c.target.node].name;
  if (!/hair|skirt/.test(nm)) return;
  if (c.target.path==='translation') setT.add(nm);
  if (c.target.path==='rotation') setR.add(nm);
});
console.log('\nCafe_Idle: hair/skirt 有 R 的=' + setR.size + '，有 T 的=' + setT.size);
console.log('  → 结论：' + (setT.size===0 ? '★★ 没有一条 translation 轨道 → 所有 hair/skirt 的 position 都靠"复位值"' : '有 translation'));
