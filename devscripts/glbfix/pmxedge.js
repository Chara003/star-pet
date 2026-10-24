/* PMX 材质描边（edgeColor/edgeFlag/edgeSize）读取 —— 偏移已按规范核对 */
const fs=require('fs');
function parse(P){
  const b=fs.readFileSync(P);
  let o=0;
  const sig=b.toString('ascii',0,4); o=4;
  const ver=b.readFloatLE(o); o+=4;
  const gc=b[o]; o+=1;
  const G=[]; for(let i=0;i<gc;i++){ G.push(b[o]); o+=1; }
  // G: [0]=encoding [1]=additionalUV [2]=vertexIdxSize [3]=textureIdxSize
  //    [4]=materialIdxSize [5]=boneIdxSize [6]=morphIdxSize [7]=rigidIdxSize
  const enc=G[0], nUV=G[1], vIdx=G[2], tIdx=G[3], mIdx=G[4], bIdx=G[5];
  const str = () => {
    const n=b.readInt32LE(o); o+=4;
    let s = enc===0 ? b.toString('utf16le',o,o+n) : b.toString('utf8',o,o+n);
    o+=n; return s;
  };
  const nmJ=str(), nmE=str(), cJ=str(), cE=str();
  const vcount=b.readInt32LE(o); o+=4;
  for(let i=0;i<vcount;i++){
    o += 12+12+8 + nUV*16;                 // position+normal+uv+额外UV
    const wt=b[o]; o+=1;
    o += wt===0? bIdx : wt===1? bIdx*2+4 : wt===2? bIdx*4+16
       : wt===3? bIdx*2+4+36 : bIdx*4+16;  // BDEF1/2/4/SDEF/QDEF
    o += 4;                                 // edgeScale
  }
  const fcount=b.readInt32LE(o); o+=4;
  o += fcount*3*vIdx;
  const tcount=b.readInt32LE(o); o+=4;
  for(let i=0;i<tcount;i++) str();
  const mcount=b.readInt32LE(o); o+=4;
  const rows=[];
  for(let i=0;i<mcount;i++){
    const mn=str(), me=str();
    for(let k=0;k<4;k++) o+=4;              // diffuse
    for(let k=0;k<3;k++) o+=4;              // specular
    o+=4;                                   // specularity
    for(let k=0;k<3;k++) o+=4;              // ambient
    const flag=b[o]; o+=1;
    const ec=[]; for(let k=0;k<4;k++){ ec.push(b.readFloatLE(o)); o+=4; }
    const es=b.readFloatLE(o); o+=4;
    const tex=b[o]; o+=tIdx;                // textureIndex (读1字节后按尺寸前进)
    if(tIdx>1) o += (tIdx-1);               // 补齐到 tIdx 字节
    const sph=b[o]; o+=tIdx; if(tIdx>1) o += (tIdx-1);
    const sphMode=b[o]; o+=1;
    const toonFlag=b[o]; o+=1;
    if(toonFlag===0){ o+=1; } else { o+=tIdx; if(tIdx>1) o+=(tIdx-1); }
    str();                                  // memo
    const fc=b.readInt32LE(o); o+=4;
    rows.push({mn,me,flag,ec,es,fc,tex,sph,sphMode,toonFlag});
  }
  return {nmJ,nmE,ver,nUV,mcount,rows,vcount,fcount};
}
for (const P of process.argv.slice(2)){
  console.log('################ '+P);
  let r;
  try { r=parse(P); } catch(e){ console.log('解析失败: '+e.message); continue; }
  console.log('  '+r.nmJ+' / '+r.nmE);
  console.log('  顶点='+r.vcount+'  面='+(r.fcount/3)+'  材质='+r.mcount);
  let nonBlack=0, edgeOn=0;
  const alphas=new Set(), sizes=new Set(), colors=new Map();
  r.rows.forEach(m=>{
    const on=(m.flag & 0x10)!==0 && m.es>0;
    if(on) edgeOn++;
    const isBlack = m.ec[0]===0&&m.ec[1]===0&&m.ec[2]===0;
    if(!isBlack) nonBlack++;
    alphas.add(+m.ec[3].toFixed(3));
    if(m.es>0) sizes.add(+m.es.toFixed(2));
    const key=m.ec.map(x=>x.toFixed(2)).join(',');
    colors.set(key,(colors.get(key)||0)+1);
  });
  console.log('  描边开启的材质='+edgeOn+'/'+r.mcount);
  console.log('  ★ 非黑色描边材质='+nonBlack+'/'+r.mcount);
  console.log('  edgeColor alpha 取值: '+JSON.stringify([...alphas].sort()));
  console.log('  edgeSize 取值(非0): '+JSON.stringify([...sizes].sort((a,c)=>a-c)));
  console.log('  颜色分布(RGBA→材质数):');
  [...colors.entries()].sort((a,c)=>c[1]-a[1]).slice(0,10).forEach(([k,v])=>console.log('     ['+k+'] × '+v));
  console.log('  按描边颜色分组的材质名示例:');
  const seen=new Set();
  r.rows.forEach(m=>{
    const key=m.ec.map(x=>x.toFixed(2)).join(',');
    if(seen.has(key)) return; seen.add(key);
    if(seen.size>5) return;
    console.log('     ['+key+'] → '+m.mn);
  });
}
