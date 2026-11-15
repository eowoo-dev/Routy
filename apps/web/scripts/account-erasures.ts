import { PrismaClient } from "../src/generated/prisma/client";
import { PrismaPg } from "@prisma/adapter-pg";
import { digest } from "../src/server/driving-records/validation";
import { eraseAccount, lockInstallation } from "../src/server/account/erasure-transaction";

const db = new PrismaClient({ adapter: new PrismaPg({ connectionString: process.env.DATABASE_URL }) });
try {
  const mode = process.argv[2];
  if (mode === "export" || mode === "prune") {
    await db.accountErasure.deleteMany({ where: { expiresAt: { lte: new Date() } } });
  }
  if (mode === "export") {
    const [accounts, installations] = await Promise.all([
      db.accountErasure.findMany({ where: { expiresAt: { gt: new Date() } } }),
      db.revokedInstallation.findMany(),
    ]);
    process.stdout.write(JSON.stringify({ version: 1, accounts, installations }));
  } else if (mode === "apply") {
    let input = "";
    for await (const chunk of process.stdin) {
      input += chunk.toString(); if (Buffer.byteLength(input) > 10 * 1024 * 1024) throw Error("Erasure journal exceeds 10 MiB");
    }
    const data = JSON.parse(input);
    const hash = (value: unknown): value is string => typeof value === "string" && /^[a-f0-9]{64}$/.test(value);
    if (data.version !== 1 || !Array.isArray(data.accounts) || !Array.isArray(data.installations)
      || data.accounts.some((row: Record<string, unknown>) => !row || !hash(row.accountHash) || typeof row.deletedAt !== "string" || !Number.isFinite(Date.parse(row.deletedAt)) || typeof row.expiresAt !== "string" || !Number.isFinite(Date.parse(row.expiresAt)))
      || data.installations.some((row: Record<string, unknown>) => !row || !hash(row.installationHash))) throw Error("Invalid erasure journal");
    await db.$transaction(async tx => {
      for (const row of data.accounts) await tx.accountErasure.upsert({ where: { accountHash: row.accountHash }, create: { accountHash: row.accountHash, deletedAt: new Date(row.deletedAt), expiresAt: new Date(row.expiresAt) }, update: { deletedAt: new Date(row.deletedAt), expiresAt: new Date(row.expiresAt) } });
      for (const row of data.installations) await tx.revokedInstallation.upsert({ where: { installationHash: row.installationHash }, create: { installationHash: row.installationHash }, update: {} });
    });
    const accounts = new Set(data.accounts.map((row: { accountHash: string }) => row.accountHash));
    for (const account of await db.account.findMany({ select: { id: true } })) {
      if (accounts.has(digest(account.id))) await db.$transaction(tx => eraseAccount(tx, account.id), { timeout: 120_000 });
    }
    const installations = new Set(data.installations.map((row: { installationHash: string }) => row.installationHash));
    for (const vehicle of await db.vehicleInstallation.findMany({ select: { id: true } })) {
      if (!installations.has(digest(vehicle.id))) continue;
      await db.$transaction(async tx => {
        await lockInstallation(tx, vehicle.id);
        await tx.drivingRecordPart.deleteMany({ where: { installationId: vehicle.id } });
        await tx.drivingRecord.deleteMany({ where: { installationId: vehicle.id } });
        await tx.vehiclePairing.deleteMany({ where: { installationId: vehicle.id } });
        await tx.vehicleInstallation.deleteMany({ where: { id: vehicle.id } });
      }, { timeout: 120_000 });
    }
    await db.accountErasure.deleteMany({ where: { expiresAt: { lt: new Date() } } });
    console.log("Erasure journal applied. Deleted accounts and revoked installations remain inaccessible.");
  } else if (mode === "prune") {
    console.log("Expired account erasure entries removed.");
  } else throw Error("Use export, apply or prune");
} finally { await db.$disconnect(); }
