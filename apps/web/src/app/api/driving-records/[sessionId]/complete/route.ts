import { api,jsonBody,response } from "@/server/driving-records/http";
import { authenticate } from "@/server/driving-records/service";
import { complete } from "@/server/driving-records/archive";
import { uuid } from "@/server/driving-records/validation";
export const runtime="nodejs";
export const dynamic="force-dynamic";
export function POST(request:Request,context:{params:Promise<{sessionId:string}>}){return api(async()=>{
  const installationId=await authenticate(request);const id=uuid((await context.params).sessionId);
  const result=await complete(installationId,id,await jsonBody(request));
  return response(result.receipt,result.created?201:200);
});}
