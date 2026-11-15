export interface SessionStore {
  get(): Promise<string | null>;
  set(token: string): Promise<void>;
  remove(): Promise<void>;
}

export class ApiError extends Error {
  constructor(message: string, public status: number) { super(message); }
}

export function createAccountClient(options: {
  native: () => boolean;
  store: SessionStore;
  url: (path: string) => string;
  fetch: typeof fetch;
}) {
  async function clear(token: string | null) {
    if (token && await options.store.get() === token) await options.store.remove();
  }
  async function authorization(): Promise<Record<string, string>> {
    if (!options.native()) return {};
    const token = await options.store.get();
    return token ? { Authorization: `Bearer ${token}` } : {};
  }
  async function request<T>(path: string, body?: unknown): Promise<T> {
    const native = options.native();
    const url = options.url(path);
    if (native && new URL(url).protocol !== "https:") throw new Error("앱에서는 HTTPS 서버에만 연결할 수 있어요.");
    const token = native ? await options.store.get() : null;
    let response: Response;
    try {
      response = await options.fetch(url, {
        method: body === undefined ? "GET" : "POST",
        credentials: native ? "omit" : "include",
        headers: {
          ...(body === undefined ? {} : { "Content-Type": "application/json" }),
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: body === undefined ? undefined : JSON.stringify(body),
        cache: "no-store", redirect: "error", signal: AbortSignal.timeout(20000),
      });
    } catch { throw new Error("인터넷 연결을 확인한 뒤 다시 시도해주세요."); }
    const value = await response.json().catch(() => ({ message: "잠시 후 다시 시도해주세요." }));
    const signingIn = path === "auth/login" || path === "auth/register";
    if (!response.ok) {
      if (native && response.status === 401 && !signingIn) await clear(token);
      throw new ApiError(value.message ?? "연결 상태를 확인한 뒤 다시 시도해주세요.", response.status);
    }
    if (native && signingIn) {
      if (typeof value.accessToken !== "string" || !/^[a-f0-9]{64}$/.test(value.accessToken)) {
        throw new Error("앱 로그인 응답을 확인할 수 없어요. 서버를 업데이트해주세요.");
      }
      try { await options.store.set(value.accessToken); }
      catch {
        // A failed secure write must not leave an inaccessible server session behind.
        await options.fetch(options.url("auth/logout"), {
          method: "POST", credentials: "omit", redirect: "error",
          headers: { Authorization: `Bearer ${value.accessToken}`, "Content-Type": "application/json" },
          body: "{}", signal: AbortSignal.timeout(20000),
        }).catch(() => undefined);
        throw new Error("로그인을 안전하게 저장하지 못했어요. 다시 시도해주세요.");
      }
      // Credentials stay in the transport and never enter React state.
      delete value.accessToken;
    }
    if (native && (path === "auth/logout" || path === "account/delete" || (path === "auth/session" && !value.user))) await clear(token);
    return value as T;
  }
  return { request, authorization };
}
