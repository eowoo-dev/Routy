import { after, test } from "node:test";
import assert from "node:assert/strict";
import { createHash, randomBytes, randomUUID, scryptSync } from "node:crypto";
import { spawnSync } from "node:child_process";
import { PrismaClient } from "../src/generated/prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";

const db = new PrismaClient({ adapter: new PrismaPg({ connectionString: process.env.DATABASE_URL }) });
const base = process.env.RECORDS_TEST_API ?? "http://127.0.0.1:3000";
const users: string[] = [], vehicles: string[] = [];
const hash = (value: string) => createHash("sha256").update(value).digest("hex");
async function call(path: string, method = "GET", body?: unknown, headers: Record<string, string> = {}) {
  return fetch(`${base}/api/${path}`, { method, headers: { ...(body === undefined ? {} : { "Content-Type": "application/json" }), ...headers }, body: body === undefined ? undefined : JSON.stringify(body) });
}
const recordInput = { schemaVersion: 1, engineVersion: "0.7.0", ruleVersion: "experimental-0.4", origin: "SYNTHETIC", startedAt: "2026-11-14T00:00:00Z", endedAt: "2026-11-14T00:00:01Z", durationMs: 1000,
  report: { engineVersion: "0.7.0", ruleVersion: "experimental-0.4", origin: "SYNTHETIC", startMs: 0, endMs: 1000, assessments: [], events: [] } };
async function fixture(linked = true) {
  const password = `Routy-${randomBytes(10).toString("hex")}`, salt = randomBytes(16).toString("hex");
  const user = await db.account.create({ data: { email: `delete-${randomUUID()}@example.test`, displayName: "탈퇴 테스트", passwordHash: `scrypt:${salt}:${scryptSync(password, salt, 64).toString("hex")}` } }); users.push(user.id);
  const tokens = [randomBytes(32).toString("hex"), randomBytes(32).toString("hex")];
  for (const token of tokens) await db.accountSession.create({ data: { accountId: user.id, tokenHash: hash(token), expiresAt: new Date(Date.now() + 3600_000) } });
  const cookie = { Cookie: `routy_session=${tokens[0]}` }, vehicle = randomUUID(), secret = randomBytes(32).toString("hex"); vehicles.push(vehicle);
  const vehicleHeaders = { Authorization: `Bearer ${vehicle}.${secret}` };
  assert.equal((await call("vehicle-installations", "POST", { installationId: vehicle, secret })).status, 201);
  const issue = await call("vehicle-pairings", "POST", {}, vehicleHeaders); assert.equal(issue.status, 201); const challenge = await issue.json();
  if (linked) assert.equal((await call("account/pairings/claim", "POST", { code: challenge.code, consent: true }, cookie)).status, 200);
  const session = randomUUID();
  assert.equal((await call(`driving-records/${session}`, "PUT", recordInput, vehicleHeaders)).status, 201);
  const bytes = Buffer.from(JSON.stringify(recordInput.report) + "\n"), part = { data: bytes.toString("base64"), sha256: hash(bytes.toString()) };
  const archived = randomUUID(), staged = randomUUID();
  assert.equal((await call(`driving-records/${archived}/parts/0`, "PUT", part, vehicleHeaders)).status, 200);
  assert.equal((await call(`driving-records/${archived}/complete`, "POST", { record: recordInput, archive: { parts: 1, bytes: bytes.length, sha256: part.sha256 } }, vehicleHeaders)).status, 201);
  assert.equal((await call(`driving-records/${staged}/parts/0`, "PUT", part, vehicleHeaders)).status, 200);
  return { user, password, tokens, cookie, vehicle, secret, vehicleHeaders, challenge, session, staged, part };
}
after(async () => {
  await db.vehiclePairing.deleteMany({ where: { installationId: { in: vehicles } } });
  await db.drivingRecordPart.deleteMany({ where: { installationId: { in: vehicles } } });
  await db.drivingRecord.deleteMany({ where: { installationId: { in: vehicles } } });
  await db.vehicleInstallation.deleteMany({ where: { id: { in: vehicles } } });
  await db.account.deleteMany({ where: { id: { in: users } } });
  await db.revokedInstallation.deleteMany({ where: { installationHash: { in: vehicles.map(hash) } } });
  await db.accountErasure.deleteMany({ where: { accountHash: { in: users.map(hash) } } });
  await db.requestLimit.deleteMany({ where: { OR: users.flatMap(id => ["delete-account", "claim", "preview"].map(bucket => ({ key: { startsWith: `${bucket}:${hash(id)}:` } }))) } });
  await db.$disconnect();
});

