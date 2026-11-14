import { api,response } from "@/server/driving-records/http";
import { requireAccount } from "@/server/account/auth";
import { db } from "@/server/db";
export const dynamic="force-dynamic";
export function GET(request:Request){return api(async()=>{const user=await requireAccount(request);const row=await db.vehicleInstallation.findUnique({where:{ownerId:user.id},select:{id:true,createdAt:true}});return response({vehicle:row?{id:row.id,label:`차량 ${row.id.slice(-4).toUpperCase()}`} :null});});}
