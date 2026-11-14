import { api,response } from "@/server/driving-records/http";
import { requireAccount } from "@/server/account/auth";
import { detail } from "@/server/driving-records/service";
import { ApiProblem,uuid } from "@/server/driving-records/validation";
import { db } from "@/server/db";
export const dynamic="force-dynamic";
export function GET(request:Request,context:{params:Promise<{sessionId:string}>}){return api(async()=>{const user=await requireAccount(request);const vehicle=await db.vehicleInstallation.findUnique({where:{ownerId:user.id}});if(!vehicle)throw new ApiProblem(404,"NOT_FOUND","주행 기록이 없습니다.");return response(await detail(vehicle.id,uuid((await context.params).sessionId)));});}
