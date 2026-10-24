const fs = require('fs');
const buf = fs.readFileSync(process.argv[2] || './model.glb');
let off = 12, g = null, bin = null;
while (off < buf.length) {
  const len = buf.readUInt32LE(off), type = buf.readUInt32LE(off + 4);
  const data = buf.slice(off + 8, off + 8 + len);
  if (type === 0x4E4F534A) g = JSON.parse(data.toString('utf8'));
  else if (type === 0x004E4942) bin = data;
  off += 8 + len;
}
const CT = {5120: Int8Array,5121: Uint8Array,5122: Int16Array,5123: Uint16Array,5125: Uint32Array,5126: Float32Array};
const NC = {SCALAR:1,VEC2:2,VEC3:3,VEC4:4,MAT4:16};
function acc(i){
  const a = g.accessors[i];
  const bv = g.bufferViews[a.bufferView];
  const TA = CT[a.componentType], n = NC[a.type];
  const start = (bv.byteOffset||0) + (a.byteOffset||0);
  return new TA(bin.buffer.slice(bin.byteOffset+start, bin.byteOffset+start+a.count*n*TA.BYTES_PER_ELEMENT));
}
// hierarchy
const parent = new Array(g.nodes.length).fill(-1);
g.nodes.forEach((n,i)=>{ (n.children||[]).forEach(c=>parent[c]=i); });
function leafMost(n){ // node 0 is likely a leaf
  for (let i=0;i<g.nodes.length;i++) if (!(g.nodes[i].children||[]).length) return i;
  return 0;
}
// find scene roots
const scenes = g.scenes||[{nodes:[]}];
console.log('scene roots:', scenes[0].nodes.map(i=>i+':'+g.nodes[i].name).join(', '));
const rootNode = scenes[0].nodes[0];
console.log('root name', g.nodes[rootNode].name, 'children', (g.nodes[rootNode].children||[]).length);
console.log('node0 children', (g.nodes[0].children||[]).length, 'parent', parent[0]);
// print tree depth of node 0
let d=0, p=0; while(parent[p]>=0){p=parent[p]; d++; if(d>50)break;}
console.log('node0 depth', d, 'root-of-it', p, g.nodes[p].name);

// dump one node's default TRS
function trs(i){
  const n=g.nodes[i];
  return {t:n.translation||[0,0,0], r:n.rotation||[0,0,0,1], s:n.scale||[1,1,1], m:!!n.matrix};
}
console.log('--- samples');
[0, 1, 2, 3].forEach(i=>console.log(' node',i,g.nodes[i].name, JSON.stringify(trs(i)).slice(0,150)));
// find a hair bone
const hairIdx = g.nodes.map((n,i)=>[n.name,i]).filter(([n])=>/hair/.test(n||''));
console.log('hair bones sample:');
hairIdx.slice(0,5).forEach(([n,i])=>console.log('  ',i,n,JSON.stringify(trs(i))));
// find its parent chain
let hi = hairIdx[0][1], chain=[];
while(hi>=0){chain.push(g.nodes[hi].name); hi=parent[hi]; if(chain.length>30)break;}
console.log('hair[0] chain:', chain.join(' <- '));

// ---- evaluate animation at t=0 for Cafe_Idle, compare hair world positions
const clipIdx = g.animations.findIndex(a=>a.name==='Cafe_Idle');
const anim = g.animations[clipIdx];
const N = g.nodes.length;
// sample at t=0: take first keyframe value of each sampler
const animTRS = {};
for (const ch of anim.channels){
  const smp = anim.samplers[ch.sampler];
  const out = acc(smp.output);
  const inp = acc(smp.input);
  const n = NC[g.accessors[smp.output].type];
  const v = Array.from(out.slice(0, n)); // first keyframe
  if (!animTRS[ch.target.node]) animTRS[ch.target.node] = {};
  animTRS[ch.target.node][ch.target.path] = v;
}
console.log('anim channels at t=0 nodes:', Object.keys(animTRS).length);
console.log('duration', Math.max(...anim.samplers.map(s=>{const a=acc(s.input);return a[a.length-1];})));

