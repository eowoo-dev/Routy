// Keep these aligned with capacitor.config.ts. Never reflect arbitrary origins.
export const MOBILE_ORIGINS = new Set(["capacitor://localhost", "https://localhost"]);

export function isMobileRequest(request: Request) {
  return MOBILE_ORIGINS.has(request.headers.get("origin") ?? "");
}

export function allowedAccountOrigin(request: Request) {
  const origin = request.headers.get("origin");
  if (!origin) return request.headers.get("sec-fetch-site") !== "cross-site";
  if (MOBILE_ORIGINS.has(origin)) return true;
  const expected = new URL(process.env.ROUTY_PUBLIC_WEB_ORIGIN ?? request.url).origin;
  return origin === expected && request.headers.get("sec-fetch-site") !== "cross-site";
}
