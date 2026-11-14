import { randomUUID } from 'node:crypto';
import { EnvironmentSimulation } from '../environment/simulation.mjs';

export const scenarios = [
  { id: 'lane-change', title: '정상 차로 변경', description: '왼쪽 방향지시등을 미리 켜고 옆 차로에서 안정합니다.', durationMs: 12000,
    expected: ['LANE_CHANGE_LEFT', 'LANE_CHANGE_SIGNAL_OBSERVED'], forbidden: ['LANE_CHANGE_RIGHT','MISSING_LANE_CHANGE_SIGNAL','INCOMPLETE_LANE_CHANGE'] },
  { id: 'lane-boundary', title: '차로 경계 접근', description: '차로 안에서 왼쪽 경계에 가까이 접근한 뒤 복귀합니다.', durationMs: 12000,
    expected: ['LANE_BOUNDARY_APPROACH'], forbidden: ['LANE_CHANGE_LEFT','LANE_CHANGE_RIGHT','LANE_CHANGE_RETURNED'] },
  { id: 'red-stop', title: '적색 신호 정차', description: '적색 신호의 정지선 앞에서 감속하고 정차합니다.', durationMs: 14000,
    expected: ['STOPPED_BEFORE_RED'], forbidden: ['STOP_LINE_CROSSING_RED','STOP_LINE_CROSSING_GREEN','STOP_LINE_CROSSING_YELLOW'] },
  { id: 'following-gap', title: '앞차 간격 감소', description: '같은 차로의 느린 앞차에 접근해 시간 간격과 TTC를 관측합니다.', durationMs: 10000,
    expected: ['SHORT_FOLLOWING_HEADWAY','LOW_CLOSING_TTC'], forbidden: ['LANE_CHANGE_LEFT','LANE_CHANGE_RIGHT','STOP_LINE_CROSSING_RED'] },
];

// Quintic reference is an environment trajectory, never a precomputed evaluation event.
function transition(t, start, duration, amount) {
  const u=Math.max(0,Math.min(1,(t-start)/duration));
  return { d:amount*(10*u**3-15*u**4+6*u**5),
    velocity:amount*(30*u**2-60*u**3+30*u**4)/duration,
    acceleration:amount*(60*u-180*u**2+120*u**3)/duration**2 };
}
function control(id, t, model) {
  let target={d:0,velocity:0,acceleration:0};
  if(id==='lane-change') target=transition(t,4,4,3.7);
  if(id==='lane-boundary') {
    const rise=transition(t,2,2,.8),fall=transition(t,8,2,.8);
    target={d:rise.d-fall.d,velocity:rise.velocity-fall.velocity,acceleration:rise.acceleration-fall.acceleration};
  }
  const speed=id==='red-stop' ? (t<4 ? 10 : t<9 ? 10-2*(t-4) : 0) : 10;
  const centerD=model.d+Math.sin(model.heading);
  const desired=Math.asin(Math.max(-.5,Math.min(.5,target.velocity/Math.max(speed,1))));
  const rate=target.acceleration/Math.max(1,speed*Math.cos(desired))+2*(desired-model.heading)+.5*(target.d-centerD);
  const steeringDeg=speed>0 ? Math.atan(rate*2.8/speed)*15*180/Math.PI : 0;
  return {speed,steeringDeg,signal:id==='lane-change' && t>=.5 ? 'LEFT':'OFF',
    light:id==='red-stop' ? 'RED':'GREEN', stopLineS:id==='red-stop' ? 70 : 1000,
    vehicles:id==='following-gap' ? [{id:'lead',s:48+6*t,d:0,speed:6}] : []};
}

