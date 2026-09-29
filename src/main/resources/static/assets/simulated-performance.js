import {request} from './api.js';
const $=id=>document.getElementById(id);
const time=value=>new Date(value).toLocaleTimeString('en-IN',{timeZone:'Asia/Kolkata',hour:'2-digit',minute:'2-digit',second:'2-digit'});
// All OHLC samples are supplied by the backend. Interpolation below only animates index graphics.
export class PerformanceChart {
  constructor(id,onUpdate,onError){
    this.id=id;this.onUpdate=onUpdate;this.onError=onError;this.samples=[];this.zoom=1;this.follow=true;this.selected=0;this.version=0;
    this.canvas=$('performance-chart');this.canvas.tabIndex=0;
    for(const key of ['chart-range','chart-interval'])$(key).addEventListener('change',()=>{this.zoom=1;this.refresh();});
    $('chart-style').addEventListener('change',()=>this.draw());
    $('chart-zoom-in').addEventListener('click',()=>{this.zoom=Math.min(16,this.zoom*2);this.draw();});
    $('chart-zoom-out').addEventListener('click',()=>{this.zoom=Math.max(1,this.zoom/2);this.draw();});
    $('chart-live-reset').addEventListener('click',()=>{this.follow=true;this.zoom=1;this.selected=this.samples.length-1;this.draw();});
    $('chart-scrubber').addEventListener('input',()=>{this.follow=false;this.selected=Number($('chart-scrubber').value);this.draw();});
    this.canvas.addEventListener('pointermove',event=>{
      if(!this.visible?.length)return;const box=this.canvas.getBoundingClientRect(),t=this.domainStart+(event.clientX-box.left-48)/(box.width-66)*(this.domainEnd-this.domainStart);
      let index=0,best=Infinity;this.samples.forEach((s,i)=>{const distance=Math.abs(new Date(s.startsAt).getTime()-t);if(distance<best){index=i;best=distance;}});
      this.follow=false;this.selected=index;this.draw();
    });
    this.canvas.addEventListener('pointerleave',()=>{this.follow=true;this.selected=this.samples.length-1;this.draw();});
    this.canvas.addEventListener('keydown',event=>{if(!['ArrowLeft','ArrowRight'].includes(event.key))return;event.preventDefault();this.follow=false;this.selected=Math.max(0,Math.min(this.samples.length-1,this.selected+(event.key==='ArrowRight'?1:-1)));this.draw();});
    new ResizeObserver(()=>this.draw()).observe(this.canvas);
  }
  async refresh(){
    const version=++this.version;
    try{
      const data=await request(`/api/purchases/${encodeURIComponent(this.id)}/performance?interval=${$('chart-interval').value}&range=${$('chart-range').value}`);
      if(version!==this.version)return;
      const old=this.samples.at(-1);this.data=data;this.samples=data.candles;this.onUpdate(data);
      if(this.follow)this.selected=this.samples.length-1;else this.selected=Math.min(this.selected,this.samples.length-1);
      $('chart-scrubber').max=String(Math.max(0,this.samples.length-1));
      $('chart-live').textContent=(data.cycle?.status==='ACTIVE'?'● Live illustration · updated ':'Cycle illustration closed · ')+time(data.serverTime)+' IST';
      cancelAnimationFrame(this.animation);
      const last=this.samples.at(-1),animate=old&&last&&old.startsAt===last.startsAt&&!matchMedia('(prefers-reduced-motion: reduce)').matches;
      const start=performance.now();
      const frame=now=>{const fraction=animate?Math.min(1,(now-start)/650):1;this.animatedClose=animate?old.close+(last.close-old.close)*(1-Math.pow(1-fraction,3)):last?.close;this.draw();if(fraction<1)this.animation=requestAnimationFrame(frame);};
      this.animation=requestAnimationFrame(frame);
    }catch(ex){if(version!==this.version)return;$('chart-live').textContent='Live update unavailable. Showing last received data.';this.onError(ex);}
  }
  draw(){
    const box=this.canvas.getBoundingClientRect();if(!box.width)return;
    const ratio=devicePixelRatio||1;this.canvas.width=Math.round(box.width*ratio);this.canvas.height=Math.round(box.height*ratio);
    const ctx=this.canvas.getContext('2d');ctx.scale(ratio,ratio);const w=box.width,h=box.height,left=48,right=w-18,top=30,bottom=h-36;
    ctx.font='12px sans-serif';ctx.fillStyle='#b9aac9';ctx.fillText('Simulated index · arbitrary units',left,17);
    if(!this.samples.length){ctx.fillText('Available after purchase',left,80);return;}
    const count=Math.max(2,Math.ceil(this.samples.length/this.zoom));this.visible=this.samples.slice(-count);
    const start=new Date(this.data.cycle.startsAt).getTime(),end=new Date(this.data.cycle.endsAt).getTime(),now=Math.min(end,new Date(this.data.serverTime).getTime());
    this.domainStart=this.zoom>1?new Date(this.visible[0].startsAt).getTime():Math.max(start,now-this.data.rangeHours*3600000);
    this.domainEnd=this.zoom===1&&this.data.rangeHours===24?end:Math.max(this.domainStart+60000,now);
    const min=Math.min(...this.visible.map(s=>s.low)),max=Math.max(...this.visible.map(s=>s.high)),pad=Math.max(.5,(max-min)*.12),lo=min-pad,hi=max+pad;
    const x=t=>left+(new Date(t).getTime()-this.domainStart)/(this.domainEnd-this.domainStart)*(right-left),y=v=>bottom-(v-lo)/(hi-lo)*(bottom-top);
    for(let i=0;i<=4;i++){const value=lo+(hi-lo)*i/4,py=y(value);ctx.strokeStyle='#45384f';ctx.beginPath();ctx.moveTo(left,py);ctx.lineTo(right,py);ctx.stroke();ctx.fillText(value.toFixed(1),4,py+4);}
    for(let i=0;i<=3;i++){const t=this.domainStart+(this.domainEnd-this.domainStart)*i/3;ctx.textAlign=i===3?'right':i===0?'left':'center';ctx.fillText(new Date(t).toLocaleTimeString('en-IN',{timeZone:'Asia/Kolkata',hour:'2-digit',minute:'2-digit'}),left+(right-left)*i/3,h-10);}ctx.textAlign='left';
    ctx.save();ctx.beginPath();ctx.rect(left,top,right-left,bottom-top);ctx.clip();
    const points=this.visible.map((s,i)=>({x:x(s.endsAt),y:y(i===this.visible.length-1&&this.animatedClose!==undefined?this.animatedClose:s.close)}));
    const path=()=>{ctx.beginPath();ctx.moveTo(points[0].x,points[0].y);for(let i=1;i<points.length;i++){const a=points[i-1],b=points[i];ctx.bezierCurveTo((a.x+b.x)/2,a.y,(a.x+b.x)/2,b.y,b.x,b.y);}};
    path();ctx.lineTo(points.at(-1).x,bottom);ctx.lineTo(points[0].x,bottom);ctx.closePath();const gradient=ctx.createLinearGradient(0,top,0,bottom);gradient.addColorStop(0,'#b997e044');gradient.addColorStop(1,'#b997e000');ctx.fillStyle=gradient;ctx.fill();
    if($('chart-style').value==='candles')this.visible.forEach(s=>{const px=x(s.startsAt),width=Math.max(1,Math.min(16,(right-left)*this.data.intervalMinutes*60000/(this.domainEnd-this.domainStart)*.62));ctx.strokeStyle=ctx.fillStyle=s.close>=s.open?'#c4a0f0':'#81bad0';ctx.lineWidth=1;ctx.beginPath();ctx.moveTo(px,y(s.high));ctx.lineTo(px,y(s.low));ctx.stroke();ctx.fillRect(px-width/2,Math.min(y(s.open),y(s.close)),width,Math.max(1.5,Math.abs(y(s.open)-y(s.close))));});
    path();ctx.strokeStyle=$('chart-style').value==='line'?'#e1c7ff':'#c4a0f077';ctx.lineWidth=$('chart-style').value==='line'?2.2:1;ctx.stroke();
    const tip=points.at(-1);ctx.fillStyle='#e8d6ff';ctx.beginPath();ctx.arc(tip.x,tip.y,3.5,0,Math.PI*2);ctx.fill();
    const sample=this.samples[Math.max(0,this.selected)];ctx.setLineDash([3,5]);ctx.strokeStyle='#eee0ff';ctx.beginPath();ctx.moveTo(x(sample.startsAt),top);ctx.lineTo(x(sample.startsAt),bottom);ctx.stroke();ctx.restore();
    $('chart-scrubber').value=String(Math.max(0,this.selected));
    $('chart-readout').textContent=`${time(sample.startsAt)}–${time(sample.endsAt)} IST · ${sample.complete?'Closed candle':'Developing candle'} · O ${sample.open.toFixed(3)} / H ${sample.high.toFixed(3)} / L ${sample.low.toFixed(3)} / C ${sample.close.toFixed(3)} units. No monetary value.`;
    $('chart-zoom-in').disabled=this.zoom>=16;$('chart-zoom-out').disabled=this.zoom<=1;
  }
}
