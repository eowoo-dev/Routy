import {setObservedEnvironment} from '/routy/environment-publisher.mjs';
const $=id=>document.getElementById(id);
let frame=null,state=null,pending=false,viewMode='third-person',initialized=false,requestEpoch=0,checkSignature='';
const names={idle:'시나리오를 선택하세요',ready:'준비',running:'실행 중',stopped:'정지 · 시작하면 이어서 실행',completed:'완료'};
function display(value) {
  state=value; frame=value.frame;
  setObservedEnvironment(frame);$('highwayRoad').style.visibility=frame?'visible':'hidden';
  if(!initialized && value.scenarios) {
    for(const s of value.scenarios) $('scenario').add(new Option(s.title,s.id));
    initialized=true;
  }
  if(value.scenarioId!==undefined) $('scenario').value=value.scenarioId ?? '';
  const definition=Array.from($('scenario').options).find(o=>o.value===value.scenarioId);
  const info=value.scenarios?.find(s=>s.id===value.scenarioId);
  if(info) { $('description').textContent=info.description;$('expected').textContent=info.expected.join(', '); }
  if(!definition || !value.scenarioId) {$('description').textContent='네 가지 독립 시나리오를 제공합니다.';$('expected').textContent='—';}
  if($('status').textContent!==names[value.status]) $('status').textContent=names[value.status];
  $('scenario').disabled=pending || value.status==='running';
  $('start').disabled=pending || !['ready','stopped'].includes(value.status);
  $('stop').disabled=pending || value.status!=='running';
  $('reset').disabled=pending || !value.scenarioId;
  $('release').disabled=pending || !value.scenarioId || value.status==='running';
  const at=frame?.atMs ?? 0;
  $('progress').max=value.durationMs || 1;$('progress').value=at;
  $('time').textContent=`${(at/1000).toFixed(1)} / ${(value.durationMs/1000).toFixed(1)}초`;
  $('count').textContent=String(value.count);$('run').textContent=frame?.runId ?? '—';
  $('trace').hidden=value.count===0;
  $('speed').textContent=`${((frame?.speed ?? 0)*3.6).toFixed(1)} km/h`;
  $('lateral').textContent=`${(frame?.d ?? 0).toFixed(2)} m`;
  $('light').textContent=({RED:'적색',GREEN:'녹색',YELLOW:'황색'})[frame?.light] ?? '—';
  $('line').textContent=frame?`${(frame.stopLineS-frame.s-2.4).toFixed(1)} m`:'—';
  const leader=frame?.vehicles.filter(v=>v.s>frame.s && Math.abs(v.d-frame.d)<(v.width+1.8)/2).sort((a,b)=>a.s-b.s)[0];
  const gap=leader ? leader.s-frame.s-(leader.length+4.8)/2 : null;
  $('gap').textContent=gap===null?'앞차 없음':gap<=0?'차체 중첩':`${gap.toFixed(1)} m`;
  $('headway').textContent=gap===null?'—':frame.speed>1?`${(gap/frame.speed).toFixed(2)}초`:'정차';
  const closing=leader ? frame.speed-leader.speed : 0;
  $('ttc').textContent=gap===null?'—':closing>.1 && gap>0?`${(gap/closing).toFixed(2)}초`:'접근 없음';
  const signature=JSON.stringify(value.checks ?? []);
  if(signature!==checkSignature) {$('checks').replaceChildren(...(value.checks?.length ? value.checks.map(c=>{
    const li=document.createElement('li');li.textContent=`${c.passed?'통과':'실패'} · ${c.label}`;return li;
  }):[Object.assign(document.createElement('li'),{textContent:'실행 완료 후 결과가 표시됩니다.'})]));checkSignature=signature;}
}
async function poll() {
  if(pending) return;
  const epoch=requestEpoch;
  const response=await fetch('/routy/scenarios/state',{signal:AbortSignal.timeout(2000)});
  if(!response.ok)throw new Error('상태를 불러오지 못했습니다.');
  const value=await response.json();if(epoch===requestEpoch && !pending)display(value);
}
function failed(error) {$('error').textContent=error.message;setObservedEnvironment(null);$('highwayRoad').style.visibility='hidden';}
async function action(action,id) {
  pending=true;requestEpoch++;if(state)display(state);$('error').textContent='';
  try {
    const response=await fetch('/routy/scenarios/control',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({action,id}),signal:AbortSignal.timeout(3000)});
    const result=await response.json(); if(!response.ok)throw new Error(result.error);
  } catch(error) {$('error').textContent=error.message;}
  finally {pending=false;await poll().catch(failed);}
}
$('scenario').addEventListener('change',()=>{if($('scenario').value)action('select',$('scenario').value);});
for(const name of ['start','stop','reset','release'])$(name).addEventListener('click',()=>action(name));
for(const [id,mode] of [['first','first-person'],['third','third-person']])$(id).addEventListener('click',()=>{
  viewMode=mode;$('first').setAttribute('aria-pressed',String(mode==='first-person'));$('third').setAttribute('aria-pressed',String(mode==='third-person'));
});
await poll().catch(failed);
setInterval(()=>poll().catch(failed),200);
document.body.dataset.consoleTheme='highway';
try {
  const {createHighwayView}=await import('/highway-view.js');
  const view=await createHighwayView($('highwayRoad'),()=>({observeOnly:true,speed_kph:frame ? frame.speed*3.6:0,steering_deg:0,
    gear:state?.status==='running'?'Drive':'Park',brake_pct:frame?.speed===0?100:0,
    viewMode,wipers:'Off',guidance:false,ldw:null,fcw:false}),status=>{
    $('render-status').textContent=status.error ?? (frame ? '합성 환경 · 화면과 수치가 동일한 환경 프레임을 관찰합니다.' : '시나리오를 선택하면 주행 장면이 표시됩니다.');
  });
  addEventListener('pagehide',()=>view.dispose(),{once:true});
} catch(error) {$('render-status').textContent='3D 화면을 불러오지 못했습니다: '+error.message;}
