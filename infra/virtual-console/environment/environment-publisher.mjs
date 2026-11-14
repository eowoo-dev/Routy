let lastSent = -Infinity;
let busy = false;

// This observes rendered coordinates. It does not produce driving judgments or write vehicle properties.
export function publishEnvironment(observation) {
  const now = performance.now();
  if (busy || now - lastSent < 100) return;
  lastSent = now;
  busy = true;
  fetch('/routy/environment', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ version: 1, source: 'SIMULATED_ENVIRONMENT', model: 'CONSOLE_VISUALIZATION',
      available: true, capturedAtMs: Date.now(), observation }),
    signal: AbortSignal.timeout(1000),
  }).catch(() => {}).finally(() => { busy = false; });
}
