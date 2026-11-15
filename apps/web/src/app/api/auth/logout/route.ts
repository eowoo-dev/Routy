import { api,response } from "@/server/driving-records/http";
import { digest } from "@/server/driving-records/validation";
import { db } from "@/server/db";
import { sameOrigin,sessionToken,setSession } from "@/server/account/auth";
import { isMobileRequest } from "@/server/account/origins";
export function POST(request:Request){return api(async()=>{sameOrigin(request);const token=sessionToken(request);if(token)await db.accountSession.deleteMany({where:{tokenHash:digest(token)}});const result=response({ok:true});return isMobileRequest(request)?result:setSession(result,null);});}
