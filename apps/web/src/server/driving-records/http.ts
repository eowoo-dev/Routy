import "server-only";
import { ApiProblem } from "./validation";
export async function jsonBody(request:Request,maxBytes=2_097_152):Promise<unknown> {
  if(request.headers.get("content-type")?.split(";")[0].trim()!=="application/json")throw new ApiProblem(415,"JSON_REQUIRED","JSON 요청이 필요합니다.");
  if(Number(request.headers.get("content-length"))>maxBytes)throw new ApiProblem(413,"BODY_TOO_LARGE","기록 용량을 초과했습니다.");
  const reader=request.body?.getReader();if(!reader)throw new ApiProblem(400,"INVALID_JSON","요청 본문이 없습니다.");
  const chunks:Uint8Array[]=[];let size=0;
  try { while(true){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>maxBytes){await reader.cancel();throw new ApiProblem(413,"BODY_TOO_LARGE","기록 용량을 초과했습니다.");}chunks.push(value);} }
  finally {reader.releaseLock();}
  try {return JSON.parse(Buffer.concat(chunks).toString("utf8"));}catch{throw new ApiProblem(400,"INVALID_JSON","잘못된 JSON입니다.");}
}
export function response(value:unknown,status=200) {return Response.json(value,{status,headers:{"Cache-Control":"no-store",...([413,415].includes(status)?{"Connection":"close"}:{})}});}
export async function api(action:()=>Promise<Response>):Promise<Response> {
  try{return await action();}catch(e){
    if(e instanceof ApiProblem)return response({code:e.code,message:e.message},e.status);
    console.error("Driving record API unavailable",e instanceof Error?e.name:"UnknownError");
    return response({code:"TEMPORARILY_UNAVAILABLE",message:"잠시 후 다시 시도해주세요."},503);
  }
}
