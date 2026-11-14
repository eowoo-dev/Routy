import {test} from 'node:test';
import assert from 'node:assert/strict';
import {EnvironmentSimulation} from './simulation.mjs';
import {readFileSync} from 'node:fs';
test('fixed inputs reproduce the entire frame sequence',()=>{
  const run=()=>{const model=new EnvironmentSimulation('test'); return Array.from({length:100},()=>model.step({speed:10,steeringDeg:20}));};
  assert.deepEqual(run(),run());
});
test('straight motion and signed steering are independent of rendering',()=>{
  const model=new EnvironmentSimulation('test');
  const straight=model.step({speed:10});
  assert.equal(straight.d,0); assert.equal(straight.s,1.5);
  const left=model.step({speed:10,steeringDeg:60});
  assert.ok(left.heading>0); assert.ok(left.d>0);
  assert.throws(()=>model.step({speed:Infinity}));
  assert.throws(()=>model.step({},1000));
});
test('SDK integration steering fixture stays inside the straight-road model scope',()=>{
  const plan=JSON.parse(readFileSync(new URL('../../../apps/pleos/driving-evaluation/src/main/resources/scenarios/demo-v1.json',import.meta.url),'utf8')).emulator;
  for(const phase of plan.phases){
    const model=new EnvironmentSimulation('sdk-fixture-'+phase.id);
    for(let t=0;t<plan.environmentPhaseSeconds*1000;t+=50){
      const index=Math.floor((t-plan.settleSeconds*1000)/(plan.steeringIntervalSeconds*1000));
      const steering=index<0?0:plan.environmentSteeringDegrees[index%plan.environmentSteeringDegrees.length];
      const frame=model.step({speed:plan.speedKph/3.6,steeringDeg:-steering});
      assert.ok(Math.abs(frame.d)<5.55,phase.id+' escaped the supported lateral domain');
      assert.ok(Math.abs(frame.heading)<Math.PI/2,phase.id+' escaped the supported heading domain');
    }
  }
});
