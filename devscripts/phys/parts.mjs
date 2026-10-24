import fs from 'fs'; import { MMDParser } from './mmdparser.mjs';
const b=fs.readFileSync(new URL('../../in/kei.pmx',import.meta.url));
const d=new MMDParser.Parser().parsePmx(b.buffer.slice(b.byteOffset,b.byteOffset+b.byteLength),true);
const R=d.rigidBodies,B=d.bones;
const tp={}; for(const r of R){const x=tp[r.boneIndex]; tp[r.boneIndex]=x===undefined?r.type:Math.max(r.type,x);}
const groups={'裙子':/スカート|skirt/, '外套':/上着|jacket|coat|ジャケット/, '袖子':/袖|sleeve/, '领带':/ネクタイ|tie/, '头发':/髪|hair/, '胸':/胸|bust/, '尾巴':/しっぽ|尻尾|尾/};
for(const [g,re] of Object.entries(groups)){
  let bones=0, withBody=0, physBones=0, bodies=0;
  for(let i=0;i<B.length;i++){ if(!re.test(B[i].name)) continue; bones++; if(tp[i]!==undefined){withBody++; if(tp[i]!==0) physBones++;} }
  for(const r of R){ const n=r.boneIndex>=0?B[r.boneIndex].name:''; if(re.test(n)) bodies++; }
  console.log(`${g}: 骨骼${bones} 有刚体${withBody}(其中动态${physBones}) 刚体数${bodies}`);
}