// ---- mat math
function qmul(a,b){return [a[3]*b[0]+a[0]*b[3]+a[1]*b[2]-a[2]*b[1], a[3]*b[1]-a[0]*b[2]+a[1]*b[3]+a[2]*b[0], a[3]*b[2]+a[0]*b[1]-a[1]*b[0]+a[2]*b[3], a[3]*b[3]-a[0]*b[0]-a[1]*b[1]-a[2]*b[2]];}
function qrot(q,v){
  const x=v[0],y=v[1],z=v[2];
  const ix=q[3]*x+q[1]*z-q[2]*y, iy=q[3]*y+q[2]*x-q[0]*z, iz=q[3]*z+q[0]*y-q[1]*x, iw=-q[0]*x-q[1]*y-q[2]*z;
  return [ix*q[3]+iw*-q[0]+iy*-q[2]-iz*-q[1], iy*q[3]+iw*-q[1]+iz*-q[0]-ix*-q[2], iz*q[3]+iw*-q[2]+ix*-q[1]-iy*-q[0]];
}
function worldPos(useAnim, only){
  const wpos = new Array(N), wq = new Array(N);
  const order = [];
  (function walk(i){ order.push(i); (g.nodes[i].children||[]).forEach(walk); })(rootNode);
  // also handle nodes not reachable
  for (let i=0;i<N;i++) if (!order.includes(i)) order.push(i);
  for (const i of order){
    let t = trs(i).t.slice(), r = trs(i).r.slice();
    if (useAnim && animTRS[i]){
      if ((!only || only.includes('t')) && animTRS[i].translation) {
        const a = animTRS[i].translation;
        const sc = trs(i).s;
        // three: position track values replace position directly (no scale mult)
        t = a.slice();
      }
      if ((!only || only.includes('r')) && animTRS[i].rotation) r = animTRS[i].rotation.slice();
    }
    const pi = parent[i];
    if (pi < 0) { wq[i] = r; wpos[i] = t; }
    else {
      const ps = trs(pi).s;
      const scaled = [t[0]*ps[0], t[1]*ps[1], t[2]*ps[2]];
      const rot = qrot(wq[pi], scaled);
      wpos[i] = [wpos[pi][0]+rot[0], wpos[pi][1]+rot[1], wpos[pi][2]+rot[2]];
      wq[i] = qmul(wq[pi], r);
    }
  }
  return wpos;
}
const restP = worldPos(false);
const animP = worldPos(true);
const animOnlyRot = worldPos(true, ['r']);
function dist(a,b){return Math.hypot(a[0]-b[0],a[1]-b[1],a[2]-b[2]);}
let maxD=0,maxD2=0,maxN='',maxN2='';
g.nodes.forEach((n,i)=>{
  const d1=dist(restP[i],animP[i]), d2=dist(restP[i],animOnlyRot[i]);
  if(d1>maxD){maxD=d1;maxN=n.name;}
  if(d2>maxD2){maxD2=d2;maxN2=n.name;}
});
console.log('max |rest-anim| (full):', maxD.toFixed(4), maxN);
console.log('max |rest-anim| (rot only):', maxD2.toFixed(4), maxN2);
// hair specific
let hairMax=0,hairMaxN='', hairRest=[], hairAnim=[];
hairIdx.forEach(([n,i])=>{
  const d = dist(restP[i], animP[i]);
  if (d>hairMax){hairMax=d; hairMaxN=n;}
  hairRest.push(restP[i]); hairAnim.push(animP[i]);
});
console.log('hair max delta:', hairMax.toFixed(4), hairMaxN);
console.log('hair rest positions (first 6):');
hairIdx.slice(0,6).forEach(([n,i])=>console.log('   ',n,'rest',restP[i].map(x=>x.toFixed(3)).join(','),'anim',animP[i].map(x=>x.toFixed(3)).join(',')));
// bounding box of rest vs anim, all nodes
function bbox(P){ let mn=[1e9,1e9,1e9],mx=[-1e9,-1e9,-1e9]; P.forEach(p=>{for(let k=0;k<3;k++){mn[k]=Math.min(mn[k],p[k]);mx[k]=Math.max(mx[k],p[k]);}}); return [mn,mx]; }
console.log('rest bbox', JSON.stringify(bbox(restP).map(v=>v.map(x=>+x.toFixed(3)))));
console.log('anim bbox', JSON.stringify(bbox(animP).map(v=>v.map(x=>+x.toFixed(3)))));
