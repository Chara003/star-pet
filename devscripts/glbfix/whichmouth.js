const fs=require('fs');
const b=fs.readFileSync(process.argv[2] || './model.glb');
const l0=b.readUInt32LE(12);
const g=JSON.parse(b.slice(20,20+l0).toString('utf8'));
let off=20+l0,bin=null;
while(off<b.length){const l=b.readUInt32LE(off),t=b.readUInt32LE(off+4);
 if(t===0x004E4942)bin=b.slice(off+8,off+8+l);off+=8+l;}
const CT={5120:Int8Array,5121:Uint8Array,5122:Int16Array,5123:Uint16Array,5125:Uint32Array,5126:Float32Array};
const NC={SCALAR:1,VEC2:2,VEC3:3,VEC4:4,MAT4:16};
function acc(i){const a=g.accessors[i],bv=g.bufferViews[a.bufferView],TA=CT[a.componentType],n=NC[a.type];
 const st=(bv.byteOffset||0)+(a.byteOffset||0);const out=new Float32Array(a.count*n);
 for(let k=0;k<a.count*n;k++) out[k]=new TA(bin.buffer,bin.byteOffset+st+k*TA.BYTES_PER_ELEMENT,1)[0];
 return out;}
/* 嘴贴图不透明像素数 */
const zlib=require('zlib');
function opx(png){let p=8,w=0,h=0,ct=0,bd=0,idat=[];
 while(p<png.length){const len=png.readUInt32BE(p),ty=png.toString('ascii',p+4,p+8);
  if(ty==='IHDR'){w=png.readUInt32BE(p+8);h=png.readUInt32BE(p+12);bd=png[p+16];ct=png[p+17];}
  else if(ty==='IDAT')idat.push(png.slice(p+8,p+8+len));else if(ty==='IEND')break;p+=12+len;}
 if(bd!==8)return -1;const ch=ct===6?4:ct===2?3:0;if(!ch)return -1;
 let raw;try{raw=zlib.inflateSync(Buffer.concat(idat));}catch(e){return -1;}
 const stride=w*ch,out=Buffer.alloc(h*stride);let prev=Buffer.alloc(stride);
 for(let y=0;y<h;y++){const ft=raw[y*(stride+1)];const line=raw.slice(y*(stride+1)+1,y*(stride+1)+1+stride);
  const cur=Buffer.alloc(stride);
  for(let i=0;i<stride;i++){const a=i>=ch?cur[i-ch]:0,b2=prev[i],c=i>=ch?prev[i-ch]:0,x=line[i];let v;
   switch(ft){case 0:v=x;break;case 1:v=x+a;break;case 2:v=x+b2;break;case 3:v=x+((a+b2)>>1);break;
    case 4:{const pp=a+b2-c,pa=Math.abs(pp-a),pb=Math.abs(pp-b2),pc=Math.abs(pp-c);v=x+(pa<=pb&&pa<=pc?a:(pb<=pc?b2:c));break;}default:v=x;}
   cur[i]=v&255;}
  cur.copy(out,y*stride);prev=cur;}
 let n=0;for(let y=0;y<h;y++)for(let x=0;x<w;x++)if(out[y*stride+x*ch+(ch-1)]>128)n++;return n;}
const size={};
g.materials.forEach(m=>{const mt=/Mouth_(\d+)$/.exec(m.name||'');if(!mt)return;
 const bt=(m.pbrMetallicRoughness||{}).baseColorTexture;if(!bt)return;
 const src=g.images[g.textures[bt.index].source],bv=g.bufferViews[src.bufferView];
 size['Mouth_'+mt[1]]=opx(bin.slice(bv.byteOffset||0,(bv.byteOffset||0)+bv.byteLength));});
const VIS=s=>size['Mouth_'+s]||0;
const cands=['Cafe_Reaction','Cafe_Idle','Cafe_Walk','Formation_Idle','Exs','Vital_Panic',
  'Vital_Death','Victory_Start','Normal_Callsign','Public01_Random','Normal_Attack_Ing','Move_End_Normal'];
console.log('动画'.padEnd(26)+' 显示的嘴      贴图像素  可见?');
cands.forEach(nm=>{
  const a=g.animations.find(x=>x.name===nm); if(!a) return;
  const ch=a.channels.filter(c=>c.target.path==='weights');
  const shown=[]; let varying=false;
  ch.forEach(c=>{
    const v=Array.from(acc(a.samplers[c.sampler].output));
    const mn=Math.min(...v),mx=Math.max(...v);
    if(mx-mn>0.02) varying=true;
    if(mn<0.5) shown.push(g.nodes[c.target.node].name.replace('Mouth_',''));
  });
  const best = shown.length? Math.max(...shown.map(VIS)) : 0;
  console.log(nm.padEnd(26)+' '+(shown.join(',').padEnd(14))
    +String(best).padStart(6)+'px  '+(best>=600?'★明显':(best>=200?'偏细':'几乎看不见'))
    +(varying?'  (会切换)':''));
});