for (const origin of [undefined, "https://localhost", "capacitor://localhost"]) test(`${origin ?? "browser"}: deletes all associated data, revokes sessions and blocks old outbox`, async () => {
  const f = await fixture();
  const headers = origin ? { Origin: origin, Authorization: `Bearer ${f.tokens[1]}` } : f.cookie;
  const r = await call("account/delete", "POST", { password: f.password, confirm: true }, headers);
  assert.equal(r.status, 200, await r.clone().text()); assert.deepEqual(await r.json(), { deleted: true });
  if (origin) { assert.equal(r.headers.get("access-control-allow-origin"), origin); assert.equal(r.headers.get("set-cookie"), null); }
  else assert.match(r.headers.get("set-cookie")!, /Max-Age=0/);
  assert.equal(await db.account.findUnique({ where: { id: f.user.id } }), null);
  assert.equal(await db.accountSession.count({ where: { accountId: f.user.id } }), 0);
  assert.equal(await db.vehicleInstallation.findUnique({ where: { id: f.vehicle } }), null);
  assert.equal(await db.drivingRecord.count({ where: { installationId: f.vehicle } }), 0);
  assert.equal(await db.drivingRecordPart.count({ where: { installationId: f.vehicle } }), 0);
  assert.equal(await db.vehiclePairing.count({ where: { installationId: f.vehicle } }), 0);
  assert.ok(await db.revokedInstallation.findUnique({ where: { installationHash: hash(f.vehicle) } }));
  const journal = await db.accountErasure.findUniqueOrThrow({ where: { accountHash: hash(f.user.id) } });
  assert.ok(journal.expiresAt.getTime() - Date.now() <= 90 * 86_400_000);
  assert.ok(!JSON.stringify(journal).includes(f.user.email)); assert.ok(!JSON.stringify(journal).includes(f.user.id));
  assert.equal((await call("account/vehicle", "GET", undefined, f.cookie)).status, 401);
  assert.equal((await call("account/vehicle", "GET", undefined, { Origin: "capacitor://localhost", Authorization: `Bearer ${f.tokens[1]}` })).status, 401);
  assert.equal((await call("vehicle-installations", "POST", { installationId: f.vehicle, secret: f.secret })).status, 401);
  assert.equal((await call(`driving-records/${f.session}`, "PUT", recordInput, f.vehicleHeaders)).status, 401);
  assert.equal((await call(`driving-records/${f.staged}/parts/0`, "PUT", f.part, f.vehicleHeaders)).status, 401);
  assert.equal((await call("account/delete", "POST", { password: f.password, confirm: true }, headers)).status, 401);
});
test("wrong password, absent confirmation, foreign origin and targeting another ID cannot delete", async () => {
  const f = await fixture(false);
  assert.equal((await call("account/delete", "POST", { password: f.password, confirm: true })).status, 401);
  assert.equal((await call("account/delete", "POST", { password: f.password, confirm: true }, { ...f.cookie, Origin: "https://untrusted.example" })).status, 403);
  assert.equal((await call("account/delete", "POST", { password: "wrong-password", confirm: true }, f.cookie)).status, 403);
  assert.equal((await call("account/delete", "POST", { password: f.password }, f.cookie)).status, 400);
  assert.equal((await call("account/delete", "POST", { password: f.password, confirm: true, accountId: randomUUID() }, f.cookie)).status, 400);
  assert.ok(await db.account.findUnique({ where: { id: f.user.id } }));
  assert.equal(await db.drivingRecord.count({ where: { installationId: f.vehicle } }), 2);
});
test("password guessing is rate limited without destroying the valid session", async () => {
  const f = await fixture(false);
  for (let i = 0; i < 5; i++) assert.equal((await call("account/delete", "POST", { password: "wrong-password", confirm: true }, f.cookie)).status, 403);
  assert.equal((await call("account/delete", "POST", { password: f.password, confirm: true }, f.cookie)).status, 429);
  assert.equal((await (await call("auth/session", "GET", undefined, f.cookie)).json()).user.id, f.user.id);
});
test("concurrent upload and erasure cannot recreate a completed or staged record", async () => {
  const f = await fixture();
  const responses = await Promise.all([
    call("account/delete", "POST", { password: f.password, confirm: true }, f.cookie),
    ...Array.from({ length: 3 }, () => call(`driving-records/${randomUUID()}`, "PUT", recordInput, f.vehicleHeaders)),
    call(`driving-records/${randomUUID()}/parts/0`, "PUT", f.part, f.vehicleHeaders),
  ]);
  assert.equal(responses[0].status, 200, await responses[0].clone().text());
  assert.ok(responses.slice(1).every(r => [200, 201, 401].includes(r.status)));
  assert.equal(await db.drivingRecord.count({ where: { installationId: f.vehicle } }), 0);
  assert.equal(await db.drivingRecordPart.count({ where: { installationId: f.vehicle } }), 0);
});
test("concurrent pairing and erasure cannot leave a vehicle attached to a deleted account", async () => {
  const f = await fixture(false);
  const [erased, claimed] = await Promise.all([
    call("account/delete", "POST", { password: f.password, confirm: true }, f.cookie),
    call("account/pairings/claim", "POST", { code: f.challenge.code, consent: true }, f.cookie),
  ]);
  assert.equal(erased.status, 200, await erased.clone().text());
  assert.ok([200, 401].includes(claimed.status), await claimed.clone().text());
  assert.equal(await db.vehicleInstallation.count({ where: { ownerId: f.user.id } }), 0);
  assert.equal(await db.accountSession.count({ where: { accountId: f.user.id } }), 0);
});
test("unlinked account deletion preserves unrelated guest vehicle records", async () => {
  const f = await fixture(false);
  assert.equal((await call("account/delete", "POST", { password: f.password, confirm: true }, f.cookie)).status, 200);
  assert.equal(await db.drivingRecord.count({ where: { installationId: f.vehicle } }), 2);
  assert.equal((await call("vehicle-connection", "GET", undefined, f.vehicleHeaders)).status, 200);
});
test("deleting one owner preserves another owner's records, sessions and vehicle", async () => {
  const first = await fixture(), other = await fixture();
  assert.equal((await call("account/delete", "POST", { password: first.password, confirm: true }, first.cookie)).status, 200);
  assert.equal((await call("account/vehicle", "GET", undefined, other.cookie)).status, 200);
  assert.equal((await call(`account/driving-records/${other.session}`, "GET", undefined, other.cookie)).status, 200);
  assert.equal(await db.drivingRecord.count({ where: { installationId: other.vehicle } }), 2);
  assert.equal(await db.accountSession.count({ where: { accountId: other.user.id } }), 2);
});
test("erasure journal removes accounts resurrected by restoring an old backup", async () => {
  const f = await fixture();
  const saved = await db.drivingRecord.findFirstOrThrow({ where: { installationId: f.vehicle } });
  assert.equal((await call("account/delete", "POST", { password: f.password, confirm: true }, f.cookie)).status, 200);
  const exported = spawnSync("pnpm", ["exec", "tsx", "scripts/account-erasures.ts", "export"], { encoding: "utf8" });
  assert.equal(exported.status, 0, exported.stderr);
  const journal = JSON.parse(exported.stdout);
  journal.accounts = journal.accounts.filter((row: { accountHash: string }) => row.accountHash === hash(f.user.id));
  journal.installations = journal.installations.filter((row: { installationHash: string }) => row.installationHash === hash(f.vehicle));
  assert.equal(journal.accounts.length, 1); assert.equal(journal.installations.length, 1);
  await db.account.create({ data: f.user });
  await db.accountSession.create({ data: { accountId: f.user.id, tokenHash: hash(f.tokens[0]), expiresAt: new Date(Date.now() + 3600_000) } });
  await db.vehicleInstallation.create({ data: { id: f.vehicle, secretHash: hash(f.secret), ownerId: f.user.id } });
  await db.drivingRecord.create({ data: { ...saved, report: recordInput.report } });
  assert.equal((await call("vehicle-connection", "GET", undefined, f.vehicleHeaders)).status, 401);
  const applied = spawnSync("pnpm", ["exec", "tsx", "scripts/account-erasures.ts", "apply"], { input: JSON.stringify(journal), encoding: "utf8" });
  assert.equal(applied.status, 0, applied.stderr);
  assert.equal(await db.account.findUnique({ where: { id: f.user.id } }), null);
  assert.equal(await db.drivingRecord.count({ where: { installationId: f.vehicle } }), 0);
  assert.equal((await call("account/vehicle", "GET", undefined, f.cookie)).status, 401);
});
test("scheduled pruning removes expired erasure entries and preserves unexpired entries", async () => {
  const expired = hash(randomUUID()), active = hash(randomUUID());
  try {
    await db.accountErasure.createMany({ data: [
      { accountHash: expired, deletedAt: new Date(Date.now() - 91 * 86_400_000), expiresAt: new Date(Date.now() - 86_400_000) },
      { accountHash: active, expiresAt: new Date(Date.now() + 86_400_000) },
    ] });
    const pruned = spawnSync("pnpm", ["exec", "tsx", "scripts/account-erasures.ts", "prune"], { encoding: "utf8" });
    assert.equal(pruned.status, 0, pruned.stderr);
    assert.equal(await db.accountErasure.findUnique({ where: { accountHash: expired } }), null);
    assert.ok(await db.accountErasure.findUnique({ where: { accountHash: active } }));
  } finally { await db.accountErasure.deleteMany({ where: { accountHash: { in: [expired, active] } } }); }
});

test("public privacy and deletion URLs work without login and expose clear contact and scope", async () => {
  for (const path of ["/privacy", "/account/delete"]) {
    const r = await fetch(base + path); assert.equal(r.status, 200);
    const html = await r.text(); assert.ok(html.includes("회원 탈퇴")); assert.ok(html.includes("yt4307@gmail.com"));
    assert.ok(!r.headers.has("set-cookie"));
  }
  const html = await (await fetch(base + "/privacy")).text();
  for (const text of ["정연한", "대한민국", "90", "365", "Cloudflare", 'href="/account/delete"']) assert.ok(html.includes(text), text);
});
