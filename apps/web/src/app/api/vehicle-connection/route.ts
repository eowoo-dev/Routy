import { api,response } from "@/server/driving-records/http";
import { authenticate } from "@/server/driving-records/service";
import { connection } from "@/server/account/pairing";
export const dynamic="force-dynamic";
export function GET(request:Request){return api(async()=>response(await connection(await authenticate(request))));}
