import http from 'node:http';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { patchHighway } from './patches/highway-view.mjs';
import { EnvironmentSimulation } from './environment/simulation.mjs';

const upstream = new URL(process.env.CONSOLE_UPSTREAM ?? 'http://host.docker.internal:8088');
const manifest = JSON.parse(await readFile(new URL('./upstream-manifest.json', import.meta.url)));
let latest = null;
let receivedAt = 0;
let simulation = null, input = null, inputAt = 0, frames = [];
setInterval(() => {
  if (!input || performance.now()-inputAt>500) return;
  if (!simulation) simulation = new EnvironmentSimulation(crypto.randomUUID());
  const time=simulation.atMs/1000;
  frames.push({...simulation.step({ ...input, light: time%30<15 ? 'RED' : 'GREEN', vehicles: [{ id:'lead-1',s:80+8*time,d:0,speed:8 }] }), generatedAtMs: Math.floor(performance.now())});
  if(frames.length>12000) frames.shift();
},50).unref();
const send = (res, status, value) => {
  res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
  res.end(JSON.stringify(value));
};

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    if(url.pathname==='/routy/clock' && req.method==='GET') return send(res,200,{serverAtMs:Math.floor(performance.now())});
    if(url.pathname==='/routy/model' && req.method==='GET') return send(res,frames.length && performance.now()-inputAt<500 ? 200 : 503,{frame:frames.at(-1) ?? null});
    if(url.pathname==='/routy/trace' && req.method==='GET') return send(res,frames.length?200:503,{frames});
    if(url.pathname==='/routy/input' && req.method==='POST') {
      if(!req.headers['content-type']?.startsWith('application/json')) return send(res,415,{error:'JSON required'});
      if(req.headers.origin && req.headers.origin!==`http://${req.headers.host}`) return send(res,403,{error:'Origin mismatch'});
      let body='';
      for await(const chunk of req) { body+=chunk; if(body.length>4096)return send(res,413,{error:'Input too large'}); }
      let value; try { value=JSON.parse(body); } catch { return send(res,400,{error:'Invalid JSON'}); }
      if(!Number.isFinite(value.speed) || value.speed<0 || value.speed>60 || !Number.isFinite(value.steeringDeg) || !['OFF','LEFT','RIGHT','UNKNOWN'].includes(value.signal)) return send(res,400,{error:'Invalid input'});
      if(performance.now()-inputAt>500) { simulation=null; frames=[]; }
      input=value; inputAt=performance.now(); return send(res,200,{accepted:true});
    }
    if (url.pathname === '/routy/health') {
      return send(res, 200, { status: 'ok', upstream: upstream.origin });
    }
    if (url.pathname === '/routy/environment') {
      if (req.method === 'GET') {
        const ageMs = Date.now() - receivedAt;
        return send(res, latest && ageMs < 1500 ? 200 : 503,
          { available: Boolean(latest && ageMs < 1500 && latest.available), ageMs: latest ? ageMs : null, frame: latest });
      }
      if (req.method !== 'POST') return send(res, 405, { error: 'Use GET or POST' });
      // Same-origin browser publication; do not accept cross-origin form writes.
      if (!req.headers['content-type']?.startsWith('application/json')) return send(res, 415, { error: 'JSON required' });
      if (req.headers.origin && req.headers.origin !== `http://${req.headers.host}`) return send(res, 403, { error: 'Origin mismatch' });
      let body = '';
      for await (const chunk of req) {
        body += chunk;
        if (Buffer.byteLength(body) > 65536) return send(res, 413, { error: 'Frame too large' });
      }
      let frame;
      try { frame = JSON.parse(body); } catch { return send(res, 400, { error: 'Invalid JSON' }); }
      if (frame.version !== 1 || frame.source !== 'SIMULATED_ENVIRONMENT' ||
          frame.model !== 'CONSOLE_VISUALIZATION' || typeof frame.available !== 'boolean' ||
          !Number.isFinite(frame.capturedAtMs)) return send(res, 400, { error: 'Invalid observation envelope' });
      latest = frame;
      receivedAt = Date.now();
      return send(res, 200, { accepted: true });
    }
    if (url.pathname === '/routy/environment-publisher.mjs') {
      res.writeHead(200, { 'content-type': 'text/javascript', 'cache-control': 'no-store' });
      return res.end(await readFile(new URL('./environment/environment-publisher.mjs', import.meta.url)));
    }
    if (url.pathname.startsWith('/routy/')) return send(res, 404, { error: 'Unknown extension route' });
    if (url.pathname === '/highway-view.js' && req.method === 'GET') {
      const response = await fetch(new URL('/highway-view.js', upstream), { signal: AbortSignal.timeout(5000) });
      if (!response.ok) return send(res, 502, { error: 'Upstream renderer unavailable' });
      const bytes = Buffer.from(await response.arrayBuffer());
      const hash = createHash('sha256').update(bytes).digest('hex');
      if (hash !== manifest.highwayViewSha256) return send(res, 502, { error: 'Upstream renderer changed; review patch and manifest', actualSha256: hash });
      res.writeHead(200, { 'content-type': 'text/javascript', 'cache-control': 'no-store' });
      return res.end(patchHighway(bytes.toString('utf8')));
    }
    // Preserve methods, query strings, bodies and streaming responses, including session heartbeats.
    const headers = { ...req.headers, host: upstream.host };
    delete headers.connection;
    const forwarded = http.request(new URL(req.url, upstream), { method: req.method, headers }, response => {
      res.writeHead(response.statusCode, response.headers);
      response.pipe(res);
    });
    forwarded.setTimeout(10000, () => forwarded.destroy(new Error('Upstream timeout')));
    forwarded.on('error', () => { if (!res.headersSent) send(res, 502, { error: 'Console unavailable; check emulator and adb forward' }); else res.destroy(); });
    req.on('aborted', () => forwarded.destroy());
    req.pipe(forwarded);
  } catch (error) {
    console.error(error.message);
    if (!res.headersSent) send(res, 500, { error: 'Console extension failed' }); else res.destroy();
  }
});
server.listen(Number(process.env.PORT ?? 8090), '0.0.0.0', () => console.log('Routy console extension listening on 8090'));
