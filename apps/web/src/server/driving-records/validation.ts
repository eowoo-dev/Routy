import type { DrivingRecordUpload, VehicleInstallationRegistration } from "@routy/contracts";
import { createHash } from "node:crypto";

export class ApiProblem extends Error {
  constructor(public status:number, public code:string, message:string) { super(message); }
}
export function object(value:unknown):Record<string,unknown> {
  if(!value || typeof value!=="object" || Array.isArray(value))throw new ApiProblem(400,"INVALID_INPUT","JSON 객체가 필요합니다.");
  return value as Record<string,unknown>;
}
export function uuid(value:unknown):string {
  if(typeof value!=="string" || !/^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i.test(value))throw new ApiProblem(400,"INVALID_ID","유효한 UUID가 필요합니다.");
  return value.toLowerCase();
}
function text(value:unknown,max:number):string {
  if(typeof value!=="string" || !value.length || value.length>max)throw new ApiProblem(400,"INVALID_INPUT","문자열 범위를 확인해주세요.");
  return value;
}
function exact(value:Record<string,unknown>,keys:string[]) {
  if(Object.keys(value).some(k=>!keys.includes(k)))throw new ApiProblem(400,"INVALID_INPUT","지원하지 않는 필드입니다.");
}
export function registration(value:unknown):VehicleInstallationRegistration {
  const row=object(value);exact(row,["installationId","secret"]);
  if(typeof row.secret!=="string" || !/^[a-f0-9]{64}$/.test(row.secret))throw new ApiProblem(400,"INVALID_INPUT","설치 인증키 형식이 잘못되었습니다.");
  return {installationId:uuid(row.installationId),secret:row.secret};
}
export function digest(text:string):string { return createHash("sha256").update(text).digest("hex"); }
export function canonical(value:unknown):string {
  if(value===null || typeof value!=="object")return JSON.stringify(value);
  if(Array.isArray(value))return "["+value.map(canonical).join(",")+"]";
  const row=value as Record<string,unknown>;
  return "{"+Object.keys(row).sort().map(k=>JSON.stringify(k)+":"+canonical(row[k])).join(",")+"}";
}
export function upload(value:unknown):DrivingRecordUpload {
  const row=object(value);
  exact(row,["schemaVersion","origin","startedAt","endedAt","durationMs","engineVersion","ruleVersion","environmentRuleVersion","scenarioId","report"]);
  if(row.schemaVersion!==1 || !["LIVE","SYNTHETIC"].includes(String(row.origin)))throw new ApiProblem(400,"INVALID_INPUT","지원하지 않는 기록 형식 또는 출처입니다.");
  if(!Number.isSafeInteger(row.durationMs) || Number(row.durationMs)<1)throw new ApiProblem(400,"INVALID_DURATION","주행 시간 범위를 확인해주세요.");
  const date=(v:unknown)=>{
    const s=text(v,32);
    if(!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(s) || !Number.isFinite(Date.parse(s)))throw new ApiProblem(400,"INVALID_TIME","UTC 시각이 필요합니다.");
    const d=new Date(s);
    if(d.toISOString().slice(0,19)!==s.slice(0,19))throw new ApiProblem(400,"INVALID_TIME","유효하지 않은 날짜입니다.");
    return d.toISOString();
  };
  const startedAt=date(row.startedAt),endedAt=date(row.endedAt);
  if(Date.parse(endedAt)-Date.parse(startedAt)!==row.durationMs)throw new ApiProblem(400,"INVALID_DURATION","시작·종료 시각과 주행 시간이 다릅니다.");
  const engineVersion=text(row.engineVersion,32),ruleVersion=text(row.ruleVersion,64);
  if(!/^\d+\.\d+\.\d+$/.test(engineVersion))throw new ApiProblem(400,"INVALID_VERSION","엔진 버전을 확인해주세요.");
  const report=object(row.report);
  let nodes=0;
  const visit=(v:unknown,depth:number)=>{
    if(++nodes>50000 || depth>24)throw new ApiProblem(400,"REPORT_TOO_COMPLEX","평가 보고서 구조가 너무 큽니다.");
    if(v===null || typeof v==="boolean" || typeof v==="string")return;
    if(typeof v==="number" && Number.isFinite(v))return;
    if(Array.isArray(v)){v.forEach(x=>visit(x,depth+1));return;}
    if(v && typeof v==="object"){Object.entries(v).forEach(([k,x])=>{if(k.length>128)throw new ApiProblem(400,"INVALID_REPORT","보고서 필드가 너무 깁니다.");visit(x,depth+1);});return;}
    throw new ApiProblem(400,"INVALID_REPORT","보고서에는 유효한 JSON 값만 허용됩니다.");
  };
  visit(report,0);
  if(report.engineVersion!==engineVersion || report.origin!==row.origin || !Number.isSafeInteger(report.startMs) || !Number.isSafeInteger(report.endMs) || Number(report.startMs)<0 || Number(report.endMs)-Number(report.startMs)!==row.durationMs || !Array.isArray(report.assessments) || !Array.isArray(report.events))throw new ApiProblem(400,"REPORT_MISMATCH","평가 보고서와 주행 정보가 일치하지 않습니다.");
  if(report.ruleVersion!==ruleVersion)throw new ApiProblem(400,"REPORT_MISMATCH","평가 규칙 버전이 일치하지 않습니다.");
  return {schemaVersion:1,origin:row.origin as DrivingRecordUpload["origin"],startedAt,endedAt,durationMs:Number(row.durationMs),engineVersion,ruleVersion,
    environmentRuleVersion:row.environmentRuleVersion==null?null:text(row.environmentRuleVersion,64),scenarioId:row.scenarioId==null?null:text(row.scenarioId,64),report};
}
