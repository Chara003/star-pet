const fs=require('fs');
const b=fs.readFileSync(process.argv[2] || './model.glb');
const l0=b.readUInt32LE(12);
const g=JSON.parse(b.slice(20,20+l0).toString('utf8'));
console.log('=== GLB 里有没有"描边"相关声明？ ===');
console.log('extensionsUsed     = '+JSON.stringify(g.extensionsUsed||[]));
console.log('extensionsRequired = '+JSON.stringify(g.extensionsRequired||[]));
let hits=[];
const kw=/outline|edge|stroke|border|rim|silhouette/i;
function scan(obj, path){
  if(!obj||typeof obj!=='object') return;
  for(const k in obj){
    if(kw.test(k)) hits.push(path+'.'+k);
    scan(obj[k], path+'.'+k);
  }
}
scan(g,'gltf');
console.log('含 outline/edge/stroke 等关键词的字段: '+(hits.length?JSON.stringify(hits):'（一个都没有）'));
console.log('\n=== 材质的全部字段（看能表达什么）===');
const keys=new Set();
g.materials.forEach(m=>Object.keys(m).forEach(k=>keys.add(k)));
console.log('  所有材质用到的字段: '+JSON.stringify([...keys]));
console.log('\n=== 有没有任何"按材质/区域区分颜色"的线索 ===');
g.materials.forEach((m,i)=>{
  const p=m.pbrMetallicRoughness||{};
  const bc=p.baseColorFactor||[1,1,1,1];
  console.log('  mat'+String(i).padStart(2)+' '+String(m.name).padEnd(28)
    +' baseColor='+JSON.stringify(bc.map(x=>+x.toFixed(2)))
    +(m.emissiveFactor?(' emissive='+JSON.stringify(m.emissiveFactor)):''));
});
console.log('\n★ 结论：glTF 规范里【根本没有描边这个概念】——');
console.log('  没有 edgeColor / edgeSize / edgeFlag，也没有任何扩展。');
console.log('  描边是我们自己加上去的，颜色只能由我们定（现在是写死的黑）。');
console.log('  （对比：PMX 有 per-material 的 edgeColor RGBA + edgeSize + flag，');
console.log('    所以 MMD 模型确实能"不同区域不同描边颜色"。）');
