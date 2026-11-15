import type { Prisma } from "@/generated/prisma/client";
import { ApiProblem, digest } from "../driving-records/validation";

export async function lockAccount(tx: Prisma.TransactionClient, id: string) {
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended(${"account:" + id}, 0))`;
}
export async function lockInstallation(tx: Prisma.TransactionClient, id: string) {
  await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended(${"installation:" + id}, 0))`;
}
export async function assertActiveInstallation(tx: Prisma.TransactionClient, id: string) {
  if (await tx.revokedInstallation.findUnique({ where: { installationHash: digest(id) } }) || !await tx.vehicleInstallation.findUnique({ where: { id } })) {
    throw new ApiProblem(401, "INSTALLATION_REVOKED", "차량 인증이 해제됐습니다. 차량 앱을 초기화한 뒤 다시 연결해주세요.");
  }
}

export async function eraseAccount(tx: Prisma.TransactionClient, accountId: string) {
  await lockAccount(tx, accountId);
  const account = await tx.account.findUnique({ where: { id: accountId } });
  if (!account) return false;
  const vehicle = await tx.vehicleInstallation.findUnique({ where: { ownerId: accountId } });
  if (vehicle) {
    await lockInstallation(tx, vehicle.id);
    await tx.revokedInstallation.upsert({ where: { installationHash: digest(vehicle.id) }, create: { installationHash: digest(vehicle.id) }, update: {} });
    await tx.drivingRecordPart.deleteMany({ where: { installationId: vehicle.id } });
    await tx.drivingRecord.deleteMany({ where: { installationId: vehicle.id } });
    await tx.vehiclePairing.deleteMany({ where: { installationId: vehicle.id } });
    await tx.vehicleInstallation.delete({ where: { id: vehicle.id } });
  }
  const buckets = [["login-email", account.email], ["preview", accountId], ["claim", accountId], ["delete-account", accountId]];
  await tx.requestLimit.deleteMany({ where: { OR: buckets.map(([bucket, value]) => ({ key: { startsWith: `${bucket}:${digest(value)}:` } })) } });
  await tx.accountErasure.deleteMany({ where: { expiresAt: { lt: new Date() } } });
  await tx.accountErasure.upsert({ where: { accountHash: digest(accountId) },
    create: { accountHash: digest(accountId), expiresAt: new Date(Date.now() + 90 * 86_400_000) }, update: {} });
  await tx.account.delete({ where: { id: accountId } }); // Cascades every browser and native session.
  return true;
}
