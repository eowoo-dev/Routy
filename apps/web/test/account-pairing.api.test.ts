import { after,before,test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID,randomBytes } from "node:crypto";
import { PrismaClient } from "../src/generated/prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";
const db=new PrismaClient({adapter:new PrismaPg({connectionString:process.env.DATABASE_URL})});
const base=process.env.RECORDS_TEST_API??"http://127.0.0.1:3000";
const suffix=randomUUID(),password="Routy-Test-"+randomBytes(8).toString("hex");
const vehicles=[randomUUID(),randomUUID(),randomUUID()];const secret=randomBytes(32).toString("hex");
const cookies:string[]=[];const accounts:string[]=[];
async function call(path:string,method="GET",body?:unknown,cookie?:string,headers:Record<string,string>={}){return fetch(base+"/api/"+path,{method,headers:{...(body?{"Content-Type":"application/json"}:{}),...(cookie?{Cookie:cookie}:{}),...headers},body:body?JSON.stringify(body):undefined});}
function vehicleHeaders(i=0){return {Authorization:`Bearer ${vehicles[i]}.${secret}`};}
async function issue(i=0){const r=await call("vehicle-pairings","POST",{},undefined,vehicleHeaders(i));assert.equal(r.status,201,await r.clone().text());return r.json();}
before(async()=>{
 for(let i=0;i<3;i++){const r=await call("auth/register","POST",{email:`routy-${i}-${suffix}@example.test`,password,displayName:`연습자${i}`});assert.equal(r.status,201,await r.clone().text());cookies.push(r.headers.get("set-cookie")!.split(";")[0]);accounts.push((await r.json()).user.id);}
 for(const installationId of vehicles)assert.equal((await call("vehicle-installations","POST",{installationId,secret})).status,201);
});
after(async()=>{await db.vehiclePairing.deleteMany({where:{installationId:{in:vehicles}}});await db.drivingRecord.deleteMany({where:{installationId:{in:vehicles}}});await db.vehicleInstallation.deleteMany({where:{id:{in:vehicles}}});await db.account.deleteMany({where:{id:{in:accounts}}});await db.$disconnect();});
test("session cookie hides credentials, login works and logout revokes",async()=>{
 const me=await (await call("auth/session","GET",undefined,cookies[0])).json();assert.equal(me.user.displayName,"연습자0");assert.equal(me.user.passwordHash,undefined);
 const bad=await call("auth/login","POST",{email:`routy-0-${suffix}@example.test`,password:"wrong-password"});assert.equal(bad.status,401);
 const login=await call("auth/login","POST",{email:`routy-0-${suffix}@example.test`,password});assert.equal(login.status,200);const header=login.headers.get("set-cookie")!;assert.match(header,/HttpOnly/);assert.match(header,/SameSite=Lax/);
 const cookie=header.split(";")[0];assert.equal((await call("auth/logout","POST",{},cookie)).status,200);assert.equal((await (await call("auth/session","GET",undefined,cookie)).json()).user,null);
});
test("cross-origin changes, weak passwords and anonymous linking are rejected",async()=>{
 assert.equal((await call("auth/logout","POST",{},cookies[0],{Origin:"https://unrelated.example"})).status,403);
 assert.equal((await call("auth/register","POST",{email:"a@example.test",password:"short",displayName:"a"})).status,400);
 assert.equal((await call("account/pairings/preview","POST",{code:"ABCDEFGH"})).status,401);
});
test("regeneration invalidates previous code and expiry is enforced",async()=>{
 const first=await issue(2),second=await issue(2);assert.match(second.code,/^[A-Z2-9]{8}$/);assert.ok(second.verificationUri.endsWith("?connect="+second.code));assert.ok(!JSON.stringify(second).includes(secret));
 assert.equal((await call("account/pairings/preview","POST",{code:first.code},cookies[2])).status,410);
 await db.vehiclePairing.update({where:{id:second.id},data:{expiresAt:new Date(Date.now()-1000)}});
 assert.equal((await call("account/pairings/claim","POST",{code:second.code,consent:true},cookies[2])).status,410);
});
test("claim requires confirmation and exactly one account wins a race",async()=>{
 const code=await issue(0);assert.equal((await call("account/pairings/preview","POST",{code:code.code},cookies[0])).status,200);
 assert.equal((await call("account/pairings/claim","POST",{code:code.code},cookies[0])).status,400);
 const attempts=await Promise.all([0,1].map(i=>call("account/pairings/claim","POST",{code:code.code,consent:true},cookies[i])));
 assert.equal(attempts.filter(r=>r.status===200).length,1);assert.ok(attempts.some(r=>[409,410].includes(r.status)));
 const status=await (await call("vehicle-connection","GET",undefined,undefined,vehicleHeaders(0))).json();assert.equal(status.status,"connected");
 assert.equal((await call("account/pairings/claim","POST",{code:code.code,consent:true},cookies[2])).status,410);
 assert.equal((await call("vehicle-pairings","POST",{},undefined,vehicleHeaders(0))).status,409);
});
test("account reads only its linked vehicle records and cannot bind a second vehicle",async()=>{
 const owner=await db.vehicleInstallation.findUniqueOrThrow({where:{id:vehicles[0]}});const ownerIndex=accounts.indexOf(owner.ownerId!);const other=ownerIndex===0?1:0;
 const sessionId=randomUUID();const report={engineVersion:"0.7.0",ruleVersion:"experimental-0.4",origin:"SYNTHETIC",startMs:0,endMs:1000,assessments:[],events:[]};
 const payload={schemaVersion:1,engineVersion:"0.7.0",ruleVersion:"experimental-0.4",origin:"SYNTHETIC",startedAt:"2026-11-14T00:00:00Z",endedAt:"2026-11-14T00:00:01Z",durationMs:1000,report};
 assert.equal((await call(`driving-records/${sessionId}`,"PUT",payload,undefined,vehicleHeaders(0))).status,201);
 assert.equal((await call(`account/driving-records/${sessionId}`,"GET",undefined,cookies[ownerIndex])).status,200);
 assert.equal((await call(`account/driving-records/${sessionId}`,"GET",undefined,cookies[other])).status,404);
 const page=await (await call("account/driving-records","GET",undefined,cookies[ownerIndex])).json();assert.equal(page.items[0].sessionId,sessionId);
 const second=await issue(1);assert.equal((await call("account/pairings/claim","POST",{code:second.code,consent:true},cookies[ownerIndex])).status,409);
 assert.equal((await db.vehiclePairing.findUniqueOrThrow({where:{id:second.id}})).status,"PENDING");
});
