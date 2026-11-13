import { NextResponse } from "next/server";
import { getHealth } from "@/server/health";

export const dynamic = "force-dynamic";

export function GET() {
  return NextResponse.json(getHealth(), {
    headers: { "Cache-Control": "no-store" },
  });
}
