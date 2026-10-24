const fs=require('fs'), zlib=require('zlib');
const P=process.argv[2];
const b=fs.readFileSync(P);
if(b.toString('ascii',0,4)!=='glTF'){ console.log('不是 GLB'); process.exit(1); }
const l0=b.readUInt32LE(12);
const g=JSON.parse(b.slice(20,20+l0).toString('utf8'));
console.log('文件: '+P+'  ('+(b.length/1048576).toFixed(2)+' MB)');
console.log('generator: '+((g.asset||{}).generator||'?'));
console.log('nodes='+g.nodes.length+' meshes='+g.meshes.length+' skins='+(g.skins||[]).length
  +' animations='+(g.animations||[]).length+' materials='+(g.materials||[]).length);
console.log('\n=== 有 morph target 的 mesh（= 能当表情用的）===');
let n=0;
g.meshes.forEach((m,i)=>{
  const has=m.primitives.some(p=>p.targets&&p.targets.length);
  if(!has) return;
  n++;
  const node=g.nodes.find(x=>x.mesh===i);
  const tcount=m.primitives.reduce((s,p)=>s+((p.targets||[]).length),0);
  let kind='?';
  if(/mouth/i.test(m.name||'')) kind='【嘴】';
  else if(/eye|mayu|brow|hitomi/i.test(m.name||'')) kind='【眼/眉】';
  console.log('  '+String(m.name).padEnd(22)+' 节点='+String(node?node.name:'?').padEnd(22)
    +' targets='+tcount+' '+kind);
});
console.log('\n合计 '+n+' 个 mesh 带 morph target');
const cats={嘴:0,眼眉:0,其它:0};
g.meshes.forEach(m=>{ if(!m.primitives.some(p=>p.targets&&p.targets.length))return;
  if(/mouth/i.test(m.name||''))cats.嘴++;
  else if(/eye|mayu|brow|hitomi/i.test(m.name||''))cats.眼眉++;
  else cats.其它++; });
console.log('分类: '+JSON.stringify(cats));
console.log('\n=== 材质 alphaMode（MASK 的通常是靠贴图镂空的眼/嘴）===');
const am={};
g.materials.forEach(m=>{ const k=(m.alphaMode||'OPAQUE')+'|'+(m.alphaCutoff===undefined?'-':m.alphaCutoff);
  am[k]=(am[k]||0)+1; });
console.log('  '+JSON.stringify(am));
console.log('\n=== morph mesh 的节点是否有初始 weights ===');
let withW=0, without=0;
g.nodes.forEach(nd=>{ if(nd.mesh===undefined) return;
  const hasM=g.meshes[nd.mesh].primitives.some(p=>p.targets&&p.targets.length);
  if(!hasM) return;
  if(nd.weights && nd.weights.length) { withW++; }
  else without++;
});
console.log('  有初始 weights 的节点='+withW+'  没有的='+without);
console.log('\n=== 动画是否覆盖这些 morph 节点的 weights ===');
const morphNodes=new Set();
g.nodes.forEach((nd,i)=>{ if(nd.mesh===undefined)return;
  if(g.meshes[nd.mesh].primitives.some(p=>p.targets&&p.targets.length)) morphNodes.add(i); });
(g.animations||[]).forEach((a,ai)=>{
  const w=new Set();
  a.channels.forEach(c=>{ if(c.target.path==='weights') w.add(c.target.node); });
  const miss=[...morphNodes].filter(x=>!w.has(x));
  console.log('  '+String(a.name).padEnd(26)+' weights通道='+String(w.size).padStart(3)
    +'  未覆盖的 morph 节点='+miss.length+(miss.length?('  ★  '+miss.map(i=>g.nodes[i].name).join(',')):''));
});
