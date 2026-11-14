import { api } from "@/server/driving-records/http";
import { authenticate } from "@/server/driving-records/service";
import { download } from "@/server/driving-records/archive";
import { uuid } from "@/server/driving-records/validation";
export const dynamic="force-dynamic";
export function GET(request:Request,context:{params:Promise<{sessionId:string}>}){return api(async()=>download(await authenticate(request),uuid((await context.params).sessionId)));}
