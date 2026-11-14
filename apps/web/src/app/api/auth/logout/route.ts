import { api,response } from "@/server/driving-records/http";
import { digest } from "@/server/driving-records/validation";
import { db } from "@/server/db";
import { sameOrigin,cookieToken,setSession } from "@/server/account/auth";
export function POST(request:Request){return api(async()=>{sameOrigin(request);const token=cookieToken(request);if(token)await db.accountSession.deleteMany({where:{tokenHash:digest(token)}});return setSession(response({ok:true}),null);});}
