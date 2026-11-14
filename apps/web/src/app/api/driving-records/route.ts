import { api,response } from "@/server/driving-records/http";
import { authenticate,list } from "@/server/driving-records/service";
export const runtime="nodejs";
export const dynamic="force-dynamic";
export function GET(request:Request){return api(async()=>response(await list(await authenticate(request),new URL(request.url))));}
