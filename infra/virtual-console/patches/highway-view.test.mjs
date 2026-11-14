import { test } from 'node:test';
import assert from 'node:assert/strict';
import { patchHighway } from './highway-view.mjs';

test('rejects changed or ambiguous insertion points', () => {
  assert.throws(() => patchHighway('changed renderer'));
  assert.throws(() => patchHighway('    renderer.render(scene, camera);\n    renderer.render(scene, camera);'));
});
test('keeps the renderer and publishes raw observations', () => {
  const result = patchHighway('export function render() {\n    renderer.render(scene, camera);\n}');
  assert.ok(result.includes('publishEnvironment({'));
  assert.equal(result.split('renderer.render(scene, camera);').length, 2);
  assert.ok(result.includes('trafficSignals: null'));
});

test('model left/right positions and heading project consistently into the +Z camera scene', () => {
  const source = `function render() {
    const s={steering_deg:0,gear:'Drive',speed_kph:0};
    const ego={group:{position:{x:0}},body:{rotation:{y:0}}};
    const THREE={MathUtils:{damp:()=>0}}, dt=0, traffic=[], distance=0;
    const clamp=(v,min,max)=>Math.min(max,Math.max(min,v));
    const renderer={render(){}},scene={},camera={};
    const steering = clamp(s.steering_deg / 540, -1, 1);
    ego.group.position.x = THREE.MathUtils.damp(ego.group.position.x, -steering * 1.15, 5, dt);
    ego.body.rotation.y = Math.PI - steering * .13;
    renderer.render(scene, camera);
    return {x:ego.group.position.x,yaw:ego.body.rotation.y};
  }`;
  const patched = patchHighway(source).split('\n').slice(1).join('\n');
  const run = frame => Function('readEnvironment','publishEnvironment',patched+'\nreturn render();')(()=>frame,()=>{});
  // Camera looks along +Z, so screen-right is world -X. The GLB's forward axis is local -Z.
  for (const sign of [-1,1]) {
    const pose=run({d:sign*3.7,heading:sign*.2});
    assert.equal(pose.x,sign*3.7);
    assert.ok(-Math.sin(pose.yaw)*sign>0);
  }
});
