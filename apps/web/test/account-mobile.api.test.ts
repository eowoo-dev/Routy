import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { PrismaClient } from "../src/generated/prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";

const db = new PrismaClient({ adapter: new PrismaPg({ connectionString: process.env.DATABASE_URL }) });
const base = process.env.RECORDS_TEST_API ?? "http://127.0.0.1:3000";
const password = `Routy-${randomBytes(12).toString("hex")}`;
const origins = ["https://localhost", "capacitor://localhost"];
const users: { id: string; email: string; token: string; origin: string; vehicle: string; secret: string }[] = [];
const hash = (value: string) => createHash("sha256").update(value).digest("hex");
async function call(path: string, method = "GET", body?: unknown, headers: Record<string, string> = {}) {
  return fetch(`${base}/api/${path}`, {
    method, headers: { ...(body === undefined ? {} : { "Content-Type": "application/json" }), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}
function mobile(user: typeof users[number], token = user.token) {
  return { Origin: user.origin, "Sec-Fetch-Site": "cross-site", Authorization: `Bearer ${token}` };
}
before(async () => {
  for (const origin of origins) {
    const email = `native-${randomUUID()}@example.test`;
    const r = await call("auth/register", "POST", { email, password, displayName: "앱 연습자" }, { Origin: origin, "Sec-Fetch-Site": "cross-site" });
    assert.equal(r.status, 201, await r.clone().text());
    const data = await r.json();
    users.push({ id: data.user.id, email, token: data.accessToken, origin, vehicle: randomUUID(), secret: randomBytes(32).toString("hex") });
    assert.equal(r.headers.get("access-control-allow-origin"), origin);
    assert.equal(r.headers.get("set-cookie"), null);
    assert.match(data.accessToken, /^[a-f0-9]{64}$/);
    assert.ok(Date.parse(data.expiresAt) > Date.now());
    assert.equal((await db.accountSession.findUnique({ where: { tokenHash: hash(data.accessToken) } }))?.accountId, data.user.id);
    const user = users.at(-1)!;
    assert.equal((await call("vehicle-installations", "POST", { installationId: user.vehicle, secret: user.secret })).status, 201);
  }
});
after(async () => {
  const vehicles = users.map(user => user.vehicle);
  await db.vehiclePairing.deleteMany({ where: { installationId: { in: vehicles } } });
  await db.drivingRecordPart.deleteMany({ where: { installationId: { in: vehicles } } });
  await db.drivingRecord.deleteMany({ where: { installationId: { in: vehicles } } });
  await db.vehicleInstallation.deleteMany({ where: { id: { in: vehicles } } });
  await db.account.deleteMany({ where: { id: { in: users.map(user => user.id) } } });
  await db.$disconnect();
});

for (const origin of origins) test(`${origin}: preflight, sign in, restore, pair, read records and revoke`, async () => {
  const user = users.find(row => row.origin === origin)!;
  for (const path of ["auth/login", "auth/session", "account/pairings/preview", "account/pairings/claim", "account/vehicle", "account/driving-records"]) {
    const r = await call(path, "OPTIONS", undefined, { Origin: origin, "Access-Control-Request-Method": path.includes("login") || path.includes("pairings") ? "POST" : "GET", "Access-Control-Request-Headers": "authorization, content-type" });
    assert.equal(r.status, 204); assert.equal(r.headers.get("access-control-allow-origin"), origin);
    assert.match(r.headers.get("vary")!, /Origin/i);
    assert.equal(r.headers.get("access-control-allow-credentials"), null);
  }
  const bad = await call("auth/login", "POST", { email: user.email, password: "wrong-password" }, { Origin: origin });
  assert.equal(bad.status, 401); assert.equal(bad.headers.get("access-control-allow-origin"), origin);
  assert.equal((await bad.json()).accessToken, undefined);
  const login = await call("auth/login", "POST", { email: user.email, password }, { Origin: origin });
  assert.equal(login.status, 200); const token = (await login.json()).accessToken;
  assert.notEqual(token, user.token);
  const me = await (await call("auth/session", "GET", undefined, mobile(user, token))).json();
  assert.equal(me.user.id, user.id); assert.equal(me.accessToken, undefined); assert.equal(me.user.passwordHash, undefined);
  const vehicleHeaders = { Authorization: `Bearer ${user.vehicle}.${user.secret}` };
  const issued = await call("vehicle-pairings", "POST", {}, vehicleHeaders);
  assert.equal(issued.status, 201); const pairing = await issued.json();
  assert.equal((await call("account/pairings/preview", "POST", { code: pairing.code }, mobile(user, token))).status, 200);
  assert.equal((await call("account/pairings/claim", "POST", { code: pairing.code }, mobile(user, token))).status, 400);
  assert.equal((await call("account/pairings/claim", "POST", { code: pairing.code, consent: true }, mobile(user, token))).status, 200);
  assert.equal((await call("account/pairings/claim", "POST", { code: pairing.code, consent: true }, mobile(user, token))).status, 410);
  assert.equal((await (await call("vehicle-connection", "GET", undefined, vehicleHeaders)).json()).status, "connected");
  assert.ok((await (await call("account/vehicle", "GET", undefined, mobile(user, token))).json()).vehicle);
  const sessionId = randomUUID();
  const payload = { schemaVersion: 1, engineVersion: "0.7.0", ruleVersion: "experimental-0.4", origin: "SYNTHETIC", startedAt: "2026-11-14T00:00:00Z", endedAt: "2026-11-14T00:00:01Z", durationMs: 1000,
    report: { engineVersion: "0.7.0", ruleVersion: "experimental-0.4", origin: "SYNTHETIC", startMs: 0, endMs: 1000, assessments: [], events: [] } };
  const archiveBytes = Buffer.from(JSON.stringify({ index: 0, report: payload.report }) + "\n");
  const sha256 = hash(archiveBytes.toString());
  assert.equal((await call(`driving-records/${sessionId}/parts/0`, "PUT", { data: archiveBytes.toString("base64"), sha256 }, vehicleHeaders)).status, 200);
  assert.equal((await call(`driving-records/${sessionId}/complete`, "POST", { record: payload, archive: { parts: 1, bytes: archiveBytes.length, sha256 } }, vehicleHeaders)).status, 201);
  assert.equal((await call(`account/driving-records/${sessionId}`, "GET", undefined, mobile(user, token))).status, 200);
  const archive = await call(`account/driving-records/${sessionId}/archive`, "GET", undefined, mobile(user, token));
  assert.equal(archive.status, 200); assert.equal(archive.headers.get("access-control-allow-origin"), origin);
  assert.match(archive.headers.get("access-control-expose-headers")!, /Content-Disposition/i);
  assert.equal(await archive.text(), archiveBytes.toString());
  assert.equal((await (await call("account/driving-records", "GET", undefined, mobile(user, token))).json()).items[0].sessionId, sessionId);
  const other = users.find(row => row.id !== user.id)!;
  assert.equal((await call(`account/driving-records/${sessionId}`, "GET", undefined, mobile(other))).status, 404);
  assert.equal((await call(`account/driving-records/${sessionId}/archive`, "GET", undefined, mobile(other))).status, 404);
  assert.equal((await call("auth/logout", "POST", {}, mobile(user, token))).status, 200);
  assert.equal((await call("account/vehicle", "GET", undefined, mobile(user, token))).status, 401);
  assert.equal(await db.accountSession.findUnique({ where: { tokenHash: hash(token) } }), null);
});

test("native origins cannot use browser cookies, including to revoke browser sessions", async () => {
  const user = users[0];
  const login = await call("auth/login", "POST", { email: user.email, password });
  assert.equal(login.status, 200); assert.equal((await login.json()).accessToken, undefined);
  const cookie = login.headers.get("set-cookie")!.split(";")[0];
  for (const origin of origins) {
    const headers = { Origin: origin, Cookie: cookie };
    assert.equal((await (await call("auth/session", "GET", undefined, headers)).json()).user, null);
    assert.equal((await call("account/vehicle", "GET", undefined, headers)).status, 401);
    assert.equal((await call("auth/logout", "POST", {}, headers)).headers.get("set-cookie"), null);
  }
  assert.equal((await (await call("auth/session", "GET", undefined, { Cookie: cookie })).json()).user.id, user.id);
  await call("auth/logout", "POST", {}, { Cookie: cookie });
});
test("untrusted, null and lookalike origins are rejected without CORS reflection", async () => {
  for (const origin of ["https://attacker.example", "null", "http://localhost", "https://localhost.attacker.example", "capacitor://localhost.attacker.example"]) {
    for (const method of ["OPTIONS", "POST", "GET"]) {
      const r = await call(method === "GET" ? "account/vehicle" : "auth/login", method, method === "POST" ? {} : undefined,
        { Origin: origin, Authorization: `Bearer ${users[0].token}`, "Access-Control-Request-Method": "POST" });
      assert.equal(r.status, 403); assert.equal(r.headers.get("access-control-allow-origin"), null);
    }
  }
});
test("preflight rejects unexpected methods and headers; cookie-free anonymous linking is denied", async () => {
  for (const origin of origins) {
    assert.equal((await call("auth/login", "OPTIONS", undefined, { Origin: origin, "Access-Control-Request-Method": "DELETE" })).status, 403);
    assert.equal((await call("auth/login", "OPTIONS", undefined, { Origin: origin, "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "x-untrusted" })).status, 403);
    const r = await call("account/pairings/claim", "POST", { code: "ABCDEFGH", consent: true }, { Origin: origin });
    assert.equal(r.status, 401); assert.equal(r.headers.get("access-control-allow-origin"), origin);
  }
});
test("expired and malformed native tokens cannot authenticate or impersonate vehicle credentials", async () => {
  const user = users[0];
  await db.accountSession.update({ where: { tokenHash: hash(user.token) }, data: { expiresAt: new Date(Date.now() - 1000) } });
  for (const token of [user.token, "invalid", `${user.vehicle}.${user.secret}`]) {
    assert.equal((await call("account/vehicle", "GET", undefined, mobile(user, token))).status, 401);
  }
  assert.equal((await (await call("auth/session", "GET", undefined, mobile(user))).json()).user, null);
  assert.equal((await call("account/vehicle", "GET", undefined, { Authorization: `Bearer ${users[1].token}` })).status, 401);
  assert.equal((await call("vehicle-connection", "GET", undefined, { Authorization: `Bearer ${users[1].token}` })).status, 401);
});
