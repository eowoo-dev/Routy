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
