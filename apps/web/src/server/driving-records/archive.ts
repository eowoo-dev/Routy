import "server-only";
import { createHash } from "node:crypto";
import { db } from "@/server/db";
import type { Prisma } from "@/generated/prisma/client";
import { ApiProblem,canonical,digest,object,upload } from "./validation";

export const PART_BYTES=196_608;
const fail=(status:number,code:string,message:string):never=>{throw new ApiProblem(status,code,message);};
export function partIndex(text:string):number {
  if(!/^(0|[1-9][0-9]{0,8})$/.test(text))return fail(400,"INVALID_PART","올바른 조각 번호가 필요합니다.");
  return Number(text);
}
export function archiveManifest(value:unknown) {
  const v=object(value);
  if(Object.keys(v).some(k=>!["parts","bytes","sha256"].includes(k)) || !Number.isSafeInteger(v.parts) || Number(v.parts)<1 || Number(v.parts)>100_000_000 || !Number.isSafeInteger(v.bytes) || Number(v.bytes)<1 || typeof v.sha256!=="string" || !/^[a-f0-9]{64}$/.test(v.sha256))return fail(400,"INVALID_ARCHIVE","상세 평가 정보를 확인해주세요.");
  if(Math.ceil(Number(v.bytes)/PART_BYTES)!==v.parts)return fail(400,"INVALID_ARCHIVE","상세 평가 조각 수와 크기가 다릅니다.");
  return {parts:Number(v.parts),bytes:Number(v.bytes),sha256:v.sha256};
}
async function lock(tx:Prisma.TransactionClient,installationId:string,sessionId:string) {
  // Shared by append and complete: a final record can never gain extra parts.
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended(${installationId+":"+sessionId}, 0))`;
}
export async function putPart(installationId:string,sessionId:string,index:number,value:unknown) {
  const v=object(value);
  if(Object.keys(v).some(k=>!["sha256","data"].includes(k)) || typeof v.data!=="string" || typeof v.sha256!=="string" || !/^[a-f0-9]{64}$/.test(v.sha256) || v.data.length>PART_BYTES/3*4 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(v.data))return fail(400,"INVALID_PART","상세 평가 조각 형식이 잘못되었습니다.");
  const bytes=Buffer.from(v.data,"base64");
  if(!bytes.length || bytes.length>PART_BYTES || createHash("sha256").update(bytes).digest("hex")!==v.sha256)return fail(400,"PART_HASH_MISMATCH","상세 평가 조각이 손상됐습니다.");
  return db.$transaction(async tx=>{
    await lock(tx,installationId,sessionId);
    const key={installationId,sessionId,index};
    const old=await tx.drivingRecordPart.findUnique({where:{installationId_sessionId_index:key},select:{sha256:true}});
    if(old){if(old.sha256!==v.sha256)return fail(409,"PART_CONFLICT","같은 번호의 다른 조각이 있습니다.");return {index,sha256:old.sha256};}
    if(await tx.drivingRecord.findUnique({where:{installationId_sessionId:{installationId,sessionId}},select:{id:true}}))return fail(409,"RECORD_COMPLETE","완료된 기록에는 조각을 추가할 수 없습니다.");
    // Enforce contiguous upload and make resume independent of local receipt loss.
    const count=await tx.drivingRecordPart.count({where:{installationId,sessionId}});
    if(index!==count)return fail(409,"PART_ORDER","앞선 상세 평가 조각부터 전송해주세요.");
    await tx.drivingRecordPart.create({data:{...key,sha256:v.sha256 as string,bytes}});
    return {index,sha256:v.sha256};
  });
}
export async function complete(installationId:string,sessionId:string,value:unknown) {
  const v=object(value);
  if(Object.keys(v).some(k=>!["record","archive"].includes(k)))return fail(400,"INVALID_ARCHIVE","지원하지 않는 필드입니다.");
  const input=upload(v.record),archive=archiveManifest(v.archive),payloadHash=digest(canonical({record:input,archive}));
  return db.$transaction(async tx=>{
    await lock(tx,installationId,sessionId);
    const where={installationId_sessionId:{installationId,sessionId}};
    const existing=await tx.drivingRecord.findUnique({where});
    if(existing){if(existing.payloadHash!==payloadHash)return fail(409,"SESSION_CONFLICT","같은 세션의 다른 기록이 있습니다.");return {created:false,receipt:{id:existing.id,sessionId,receivedAt:existing.receivedAt.toISOString()}};}
    if(await tx.drivingRecordPart.count({where:{installationId,sessionId}})!==archive.parts)return fail(409,"ARCHIVE_INCOMPLETE","상세 평가 전송이 아직 완료되지 않았습니다.");
    const hash=createHash("sha256");let total=0;
    // One bounded row per read, even when the entire archive is hundreds of MiB.
    for(let index=0;index<archive.parts;index++){
      const part=await tx.drivingRecordPart.findUnique({where:{installationId_sessionId_index:{installationId,sessionId,index}}});
      if(!part || (index<archive.parts-1 && part.bytes.length!==PART_BYTES))return fail(409,"ARCHIVE_INCOMPLETE","상세 평가 조각을 확인해주세요.");
      hash.update(part.bytes);total+=part.bytes.length;
    }
    if(total!==archive.bytes || hash.digest("hex")!==archive.sha256)return fail(409,"ARCHIVE_HASH_MISMATCH","상세 평가 전체 무결성 확인에 실패했습니다.");
    const row=await tx.drivingRecord.create({data:{installationId,sessionId,...input,startedAt:new Date(input.startedAt),endedAt:new Date(input.endedAt),report:input.report as Prisma.InputJsonObject,payloadHash,archiveParts:archive.parts,archiveBytes:archive.bytes,archiveSha256:archive.sha256}});
    return {created:true,receipt:{id:row.id,sessionId,receivedAt:row.receivedAt.toISOString()}};
  },{timeout:120_000});
}
export async function download(installationId:string,sessionId:string) {
  const record=await db.drivingRecord.findUnique({where:{installationId_sessionId:{installationId,sessionId}},select:{archiveParts:true}});
  if(!record?.archiveParts)return fail(404,"NOT_FOUND","상세 평가가 없습니다.");
  let index=0;const count=record.archiveParts;
  const stream=new ReadableStream<Uint8Array>({async pull(controller){
    if(index>=count){controller.close();return;}
    try {
      const part=await db.drivingRecordPart.findUnique({where:{installationId_sessionId_index:{installationId,sessionId,index:index++}}});
      if(!part)throw new Error("Missing archive part");
      controller.enqueue(part.bytes);
    }catch(e){controller.error(e);}
  }});
  return new Response(stream,{headers:{"Content-Type":"application/x-ndjson","Content-Disposition":`attachment; filename="routy-${sessionId}.ndjson"`,"Cache-Control":"no-store","X-Content-Type-Options":"nosniff"}});
}
