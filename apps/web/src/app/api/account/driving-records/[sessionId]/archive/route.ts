import { api } from "@/server/driving-records/http";
import { download } from "@/server/driving-records/archive";
import { ApiProblem,uuid } from "@/server/driving-records/validation";
import { requireAccount } from "@/server/account/auth";
import { db } from "@/server/db";
export const dynamic="force-dynamic";
export function GET(request:Request,context:{params:Promise<{sessionId:string}>}){return api(async()=>{
  const user=await requireAccount(request);const vehicle=await db.vehicleInstallation.findUnique({where:{ownerId:user.id}});
  if(!vehicle)throw new ApiProblem(404,"NOT_FOUND","주행 기록이 없습니다.");
  return download(vehicle.id,uuid((await context.params).sessionId));
});}
