import { after,before,test } from "node:test";
import assert from "node:assert/strict";
import { createHash,randomBytes,randomUUID } from "node:crypto";
import { request as httpRequest } from "node:http";
import { PrismaClient } from "../src/generated/prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";
const db=new PrismaClient({adapter:new PrismaPg({connectionString:process.env.DATABASE_URL})});
const base=process.env.RECORDS_TEST_API??"http://127.0.0.1:3000";
const ids=[randomUUID(),randomUUID()],secret=randomBytes(32).toString("hex");
const headers=(i=0)=>({Authorization:`Bearer ${ids[i]}.${secret}`,"Content-Type":"application/json"});
const send=(path:string,method="GET",value?:unknown,i=0)=>fetch(base+path,{method,headers:headers(i),body:value===undefined?undefined:JSON.stringify(value)});
const hash=(b:Uint8Array)=>createHash("sha256").update(b).digest("hex");
const partBytes=196608;
const payload=()=>({schemaVersion:1,origin:"LIVE",startedAt:"2026-11-14T00:00:00Z",endedAt:"2026-11-15T12:00:00Z",durationMs:129600000,engineVersion:"0.4.0",ruleVersion:"experimental-0.4",environmentRuleVersion:null,scenarioId:null,
  report:{engineVersion:"0.4.0",origin:"LIVE",ruleVersion:"experimental-0.4",startMs:50,endMs:129600050,assessments:[],events:[],feedback:[],segmentation:{windows:2160,details:"ARCHIVE"}}});
before(async()=>{for(const installationId of ids)assert.equal((await send("/api/vehicle-installations","POST",{installationId,secret})).status,201);});
after(async()=>{await db.drivingRecordPart.deleteMany({where:{installationId:{in:ids}}});await db.drivingRecord.deleteMany({where:{installationId:{in:ids}}});await db.vehicleInstallation.deleteMany({where:{id:{in:ids}}});await db.$disconnect();});
test("multi-MiB archive resumes after lost receipts, publishes once and streams intact",async()=>{
  const id=randomUUID(),path=`/api/driving-records/${id}`;
  const bytes=Buffer.from(Array.from({length:4000},(_,index)=>JSON.stringify({index,report:{text:"장시간 주행 평가".repeat(40)}})+"\n").join(""));
  assert.ok(bytes.length>2_097_152);
  const archive={parts:Math.ceil(bytes.length/partBytes),bytes:bytes.length,sha256:hash(bytes)};
  assert.equal((await send(path+"/complete","POST",{record:payload(),archive})).status,409);
  for(let index=0;index<archive.parts;index++){
    const part=bytes.subarray(index*partBytes,(index+1)*partBytes),body={data:part.toString("base64"),sha256:hash(part)};
    const r=await send(path+`/parts/${index}`,"PUT",body);assert.equal(r.status,200,await r.text());
    if(index===1){const repeated=await send(path+`/parts/${index}`,"PUT",body);assert.equal(repeated.status,200);}
  }
  assert.equal((await send(path)).status,404,"Staged parts must not appear as a completed trip");
  const complete=await Promise.all(Array.from({length:4},()=>send(path+"/complete","POST",{record:payload(),archive})));
  assert.equal(complete.filter(r=>r.status===201).length,1);assert.equal(complete.filter(r=>r.status===200).length,3);
  const detail=await(await send(path)).json();assert.equal(detail.durationMs,129600000);assert.deepEqual(detail.archive,archive);
  const response=await send(path+"/archive");assert.equal(response.status,200);assert.equal(response.headers.get("content-type"),"application/x-ndjson");
  const received=Buffer.from(await response.arrayBuffer());assert.equal(hash(received),archive.sha256);assert.deepEqual(received,bytes);
  assert.equal((await db.drivingRecord.count({where:{installationId:ids[0],sessionId:id}})),1);
  assert.equal((await db.drivingRecordPart.count({where:{installationId:ids[0],sessionId:id}})),archive.parts);
  assert.equal((await send(path+"/archive","GET",undefined,1)).status,404);
  assert.equal((await fetch(base+path+"/archive")).status,401);
  assert.equal((await send(path+`/parts/${archive.parts}`,"PUT",{data:"eA==",sha256:hash(Buffer.from("x"))})).status,409);
  assert.equal((await send(path+"/complete","POST",{record:{...payload(),scenarioId:"changed"},archive})).status,409);
});
test("missing, damaged, conflicting and unordered parts cannot finalize",async()=>{
  const path=`/api/driving-records/${randomUUID()}`,part=Buffer.alloc(partBytes,7),body={data:part.toString("base64"),sha256:hash(part)};
  assert.equal((await send(path+"/parts/1","PUT",body)).status,409);
  assert.equal((await send(path+"/parts/0","PUT",{...body,sha256:"0".repeat(64)})).status,400);
  const concurrent=await Promise.all([send(path+"/parts/0","PUT",body),send(path+"/parts/0","PUT",body)]);
  assert.ok(concurrent.every(r=>r.status===200));
  assert.equal((await send(path+"/parts/0","PUT",{data:"eA==",sha256:hash(Buffer.from("x"))})).status,409);
  const archive={parts:1,bytes:partBytes,sha256:"0".repeat(64)};
  assert.equal((await send(path+"/complete","POST",{record:payload(),archive})).status,409);
  assert.equal((await send(path)).status,404);
  assert.equal((await send(path+"/archive")).status,404);
  assert.equal((await send(path+"/parts/-1","PUT",body)).status,400);
  assert.equal((await send(path+"/parts/1","PUT",{data:"not-base64",sha256:body.sha256})).status,400);
  // Exercise early Content-Length rejection without racing a large body against
  // the deliberately closed connection (fetch may otherwise report ECONNRESET).
  const oversized=await new Promise<number>((resolve,reject)=>{
    const req=httpRequest(base+path+"/parts/1",{method:"PUT",headers:{...headers(),"Content-Length":"270001"}},res=>{res.resume();resolve(res.statusCode!);req.end();});
    req.on("error",reject);req.setTimeout(10000,()=>req.destroy(new Error("Header rejection timed out")));req.flushHeaders();
  });
  assert.equal(oversized,413);
});
