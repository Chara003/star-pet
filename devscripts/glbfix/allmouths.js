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
/* 嘴贴图的"可视度"：用贴图里不透明像素数当权重（越大越明显） */
const zlib=require('zlib');
function opaquePixels(png){
  let p=8,w=0,h=0,ct=0,bd=0,idat=[];
  while(p<png.length){const len=png.readUInt32BE(p),ty=png.toString('ascii',p+4,p+8);
    if(ty==='IHDR'){w=png.readUInt32BE(p+8);h=png.readUInt32BE(p+12);bd=png[p+16];ct=png[p+17];}
    else if(ty==='IDAT')idat.push(png.slice(p+8,p+8+len));
    else if(ty==='IEND')break;
    p+=12+len;}
  if(bd!==8) return -1;
  const ch = ct===6?4:ct===2?3:0; if(!ch) return -1;
  let raw; try{ raw=zlib.inflateSync(Buffer.concat(idat)); }catch(e){ return -1; }
  const stride=w*ch, out=Buffer.alloc(h*stride); let prev=Buffer.alloc(stride);
  for(let y=0;y<h;y++){const ft=raw[y*(stride+1)];const line=raw.slice(y*(stride+1)+1,y*(stride+1)+1+stride);
    const cur=Buffer.alloc(stride);
    for(let i=0;i<stride;i++){const a=i>=ch?cur[i-ch]:0,b2=prev[i],c=i>=ch?prev[i-ch]:0,x=line[i];
      let v;switch(ft){case 0:v=x;break;case 1:v=x+a;break;case 2:v=x+b2;break;case 3:v=x+((a+b2)>>1);break;
        case 4:{const pp=a+b2-c,pa=Math.abs(pp-a),pb=Math.abs(pp-b2),pc=Math.abs(pp-c);v=x+(pa<=pb&&pa<=pc?a:(pb<=pc?b2:c));break;}default:v=x;}
      cur[i]=v&255;}
    cur.copy(out,y*stride);prev=cur;}
  let n=0; for(let y=0;y<h;y++)for(let x=0;x<w;x++) if(out[y*stride+x*ch+(ch-1)]>128) n++;
  return n;
}
/* 各嘴贴图的不透明像素数 */
const size={};
g.materials.forEach(m=>{
  const mt=/Mouth_(\d+)$/.exec(m.name||'');
  if(!mt) return;
  const bt=(m.pbrMetallicRoughness||{}).baseColorTexture; if(!bt) return;
  const img=g.textures[bt.index].source, src=g.images[img], bv=g.bufferViews[src.bufferView];
  const px=opaquePixels(bin.slice(bv.byteOffset||0,(bv.byteOffset||0)+bv.byteLength));
  size['Mouth_'+mt[1]]=px;
});
console.log('=== 各嘴贴图的"明显程度"（不透明像素数，越大越明显）===');
Object.entries(size).sort((a,c)=>c[1]-a[1]).forEach(([k,v])=>{
  console.log('  '+k.padEnd(12)+String(v).padStart(6)+' px  '+'#'.repeat(Math.min(40,Math.round(v/3))));
});
/* 每个动画显示哪个嘴 */
console.log('\n=== 每个动画"稳定显示"的嘴（该嘴权重=0，其余=1）===');
const out=[];
g.animations.forEach(a=>{
  const chans=a.channels.filter(c=>c.target.path==='weights');
  let shown=[], varying=[];
  chans.forEach(c=>{
    const nm=g.nodes[c.target.node].name;
    const smp=a.samplers[c.sampler];
    const v=Array.from(acc(smp.output));
    const mn=Math.min(...v), mx=Math.max(...v);
    if(mx-mn>0.02) varying.push(nm.replace('Mouth_',''));
    if(mn<0.5) shown.push(nm.replace('Mouth_',''));
  });
  out.push({n:a.name, shown, varying, ch:chans.length});
});
out.forEach(o=>{
  const px = o.shown.length? o.shown.map(s=>size['Mouth_'+s]||0).reduce((a,c)=>Math.max(a,c),0):0;
  console.log('  '+o.n.padEnd(24)+' weights='+String(o.ch).padStart(2)
    +'  恒定可见嘴='+(o.shown.join(',')||'-').padEnd(10)
    +'  会动/切换的嘴='+(o.varying.length?varying0(o.varying):'-')
    +'  最明显贴图='+px+'px');
});
function varying0(v){ return v.length>4? (v.slice(0,4).join(',')+'…') : v.join(','); }
