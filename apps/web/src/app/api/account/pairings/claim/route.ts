import { api,jsonBody,response } from "@/server/driving-records/http";
import { object,ApiProblem } from "@/server/driving-records/validation";
import { requireAccount,sameOrigin,limited } from "@/server/account/auth";
import { claim,pairingCode } from "@/server/account/pairing";
export function POST(request:Request){return api(async()=>{sameOrigin(request);const user=await requireAccount(request);await limited("claim",user.id,15);const body=object(await jsonBody(request,4096));if(body.consent!==true)throw new ApiProblem(400,"CONSENT_REQUIRED","연결할 차량과 계정을 확인해주세요.");return response(await claim(user.id,pairingCode(body.code)));});}
