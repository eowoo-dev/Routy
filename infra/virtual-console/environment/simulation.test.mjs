import {test} from 'node:test';
import assert from 'node:assert/strict';
import {EnvironmentSimulation} from './simulation.mjs';
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
