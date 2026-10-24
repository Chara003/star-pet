const fs=require('fs');
const buf=fs.readFileSync(process.argv[2] || './model.glb');
const l0=buf.readUInt32LE(12);
const g=JSON.parse(buf.slice(20,20+l0).toString('utf8'));
let off=20+l0,bin=null;
while(off<buf.length){const l=buf.readUInt32LE(off),t=buf.readUInt32LE(off+4);
 if(t===0x004E4942)bin=buf.slice(off+8,off+8+l);off+=8+l;}
function pngAlpha(buf){
  // 极简 PNG 解码：读 IHDR 判 colorType，再解 IDAT（zlib inflate）
  const zlib=require('zlib');
  let p=8, w=0,h=0,ct=0,bd=0, idat=[];
  while(p<buf.length){
    const len=buf.readUInt32BE(p), type=buf.toString('ascii',p+4,p+8);
    if(type==='IHDR'){ w=buf.readUInt32BE(p+8); h=buf.readUInt32BE(p+12); bd=buf[p+16]; ct=buf[p+17]; }
    else if(type==='IDAT') idat.push(buf.slice(p+8,p+8+len));
    else if(type==='IEND') break;
    p+=12+len;
  }
  if(bd!==8) return {w,h,ct,note:'bitDepth='+bd+' 未处理'};
  const ch = ct===6?4 : ct===2?3 : ct===4?2 : ct===0?1 : 0;
  if(!ch) return {w,h,ct,note:'colorType='+ct+' 无 alpha'};
  const raw=zlib.inflateSync(Buffer.concat(idat));
  const stride=w*ch;
  const out=Buffer.alloc(h*stride);
  let prev=Buffer.alloc(stride);
  for(let y=0;y<h;y++){
    const ft=raw[y*(stride+1)];
    const line=raw.slice(y*(stride+1)+1, y*(stride+1)+1+stride);
    const cur=Buffer.alloc(stride);
    for(let i=0;i<stride;i++){
      const a=i>=ch?cur[i-ch]:0, b=prev[i], c=i>=ch?prev[i-ch]:0, x=line[i];
      let v;
      switch(ft){ case 0:v=x;break; case 1:v=x+a;break; case 2:v=x+b;break;
        case 3:v=x+((a+b)>>1);break;
        case 4:{const pp=a+b-c,pa=Math.abs(pp-a),pb=Math.abs(pp-b),pc=Math.abs(pp-c);
          v=x+(pa<=pb&&pa<=pc?a:(pb<=pc?b:c));break;}
        default:v=x;}
      cur[i]=v&255;
    }
    cur.copy(out,y*stride); prev=cur;
  }
  const alphas=new Map();
  for(let y=0;y<h;y++)for(let x=0;x<w;x++){
    const a=out[y*stride+x*ch+(ch-1)];
    alphas.set(a,(alphas.get(a)||0)+1);
  }
  return {w,h,ct,ch,alphas:[...alphas.entries()].sort((p,q)=>q[1]-p[1]).slice(0,6)};
}
for (const [label, matIdx] of [['嘴 MASK (mat2)',2],['眼 MASK (mat16)',16],['头发 OPAQUE (mat18)',18],['光环 OPAQUE (mat23)',23],['身体 OPAQUE (mat19)',19]]){
  const m=g.materials[matIdx];
  const bt=(m.pbrMetallicRoughness||{}).baseColorTexture;
  if(!bt){ console.log(label+': 无贴图'); continue; }
  const img=g.textures[bt.index], src=g.images[img.source];
  const bv=g.bufferViews[src.bufferView];
  const data=bin.slice(bv.byteOffset||0,(bv.byteOffset||0)+bv.byteLength);
  try{
    const r=pngAlpha(data);
    console.log(label+' alphaMode='+(m.alphaMode||'OPAQUE')+'  '+r.w+'x'+r.h+' ch='+r.ch
      +'  alpha 值分布(top6): '+(r.alphas?JSON.stringify(r.alphas.map(e=>e[0]+':'+e[1])):r.note));
  }catch(e){ console.log(label+' 解析失败: '+e.message); }
}
