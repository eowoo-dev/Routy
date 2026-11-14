import { api,response } from "@/server/driving-records/http";
import { currentAccount,publicAccount } from "@/server/account/auth";
export const dynamic="force-dynamic";
export function GET(request:Request){return api(async()=>{const user=await currentAccount(request);return response({user:user?publicAccount(user):null});});}
