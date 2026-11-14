import { api,jsonBody,response } from "@/server/driving-records/http";
import { authenticate } from "@/server/driving-records/service";
import { partIndex,putPart } from "@/server/driving-records/archive";
import { uuid } from "@/server/driving-records/validation";
export const runtime="nodejs";
export const dynamic="force-dynamic";
export function PUT(request:Request,context:{params:Promise<{sessionId:string,index:string}>}){return api(async()=>{
  const installationId=await authenticate(request);const p=await context.params;
  return response(await putPart(installationId,uuid(p.sessionId),partIndex(p.index),await jsonBody(request,270_000)));
});}
