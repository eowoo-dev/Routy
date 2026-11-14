import "server-only";
import { timingSafeEqual } from "node:crypto";
import type { DrivingRecordUpload, DrivingRecordSummary, DrivingRecordDetail } from "@routy/contracts";
import type { DrivingRecord, Prisma } from "@/generated/prisma/client";
import { db } from "@/server/db";
import { ApiProblem,canonical,digest,uuid } from "./validation";
import { archiveReviewPreview } from "./review-preview";

function equal(a:string,b:string){return timingSafeEqual(Buffer.from(a,"hex"),Buffer.from(b,"hex"));}
function duplicate(e:unknown){return !!e && typeof e==="object" && "code" in e && e.code==="P2002";}
export async function register(installationId:string,secret:string):Promise<boolean> {
  const secretHash=digest(secret);
  try{await db.vehicleInstallation.create({data:{id:installationId,secretHash}});return true;}
  catch(e){if(!duplicate(e))throw e;const existing=await db.vehicleInstallation.findUnique({where:{id:installationId}});
    if(!existing || !equal(existing.secretHash,secretHash))throw new ApiProblem(409,"INSTALLATION_CONFLICT","이미 등록된 설치 ID입니다.");return false;}
}
export async function authenticate(request:Request):Promise<string> {
  const match=/^Bearer ([a-f0-9-]{36})\.([a-f0-9]{64})$/i.exec(request.headers.get("authorization")??"");
  if(!match)throw new ApiProblem(401,"UNAUTHORIZED","차량 설치 인증이 필요합니다.");
  let id:string;try{id=uuid(match[1]);}catch{throw new ApiProblem(401,"UNAUTHORIZED","차량 설치 인증이 필요합니다.");}
  const installation=await db.vehicleInstallation.findUnique({where:{id}});
  if(!installation || !equal(installation.secretHash,digest(match[2])))throw new ApiProblem(401,"UNAUTHORIZED","차량 설치 인증에 실패했습니다.");
  return id;
}
export async function put(installationId:string,sessionId:string,input:DrivingRecordUpload) {
  const payloadHash=digest(canonical(input));
  try {
    const row=await db.drivingRecord.create({data:{installationId,sessionId,...input,startedAt:new Date(input.startedAt),endedAt:new Date(input.endedAt),report:input.report as Prisma.InputJsonObject,payloadHash}});
    return {created:true,receipt:{id:row.id,sessionId:row.sessionId,receivedAt:row.receivedAt.toISOString()}};
  }catch(e){if(!duplicate(e))throw e;
    const row=await db.drivingRecord.findUnique({where:{installationId_sessionId:{installationId,sessionId}}});
    if(!row || row.payloadHash!==payloadHash)throw new ApiProblem(409,"SESSION_CONFLICT","같은 세션 ID의 다른 기록이 이미 저장돼 있습니다.");
    return {created:false,receipt:{id:row.id,sessionId:row.sessionId,receivedAt:row.receivedAt.toISOString()}};
  }
}
const projection={id:true,sessionId:true,receivedAt:true,schemaVersion:true,origin:true,startedAt:true,endedAt:true,durationMs:true,engineVersion:true,ruleVersion:true,environmentRuleVersion:true,scenarioId:true} as const;
type SummaryRow=Pick<DrivingRecord,keyof typeof projection>;
function summary(row:SummaryRow):DrivingRecordSummary {
  return {id:row.id,sessionId:row.sessionId,receivedAt:row.receivedAt.toISOString(),schemaVersion:row.schemaVersion,
    origin:row.origin,startedAt:row.startedAt.toISOString(),endedAt:row.endedAt.toISOString(),durationMs:Number(row.durationMs),
    engineVersion:row.engineVersion,ruleVersion:row.ruleVersion,environmentRuleVersion:row.environmentRuleVersion,scenarioId:row.scenarioId};
}
export async function list(installationId:string,url:URL) {
  const limitText=url.searchParams.get("limit")??"20";
  if(!/^(?:[1-9]|[1-4][0-9]|50)$/.test(limitText))throw new ApiProblem(400,"INVALID_LIMIT","목록 크기는 1~50입니다.");
  const limit=Number(limitText),cursor=url.searchParams.get("cursor");
  if(cursor && !await db.drivingRecord.findFirst({where:{id:uuid(cursor),installationId},select:{id:true}}))throw new ApiProblem(400,"INVALID_CURSOR","이 설치의 목록 커서가 아닙니다.");
  const rows=await db.drivingRecord.findMany({where:{installationId},select:projection,orderBy:[{receivedAt:"desc"},{id:"desc"}],take:limit+1,...(cursor?{cursor:{id:cursor},skip:1}:{})});
  return {items:rows.slice(0,limit).map(summary),nextCursor:rows.length>limit?rows[limit-1].id:null};
}
export async function detail(installationId:string,sessionId:string):Promise<DrivingRecordDetail> {
  const row=await db.drivingRecord.findUnique({where:{installationId_sessionId:{installationId,sessionId}}});
  if(!row)throw new ApiProblem(404,"NOT_FOUND","주행 기록이 없습니다.");
  let report=row.report as Record<string,unknown>;
  const environment=report.environment as Record<string,unknown>|undefined;
  if(row.archiveParts && !environment?.review) {
    const count=row.archiveParts;
    async function* chunks(){for(let index=0;index<count;index++){
      const part=await db.drivingRecordPart.findUnique({where:{installationId_sessionId_index:{installationId,sessionId,index}},select:{bytes:true}});
      if(!part)throw new ApiProblem(409,"ARCHIVE_INCOMPLETE","상세 평가를 불러오지 못했습니다. 다시 시도해주세요.");
      yield part.bytes;
    }}
    const restored=await archiveReviewPreview(chunks());
    if(restored.review.groups.length>0)report={...report,environment:{...environment,review:restored.review},reviewPreview:restored.preview};
  }
  return {...summary(row),report,...(row.archiveParts!=null && row.archiveSha256!=null?{archive:{parts:row.archiveParts,bytes:Number(row.archiveBytes),sha256:row.archiveSha256}}:{})};
}
