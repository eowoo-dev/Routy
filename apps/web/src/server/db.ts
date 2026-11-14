import "server-only";
import { PrismaPg } from "@prisma/adapter-pg";
import { PrismaClient } from "@/generated/prisma/client";

const globalDb=globalThis as unknown as { routyDb?: PrismaClient };
export const db=globalDb.routyDb ?? new PrismaClient({adapter:new PrismaPg({connectionString:process.env.DATABASE_URL})});
if(process.env.NODE_ENV!=="production")globalDb.routyDb=db;
