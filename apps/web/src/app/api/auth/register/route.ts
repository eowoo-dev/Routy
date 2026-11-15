import { api,jsonBody } from "@/server/driving-records/http";
import { ApiProblem } from "@/server/driving-records/validation";
import { db } from "@/server/db";
import { sameOrigin,limited,clientAddress,accountInput,hashPassword,newSession,sessionResponse } from "@/server/account/auth";
export const runtime="nodejs";
export function POST(request:Request){return api(async()=>{
 sameOrigin(request);await limited("signup",clientAddress(request),20);
 const input=accountInput(await jsonBody(request,4096),true);
 let user;try{user=await db.account.create({data:{email:input.email,displayName:input.displayName,passwordHash:await hashPassword(input.password)}});}catch(e){if(e&&typeof e==="object"&&"code" in e&&e.code==="P2002")throw new ApiProblem(409,"EMAIL_IN_USE","이미 가입된 이메일입니다. 로그인해주세요.");throw e;}
 return sessionResponse(request,user,await newSession(user.id),201);
});}
