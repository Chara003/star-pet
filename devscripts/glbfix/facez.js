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
console.log('=== 每个 primitive 用的材质 + 位置范围（看谁"在前面"）===');
g.meshes.forEach((m,mi)=>{
  const nd=g.nodes.find(x=>x.mesh===mi&&x.name);
  m.primitives.forEach((p,pi)=>{
    const mat=g.materials[p.material];
    const pos=acc(p.attributes.POSITION);
    let mn=[1e9,1e9,1e9],mx=[-1e9,-1e9,-1e9];
    for(let i=0;i<pos.length;i+=3)for(let c=0;c<3;c++){mn[c]=Math.min(mn[c],pos[i+c]);mx[c]=Math.max(mx[c],pos[i+c]);}
    const nm=(nd?nd.name:m.name)||('mesh'+mi);
    /* 只看脸附近的（x 接近 0，z 在 0.6~0.8） */
    const nearFace = Math.abs(mn[0])<0.2 && mn[2]>0.55 && mx[2]<0.85;
    if (!nearFace) return;
    console.log('  mesh'+String(mi).padStart(2)+' prim'+pi+' '+nm.padEnd(20)
      +' mat='+String(p.material).padStart(2)+' '+String(mat.name).padEnd(26)
      +' alphaMode='+(mat.alphaMode||'OPAQUE').padEnd(7)
      +' z=['+mn[2].toFixed(4)+','+mx[2].toFixed(4)+']'
      +'  索引='+(p.indices!==undefined?'有':'无'));
  });
});
console.log('\n=== 结论：z 越大越"靠前"（模型面向 +Z）===');
console.log('  嘴(Mouth_305) 的 z 范围是 0.6177 ~ 0.6737');
console.log('  若某个 Face/EyeMouth 图元的 z 超过 0.674 且覆盖同一区域 → 会把嘴挡住');
