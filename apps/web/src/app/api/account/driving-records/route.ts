import { api,response } from "@/server/driving-records/http";
import { requireAccount } from "@/server/account/auth";
import { list } from "@/server/driving-records/service";
import { db } from "@/server/db";
export const dynamic="force-dynamic";
export function GET(request:Request){return api(async()=>{const user=await requireAccount(request);const vehicle=await db.vehicleInstallation.findUnique({where:{ownerId:user.id}});return response(vehicle?await list(vehicle.id,new URL(request.url)):{items:[],nextCursor:null});});}
