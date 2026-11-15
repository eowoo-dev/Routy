import { test } from "node:test";
import assert from "node:assert/strict";
import { createAccountClient, type SessionStore } from "../src/lib/api/accountClient";

const token = "a".repeat(64), newer = "b".repeat(64);
function fixture(native = true, initial: string | null = null) {
  let saved = initial;
  const calls: { url: string; init: RequestInit }[] = [];
  const store: SessionStore = { async get() { return saved; }, async set(value) { saved = value; }, async remove() { saved = null; } };
  let handler = async () => Response.json({ user: { id: "account" }, accessToken: token });
  const options = { native: () => native, store, url: (path: string) => `https://routy.eowoo.org/api/${path}`,
    fetch: (async (url, init) => { calls.push({ url: String(url), init: init! }); return handler(); }) as typeof fetch };
  return { client: createAccountClient(options), options, calls, saved: () => saved, handler: (next: typeof handler) => { handler = next; } };
}
test("browser uses HttpOnly cookies without touching native secure storage", async () => {
  const f = fixture(false);
  f.options.store.get = async () => { throw Error("must not read native credentials"); };
  await f.client.request("auth/login", { email: "me@example.test", password: "password" });
  assert.equal(f.calls[0].init.credentials, "include");
  assert.equal(new Headers(f.calls[0].init.headers).get("authorization"), null);
  assert.equal(f.saved(), null);
});
test("native login persists token, hides it from UI and restores it on a new client", async () => {
  const f = fixture();
  const result = await f.client.request<Record<string, unknown>>("auth/login", {});
  assert.equal(f.saved(), token); assert.equal(result.accessToken, undefined);
  await createAccountClient(f.options).request("account/vehicle");
  assert.equal(f.calls[1].init.credentials, "omit");
  assert.equal(new Headers(f.calls[1].init.headers).get("authorization"), `Bearer ${token}`);
  assert.equal(f.calls[1].init.redirect, "error");
});
test("native logout clears only after server revocation succeeds", async () => {
  const f = fixture(true, token);
  f.handler(async () => { throw Error("offline"); });
  await assert.rejects(f.client.request("auth/logout", {})); assert.equal(f.saved(), token);
  f.handler(async () => Response.json({ ok: true }));
  await f.client.request("auth/logout", {}); assert.equal(f.saved(), null);
});
test("expired and revoked sessions clear secure storage, transient failures retain it", async () => {
  const f = fixture(true, token);
  f.handler(async () => Response.json({ message: "unavailable" }, { status: 503 }));
  await assert.rejects(f.client.request("account/vehicle")); assert.equal(f.saved(), token);
  f.handler(async () => Response.json({ message: "login required" }, { status: 401 }));
  await assert.rejects(f.client.request("account/vehicle")); assert.equal(f.saved(), null);
  await f.options.store.set(token);
  f.handler(async () => Response.json({ user: null }));
  await f.client.request("auth/session"); assert.equal(f.saved(), null);
});
test("a stale unauthorized response cannot erase a newly established session", async () => {
  const f = fixture(true, token);
  f.handler(async () => { await f.options.store.set(newer); return Response.json({}, { status: 401 }); });
  await assert.rejects(f.client.request("account/vehicle")); assert.equal(f.saved(), newer);
});
test("secure storage failure revokes issued token without exposing credentials in URL", async () => {
  const f = fixture();
  f.options.store.set = async () => { throw Error("keystore locked"); };
  await assert.rejects(f.client.request("auth/register", {}), /안전하게 저장/);
  assert.equal(f.calls[1].url, "https://routy.eowoo.org/api/auth/logout");
  assert.equal(new Headers(f.calls[1].init.headers).get("authorization"), `Bearer ${token}`);
  assert.ok(f.calls.every(call => !call.url.includes(token)));
});
test("native login fails closed against old server or insecure API configuration", async () => {
  const f = fixture(); f.handler(async () => Response.json({ user: {} }));
  await assert.rejects(f.client.request("auth/login", {}), /서버를 업데이트/); assert.equal(f.saved(), null);
  f.options.url = () => "http://example.test/api/auth/login";
  await assert.rejects(createAccountClient(f.options).request("auth/login", {}), /HTTPS/);
  assert.equal(f.calls.length, 1);
});
test("account deletion clears native credentials only when deletion succeeds", async () => {
  const f = fixture(true, token);
  f.handler(async () => Response.json({ message: "wrong password" }, { status: 403 }));
  await assert.rejects(f.client.request("account/delete", { password: "wrong", confirm: true }));
  assert.equal(f.saved(), token);
  f.handler(async () => Response.json({ deleted: true }));
  await f.client.request("account/delete", { password: "correct", confirm: true });
  assert.equal(f.saved(), null);
});
