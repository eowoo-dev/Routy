export type View = "home" | "records" | "connect";
export const viewPaths: Record<View, string> = { home: "/", records: "/records", connect: "/connect" };
export const validSessionId = (value: string) => /^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i.test(value);
export const connectionCode = (value: string) => value.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 8);
export const connectionHref = (code: string) => code ? `/connect?${new URLSearchParams({ code: connectionCode(code) })}` : "/connect";
export const recordHref = (sessionId: string) => `/records?${new URLSearchParams({ session: sessionId })}`;

// Only return to screens owned by this app; never pass arbitrary URLs to the router.
export function safeDestination(value: string | null): string {
  if (!value || !value.startsWith("/") || value.startsWith("//") || value.includes("\\")) return "/";
  try {
    const url = new URL(value, "https://routy.invalid");
    if (url.origin !== "https://routy.invalid" || !Object.values(viewPaths).includes(url.pathname)) return "/";
    if (url.pathname === "/records") {
      const session = url.searchParams.get("session");
      return session && validSessionId(session) ? recordHref(session) : "/records";
    }
    const code = url.searchParams.get(url.pathname === "/" ? "connect" : "code");
    if (url.pathname === "/connect" || code) return connectionHref(connectionCode(code ?? ""));
    return "/";
  } catch { return "/"; }
}

export function authHref(mode: "login" | "signup", destination: string) {
  const next = safeDestination(destination);
  return `/${mode}${next === "/" ? "" : `?${new URLSearchParams({ next })}`}`;
}