export class ScenarioRunner {
  constructor() { this.status='idle'; this.definition=null; this.frames=[]; }
  select(id) {
    const definition=scenarios.find(s=>s.id===id);
    if(!definition) throw new Error('알 수 없는 시나리오입니다.');
    if(this.status==='running') throw new Error('실행을 정지한 뒤 시나리오를 선택하세요.');
    this.definition=definition;
    return this.reset();
  }
  reset() {
    if(!this.definition) throw new Error('시나리오를 먼저 선택하세요.');
    this.model=new EnvironmentSimulation(randomUUID()); this.frames=[]; this.status='ready';
    const initial=control(this.definition.id,0,this.model);
    this.preview={version:2,source:'SIMULATED_ENVIRONMENT',runId:this.model.runId,atMs:0,sequence:0,
      s:1,d:0,heading:0,available:true,speed:initial.speed,signal:initial.signal,light:initial.light,stopLineS:initial.stopLineS,
      vehicles:initial.vehicles.map(v=>({length:4.8,width:1.8,...v}))};
    return this.snapshot();
  }
  start() {
    if(!['ready','stopped'].includes(this.status)) throw new Error('준비 또는 정지 상태에서만 시작할 수 있습니다.');
    this.status='running'; return this.snapshot();
  }
  stop() { if(this.status==='running') this.status='stopped'; return this.snapshot(); }
  release() {
    if(this.status==='running') throw new Error('실행을 정지한 뒤 수동 모드로 전환하세요.');
    this.definition=null; this.frames=[]; this.preview=null; this.status='idle'; return this.snapshot();
  }
  tick(generatedAtMs=Math.floor(performance.now())) {
    if(this.status!=='running') return;
    const frame={...this.model.step(control(this.definition.id,this.model.atMs/1000,this.model)),generatedAtMs};
    this.frames.push(frame);
    if(frame.atMs>=this.definition.durationMs) this.status='completed';
    return frame;
  }
  snapshot() { return { status:this.status, scenarioId:this.definition?.id ?? null, durationMs:this.definition?.durationMs ?? 0,
    frame:this.frames.at(-1) ?? this.preview ?? null, count:this.frames.length,
    checks:this.status==='completed' ? verifyGeometry(this.definition.id,this.frames) : [] }; }
}

export function generateScenario(id) {
  const runner=new ScenarioRunner(); runner.select(id); runner.start();
  while(runner.status==='running') runner.tick(runner.model.atMs);
  return { ...runner.definition, frames:runner.frames, checks:verifyGeometry(id,runner.frames) };
}

export function verifyGeometry(id, frames) {
  const last=frames.at(-1), definition=scenarios.find(s=>s.id===id);
  const check=(label,passed)=>({label,passed:Boolean(passed)});
  const common=[check('끝까지 실행',last?.atMs===definition.durationMs),
    check('연속된 프레임',frames.length===definition.durationMs/50 && frames[0]?.atMs===50 && frames.every((f,i)=>f.available && Number.isFinite(f.d) && Math.abs(f.d)<5.55 && f.sequence===i+1 && (i===0 || f.atMs-frames[i-1].atMs===50)))];
  if(id==='lane-change') return [...common,check('왼쪽 차로에서 안정',frames.filter(f=>f.atMs>=10000).every(f=>Math.abs(f.d-3.7)<.1 && Math.abs(f.heading)<.02))];
  if(id==='lane-boundary') return [...common,check('경계 안에서 접근',Math.max(...frames.map(f=>f.d))>.72 && frames.every(f=>Math.abs(f.d)+.9<1.85)),check('원래 차로 복귀',Math.abs(last.d)<.05)];
  if(id==='red-stop') return [...common,check('정지선 앞 정차',last.speed===0 && last.stopLineS-last.s-2.4>=0 && last.stopLineS-last.s-2.4<=5),check('적색 중 통과 없음',frames.every(f=>f.light==='RED' && f.s+2.4<f.stopLineS))];
  const gaps=frames.map(f=>f.vehicles[0].s-f.s-4.8);
  return [...common,check('간격 감소와 비접촉',gaps[0]>30 && gaps.at(-1)>0 && gaps.at(-1)<5)];
}
