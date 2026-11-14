let lastSent = -Infinity;
let busy = false;
let environment = null, environmentAt = 0;
let controller='manual';
export function readEnvironment() { return performance.now()-environmentAt<500 ? environment : null; }
// Scenario view uses one polled frame for both its labels and renderer, without publishing inputs.
export function setObservedEnvironment(frame) { environment=frame;environmentAt=performance.now(); }

// This observes rendered coordinates. It does not produce driving judgments or write vehicle properties.
export function publishEnvironment(observation) {
  if(observation.observeOnly) return;
  const now = performance.now();
  if (busy || now - lastSent < 100) return;
  lastSent = now;
  busy = true;
  const requests=[fetch('/routy/model',{signal:AbortSignal.timeout(500)}).then(async response=>{
    const result=await response.json(); controller=result.controller ?? 'manual';
    if(response.ok) { environment=result.frame; environmentAt=performance.now(); }
    else environment=null;
  }).catch(()=>{environment=null;})];
  if (observation.input && controller==='manual') requests.push(fetch('/routy/input', {
    method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(observation.input),signal:AbortSignal.timeout(500),
  }));
  if(!observation.observeOnly) requests.push(fetch('/routy/environment', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ version: 1, source: 'SIMULATED_ENVIRONMENT', model: 'CONSOLE_VISUALIZATION',
      available: true, capturedAtMs: Date.now(), observation }),
    signal: AbortSignal.timeout(1000),
  }));
  Promise.allSettled(requests).finally(() => { busy = false; });
}
