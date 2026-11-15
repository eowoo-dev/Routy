import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { scenarios, generateScenario } from './runner.mjs';

// Ship observations, not precomputed judgments. Stable IDs make source/assets drift detectable.
const cases=scenarios.map(s=>{
  const c=generateScenario(s.id);
  if(c.checks.some(x=>!x.passed)) throw new Error(`Invalid submission geometry: ${s.id}`);
  return {...s,frames:c.frames.map(({generatedAtMs,...f})=>({...f,runId:`submission-${s.id}-v1`}))};
});
const text=JSON.stringify({suite:'submission-v1',origin:'SYNTHETIC',cases})+'\n';
const output=fileURLToPath(new URL('../../../apps/pleos/driving-evaluation/src/main/resources/scenarios/submission-v1.json',import.meta.url));
if(process.argv.includes('--check')) {
  if(await readFile(output,'utf8')!==text) throw new Error('Bundled submission observations are stale; run submission.mjs');
} else { await mkdir(dirname(output),{recursive:true}); await writeFile(output,text); }
console.log(`Submission observations: ${cases.length} scenarios verified`);
