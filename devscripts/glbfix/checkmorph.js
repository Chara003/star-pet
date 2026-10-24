const fs=require('fs');
const buf=fs.readFileSync(process.argv[2] || './model.glb');
const l0=buf.readUInt32LE(12);
const g=JSON.parse(buf.slice(20,20+l0).toString('utf8'));
console.log('=== 所有"带 morph target"的 mesh（= 能当表情用的）===');
let n=0;
g.meshes.forEach((m,i)=>{
  const has = m.primitives.some(p=>p.targets && p.targets.length);
  if(!has) return;
  n++;
  const node = g.nodes.find(x=>x.mesh===i);
  console.log('  '+String(m.name).padEnd(14)+' 节点='+(node?node.name:'?')
    +'  贴图='+(g.materials[m.primitives[0].material].pbrMetallicRoughness.baseColorTexture
        ? g.materials[m.primitives[0].material].name : '无')
    +'  归到哪类='+(/Mouth/i.test(m.name)?'【嘴】':(/Eye/i.test(m.name)?'【眼】':(/Brow/i.test(m.name)?'【眉】':'【其它】'))));
});
console.log('\n合计 '+n+' 个可 morph 的 mesh');
const cats={嘴:0,眼:0,眉:0,其它:0};
g.meshes.forEach(m=>{ if(!m.primitives.some(p=>p.targets&&p.targets.length)) return;
  if(/Mouth/i.test(m.name))cats.嘴++; else if(/Eye/i.test(m.name))cats.眼++;
  else if(/Brow/i.test(m.name))cats.眉++; else cats.其它++; });
console.log('分类统计: '+JSON.stringify(cats));
console.log('\n★ 结论：' + (cats.嘴===n ? '全部 ' + n + ' 个 morph 都是【嘴型】—— 没有脸/眼/眉的表情' : '存在非嘴型的 morph'));
