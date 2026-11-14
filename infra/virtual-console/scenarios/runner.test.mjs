import {test} from 'node:test';
import assert from 'node:assert/strict';
import {ScenarioRunner,scenarios,generateScenario,verifyGeometry} from './runner.mjs';
import {publishEnvironment,setObservedEnvironment,readEnvironment} from '../environment/environment-publisher.mjs';

for(const definition of scenarios) test(definition.id+' is bounded, deterministic and independently reset',()=>{
  const a=generateScenario(definition.id),b=generateScenario(definition.id);
  const normalize=rows=>rows.map(({runId,...rest})=>rest);
  assert.deepEqual(normalize(a.frames),normalize(b.frames));
  assert.ok(a.checks.every(c=>c.passed),JSON.stringify(a.checks));
});
test('truncated traces fail input verification instead of looking complete',()=>{
  const c=generateScenario('lane-change');
  assert.ok(verifyGeometry(c.id,c.frames.slice(1)).some(x=>!x.passed));
});
test('start, stop, resume and reset preserve explicit state',()=>{
  const r=new ScenarioRunner(); assert.throws(()=>r.start()); r.select('lane-change');
  r.start(); r.tick(); assert.throws(()=>r.start()); assert.throws(()=>r.select('red-stop'));
  const before=r.snapshot(); r.stop(); r.tick(); assert.equal(r.snapshot().count,before.count);
  r.start();r.tick();assert.equal(r.snapshot().count,before.count+1);
  r.reset();assert.equal(r.status,'ready');assert.equal(r.frames.length,0);
  assert.notEqual(r.snapshot().frame.runId,before.frame.runId);
  r.release();assert.equal(r.status,'idle');
});
test('scenario observer renders its polled frame without writing any control request',()=>{
  const original=globalThis.fetch;
  let requests=0;
  globalThis.fetch=()=>{requests++;throw new Error('Observer must not write');};
  try {
    const frame=generateScenario('red-stop').frames.at(-1);
    setObservedEnvironment(frame);
    publishEnvironment({observeOnly:true,input:{speed:10}});
    assert.equal(readEnvironment(),frame);
    assert.equal(requests,0);
    setObservedEnvironment(null);assert.equal(readEnvironment(),null);
  } finally {globalThis.fetch=original;}
});
