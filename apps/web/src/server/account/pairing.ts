import "server-only";
import { randomInt } from "node:crypto";
import { db } from "@/server/db";
import { ApiProblem,digest } from "@/server/driving-records/validation";
import { assertActiveInstallation, lockAccount, lockInstallation } from "./erasure-transaction";
const alphabet="ABCDEFGHJKMNPQRSTUVWXYZ23456789";
export function pairingCode(value:unknown) {
  if(typeof value!=="string")throw new ApiProblem(400,"INVALID_CODE","차량 화면의 8자리 코드를 입력해주세요.");
  const code=value.replace(/[\s-]/g,"").toUpperCase();
  if(code.length!==8 || [...code].some(c=>!alphabet.includes(c)))throw new ApiProblem(400,"INVALID_CODE","차량 화면의 8자리 코드를 확인해주세요.");
  return code;
}
function origin() {
  const configured=process.env.ROUTY_PUBLIC_WEB_ORIGIN??(process.env.NODE_ENV!=="production"?"http://localhost:3000":"");
  const url=new URL(configured);
  if((url.protocol!=="https:" && !(process.env.NODE_ENV!=="production" && url.protocol==="http:")) || url.username || url.password || url.pathname!=="/" || url.search || url.hash)throw new Error("ROUTY_PUBLIC_WEB_ORIGIN must be a web origin");
  return url.origin;
}
export async function connection(installationId:string) {
  const vehicle=await db.vehicleInstallation.findUniqueOrThrow({where:{id:installationId},include:{owner:{select:{displayName:true}}}});
  return {status:vehicle.owner?"connected":"unlinked",ownerName:vehicle.owner?.displayName??null};
}
export async function createPairing(installationId:string) {
  const webOrigin=origin();
  const code=Array.from({length:8},()=>alphabet[randomInt(alphabet.length)]).join("");
  const expiresAt=new Date(Date.now()+300_000);
  const pairing=await db.$transaction(async tx=>{
    await lockInstallation(tx,installationId);await assertActiveInstallation(tx,installationId);
    const installation=await tx.vehicleInstallation.findUniqueOrThrow({where:{id:installationId}});
    if(installation.ownerId)throw new ApiProblem(409,"ALREADY_LINKED","이미 계정에 연결된 차량입니다.");
    await tx.vehiclePairing.updateMany({where:{installationId,status:"PENDING"},data:{status:"CANCELLED"}});
    return tx.vehiclePairing.create({data:{installationId,codeHash:digest(code),expiresAt}});
  },{isolationLevel:"Serializable"});
  return {id:pairing.id,code,expiresAt:expiresAt.toISOString(),expiresInSeconds:300,verificationUri:`${webOrigin}/?connect=${code}`};
}
export async function preview(code:string) {
  const row=await db.vehiclePairing.findUnique({where:{codeHash:digest(code)},include:{installation:true}});
  if(!row || row.status!=="PENDING" || row.expiresAt.getTime()<=Date.now())throw new ApiProblem(410,"CODE_EXPIRED","코드가 만료되었거나 이미 사용됐습니다. 차량에서 새 코드를 발급해주세요.");
  if(row.installation.ownerId)throw new ApiProblem(409,"ALREADY_LINKED","이미 연결된 차량입니다.");
  return {vehicleLabel:`차량 ${row.installationId.slice(-4).toUpperCase()}`,expiresAt:row.expiresAt.toISOString()};
}
export async function claim(accountId:string,code:string) {
  for(let attempt=0;attempt<3;attempt++){
    try {return await db.$transaction(async tx=>{
      await lockAccount(tx,accountId);
      if(!await tx.account.findUnique({where:{id:accountId}}))throw new ApiProblem(401,"LOGIN_REQUIRED","로그인 후 계속해주세요.");
      const row=await tx.vehiclePairing.findUnique({where:{codeHash:digest(code)}});
      if(!row || row.status!=="PENDING" || row.expiresAt.getTime()<=Date.now())throw new ApiProblem(410,"CODE_EXPIRED","코드가 만료되었거나 이미 사용됐습니다.");
      await lockInstallation(tx,row.installationId);await assertActiveInstallation(tx,row.installationId);
      if(await tx.vehicleInstallation.findUnique({where:{ownerId:accountId}}))throw new ApiProblem(409,"ACCOUNT_ALREADY_LINKED","MVP에서는 계정당 차량 한 대를 연결할 수 있습니다.");
      const consumed=await tx.vehiclePairing.updateMany({where:{id:row.id,status:"PENDING",expiresAt:{gt:new Date()}},data:{status:"CLAIMED",consumedAt:new Date()}});
      const linked=await tx.vehicleInstallation.updateMany({where:{id:row.installationId,ownerId:null},data:{ownerId:accountId}});
      if(consumed.count!==1 || linked.count!==1)throw new ApiProblem(409,"PAIRING_CONFLICT","다른 연결이 먼저 완료됐습니다. 연결 상태를 확인해주세요.");
      return {connected:true};
    },{isolationLevel:"Serializable"});
    }catch(e){if(e && typeof e==="object" && "code" in e){if(e.code==="P2034" && attempt<2)continue;if(e.code==="P2002")throw new ApiProblem(409,"PAIRING_CONFLICT","이미 연결된 계정 또는 차량입니다.");}throw e;}
  }
  throw new ApiProblem(409,"PAIRING_CONFLICT","연결 상태를 다시 확인해주세요.");
}
