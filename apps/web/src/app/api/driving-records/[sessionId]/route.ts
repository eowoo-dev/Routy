import { api,jsonBody,response } from "@/server/driving-records/http";
import { authenticate,detail,put } from "@/server/driving-records/service";
import { upload,uuid } from "@/server/driving-records/validation";
export const runtime="nodejs";
export const dynamic="force-dynamic";
type Context={params:Promise<{sessionId:string}>};
export function PUT(request:Request,context:Context){return api(async()=>{const installationId=await authenticate(request);const sessionId=uuid((await context.params).sessionId);const result=await put(installationId,sessionId,upload(await jsonBody(request)));return response(result.receipt,result.created?201:200);});}
export function GET(request:Request,context:Context){return api(async()=>response(await detail(await authenticate(request),uuid((await context.params).sessionId))));}
