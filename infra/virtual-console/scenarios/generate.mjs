import { mkdir, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { scenarios, generateScenario } from './runner.mjs';

const arg=name=>{const i=process.argv.indexOf(name);return i<0?null:process.argv[i+1];};
const selected=arg('--scenario');
if(selected && !scenarios.some(s=>s.id===selected)) throw new Error('Unknown scenario: '+selected);
const cases=scenarios.filter(s=>!selected || s.id===selected).map(s=>generateScenario(s.id));
const output=arg('--output') ?? 'apps/pleos/driving-evaluation/build/environment-scenarios.json';
await mkdir(dirname(output),{recursive:true});
await writeFile(output,JSON.stringify({suite:'environment-scenarios-v1',cases},null,2));
for(const c of cases) console.log(c.id,c.checks.every(x=>x.passed)?'PASS':'FAIL',JSON.stringify(c.checks));
if(cases.some(c=>c.checks.some(x=>!x.passed))) process.exitCode=1;
