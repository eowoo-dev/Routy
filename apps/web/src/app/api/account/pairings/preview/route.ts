import { api,jsonBody,response } from "@/server/driving-records/http";
import { object } from "@/server/driving-records/validation";
import { requireAccount,sameOrigin,limited } from "@/server/account/auth";
import { preview,pairingCode } from "@/server/account/pairing";
export function POST(request:Request){return api(async()=>{sameOrigin(request);const user=await requireAccount(request);await limited("preview",user.id,15);return response(await preview(pairingCode(object(await jsonBody(request,4096)).code)));});}
