let lastSent = -Infinity;
let busy = false;
let environment = null, environmentAt = 0;
export function readEnvironment() { return performance.now()-environmentAt<500 ? environment : null; }

// This observes rendered coordinates. It does not produce driving judgments or write vehicle properties.
export function publishEnvironment(observation) {
  const now = performance.now();
  if (busy || now - lastSent < 100) return;
  lastSent = now;
  busy = true;
  fetch('/routy/model',{signal:AbortSignal.timeout(500)}).then(async response=>{
    if(response.ok) { environment=(await response.json()).frame; environmentAt=performance.now(); }
    else environment=null;
  }).catch(()=>{environment=null;});
  if (observation.input) fetch('/routy/input', {
    method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(observation.input),signal:AbortSignal.timeout(500),
  }).catch(() => {});
  fetch('/routy/environment', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ version: 1, source: 'SIMULATED_ENVIRONMENT', model: 'CONSOLE_VISUALIZATION',
      available: true, capturedAtMs: Date.now(), observation }),
    signal: AbortSignal.timeout(1000),
  }).catch(() => {}).finally(() => { busy = false; });
}
