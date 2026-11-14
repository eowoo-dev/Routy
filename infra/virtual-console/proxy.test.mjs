import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';

test('observation endpoint rejects invalid frames and expires previously available data', async () => {
  const port = 19000 + Math.floor(Math.random() * 10000);
  const child = spawn(process.execPath, ['infra/virtual-console/proxy.mjs'], {
    env: { ...process.env, PORT: String(port) }, stdio: ['ignore', 'pipe', 'inherit'],
  });
  try {
    await Promise.race([once(child.stdout, 'data'), once(child, 'exit').then(() => { throw new Error('Proxy failed to start'); })]);
    const url = `http://127.0.0.1:${port}/routy/environment`;
    assert.equal((await fetch(url)).status, 503);
    assert.equal((await fetch(url, { method: 'POST', body: '{}' })).status, 415);
    const post = body => fetch(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
    assert.equal((await post({})).status, 400);
    const frame = { version: 1, source: 'SIMULATED_ENVIRONMENT', model: 'CONSOLE_VISUALIZATION', available: true, capturedAtMs: Date.now(), observation: {} };
    assert.equal((await post(frame)).status, 200);
    const available = await fetch(url);
    assert.equal(available.status, 200);
    assert.deepEqual((await available.json()).frame, frame);
    const base=`http://127.0.0.1:${port}`;
    assert.equal((await fetch(base+'/routy/input',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({speed:10,steeringDeg:0,signal:'UNKNOWN'})})).status,200);
    await new Promise(resolve=>setTimeout(resolve,150));
    const trace=await (await fetch(base+'/routy/trace')).json();
    assert.ok(trace.frames.length>0);
    assert.equal(trace.frames[0].version,2);
    assert.equal(trace.frames[0].source,'SIMULATED_ENVIRONMENT');
    assert.equal((await fetch(base+'/routy/model')).status,200);
    await new Promise(resolve => setTimeout(resolve, 1600));
    const expired = await fetch(url);
    assert.equal(expired.status, 503);
    assert.equal((await expired.json()).available, false);
    assert.equal((await fetch(base+'/routy/model')).status,503);
  } finally {
    child.kill();
    await once(child, 'exit');
  }
});
