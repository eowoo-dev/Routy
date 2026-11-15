import { NextResponse, type NextRequest } from "next/server";
import { allowedAccountOrigin, isMobileRequest } from "./server/account/origins";

export function proxy(request: NextRequest) {
  if (!allowedAccountOrigin(request)) {
    return NextResponse.json({ code: "ORIGIN_DENIED", message: "이 화면에서 다시 시도해주세요." }, {
      status: 403, headers: { "Cache-Control": "no-store", Vary: "Origin" },
    });
  }
  const mobile = isMobileRequest(request);
  const headers = new Headers({ Vary: "Origin", "Cache-Control": "no-store" });
  if (mobile) {
    headers.set("Access-Control-Allow-Origin", request.headers.get("origin")!);
    headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    headers.set("Access-Control-Expose-Headers", "Content-Disposition");
  }
  if (request.method === "OPTIONS") {
    const method = request.headers.get("access-control-request-method");
    const requestedHeaders = (request.headers.get("access-control-request-headers") ?? "")
      .split(",").map(value => value.trim().toLowerCase()).filter(Boolean);
    if (!mobile || !["GET", "POST"].includes(method ?? "") || requestedHeaders.some(value => !["content-type", "authorization"].includes(value))) {
      return new NextResponse(null, { status: 403, headers });
    }
    return new NextResponse(null, { status: 204, headers });
  }
  const result = NextResponse.next();
  headers.forEach((value, key) => result.headers.set(key, value));
  return result;
}

export const config = { matcher: ["/api/auth/:path*", "/api/account/:path*"] };
