import { api,response } from "@/server/driving-records/http";
import { authenticate } from "@/server/driving-records/service";
import { limited } from "@/server/account/auth";
import { createPairing } from "@/server/account/pairing";
export function POST(request:Request){return api(async()=>{const id=await authenticate(request);await limited("issue-code",id,10);return response(await createPairing(id),201);});}
