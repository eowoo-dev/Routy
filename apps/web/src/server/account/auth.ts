import "server-only";
import { randomBytes, scrypt as cryptoScrypt, timingSafeEqual } from "node:crypto";
import { promisify } from "node:util";
import { db } from "@/server/db";
import { ApiProblem, digest, object } from "@/server/driving-records/validation";
const scrypt=promisify(cryptoScrypt);
const COOKIE="routy_session";
export const SESSION_MS=30*24*60*60*1000;
export function publicAccount(user:{id:string;email:string;displayName:string}) {return {id:user.id,email:user.email,displayName:user.displayName};}
export function sameOrigin(request:Request) {
  const origin=request.headers.get("origin");
  const expected=new URL(process.env.ROUTY_PUBLIC_WEB_ORIGIN??request.url).origin;
  if((origin && origin!==expected) || request.headers.get("sec-fetch-site")==="cross-site")throw new ApiProblem(403,"ORIGIN_DENIED","이 화면에서 다시 시도해주세요.");
}
export async function limited(bucket:string,identity:string,max:number,windowMs=15*60*1000) {
  await db.requestLimit.deleteMany({where:{expiresAt:{lt:new Date(Date.now()-86_400_000)}}});
  const window=Math.floor(Date.now()/windowMs),key=`${bucket}:${digest(identity)}:${window}`;
  const row=await db.requestLimit.upsert({where:{key},create:{key,hits:1,expiresAt:new Date((window+1)*windowMs)},update:{hits:{increment:1}}});
  if(row.hits>max)throw new ApiProblem(429,"TOO_MANY_ATTEMPTS","시도가 많습니다. 잠시 후 다시 시도해주세요.");
}
export function clientAddress(request:Request) {return process.env.ROUTY_TRUST_PROXY==="1" ? (request.headers.get("x-forwarded-for")?.split(",")[0].trim()??"unknown"):"local";}
export function accountInput(value:unknown,signup=false) {
  const row=object(value);
  if(typeof row.email!=="string" || row.email.length>254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(row.email.trim()))throw new ApiProblem(400,"INVALID_EMAIL","이메일 주소를 확인해주세요.");
  if(typeof row.password!=="string" || row.password.length<10 || row.password.length>128)throw new ApiProblem(400,"INVALID_PASSWORD","비밀번호는 10~128자로 입력해주세요.");
  const displayName=typeof row.displayName==="string"?row.displayName.trim():"";
  if(signup && (displayName.length<1 || displayName.length>30))throw new ApiProblem(400,"INVALID_NAME","이름은 1~30자로 입력해주세요.");
  return {email:row.email.trim().toLowerCase(),password:row.password,displayName};
}
export async function hashPassword(password:string) {
  const salt=randomBytes(16).toString("hex"),key=await scrypt(password,salt,64) as Buffer;
  return `scrypt:${salt}:${key.toString("hex")}`;
}
export async function checkPassword(password:string,stored?:string) {
  const parts=(stored??`scrypt:${"0".repeat(32)}:${"0".repeat(128)}`).split(":");
  const key=await scrypt(password,parts[1],64) as Buffer;
  return timingSafeEqual(key,Buffer.from(parts[2],"hex")) && !!stored;
}
export async function newSession(accountId:string) {
  await db.accountSession.deleteMany({where:{expiresAt:{lt:new Date()}}});
  const token=randomBytes(32).toString("hex");
  await db.accountSession.create({data:{accountId,tokenHash:digest(token),expiresAt:new Date(Date.now()+SESSION_MS)}});
  return token;
}
export function setSession(response:Response,token:string|null) {
  response.headers.append("Set-Cookie",`${COOKIE}=${token??""}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${token?SESSION_MS/1000:0}${process.env.NODE_ENV==="production"?"; Secure":""}`);
  return response;
}
export function cookieToken(request:Request) {return /(?:^|;\s*)routy_session=([a-f0-9]{64})(?:;|$)/.exec(request.headers.get("cookie")??"")?.[1];}
export async function currentAccount(request:Request) {
  const token=cookieToken(request);if(!token)return null;
  const session=await db.accountSession.findUnique({where:{tokenHash:digest(token)},include:{account:true}});
  return session && session.expiresAt.getTime()>Date.now()?session.account:null;
}
export async function requireAccount(request:Request) {
  const user=await currentAccount(request);if(!user)throw new ApiProblem(401,"LOGIN_REQUIRED","로그인 후 계속해주세요.");return user;
}
