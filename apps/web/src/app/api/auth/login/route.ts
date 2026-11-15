import { api,jsonBody } from "@/server/driving-records/http";
import { ApiProblem } from "@/server/driving-records/validation";
import { db } from "@/server/db";
import { sameOrigin,limited,clientAddress,accountInput,checkPassword,newSession,sessionResponse } from "@/server/account/auth";
export const runtime="nodejs";
export function POST(request:Request){return api(async()=>{
 sameOrigin(request);await limited("login-ip",clientAddress(request),100);
 const input=accountInput(await jsonBody(request,4096));await limited("login-email",input.email,20);
 const user=await db.account.findUnique({where:{email:input.email}});
 if(!await checkPassword(input.password,user?.passwordHash) || !user)throw new ApiProblem(401,"INVALID_CREDENTIALS","이메일 또는 비밀번호를 확인해주세요.");
 return sessionResponse(request,user,await newSession(user.id));
});}
