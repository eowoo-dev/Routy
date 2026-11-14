import { api,jsonBody,response } from "@/server/driving-records/http";
import { registration } from "@/server/driving-records/validation";
import { register } from "@/server/driving-records/service";
export const runtime="nodejs";
export const dynamic="force-dynamic";
export function POST(request:Request){return api(async()=>{const value=registration(await jsonBody(request,4096));const created=await register(value.installationId,value.secret);return response({installationId:value.installationId},created?201:200);});}
