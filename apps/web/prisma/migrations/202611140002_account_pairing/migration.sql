CREATE TABLE "Account" (
 "id" UUID PRIMARY KEY, "email" VARCHAR(254) NOT NULL UNIQUE, "displayName" VARCHAR(30) NOT NULL,
 "passwordHash" VARCHAR(256) NOT NULL, "createdAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE "AccountSession" (
 "id" UUID PRIMARY KEY, "accountId" UUID NOT NULL REFERENCES "Account"("id") ON DELETE CASCADE,
 "tokenHash" CHAR(64) NOT NULL UNIQUE, "expiresAt" TIMESTAMPTZ(3) NOT NULL, "createdAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "AccountSession_expiresAt_idx" ON "AccountSession"("expiresAt");
ALTER TABLE "VehicleInstallation" ADD COLUMN "ownerId" UUID UNIQUE REFERENCES "Account"("id") ON DELETE RESTRICT;
CREATE TABLE "VehiclePairing" (
 "id" UUID PRIMARY KEY, "installationId" UUID NOT NULL REFERENCES "VehicleInstallation"("id") ON DELETE CASCADE,
 "codeHash" CHAR(64) NOT NULL UNIQUE, "status" VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 "expiresAt" TIMESTAMPTZ(3) NOT NULL, "createdAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP, "consumedAt" TIMESTAMPTZ(3),
 CONSTRAINT "VehiclePairing_status_check" CHECK ("status" IN ('PENDING','CLAIMED','CANCELLED'))
);
CREATE INDEX "VehiclePairing_installationId_status_idx" ON "VehiclePairing"("installationId","status");
CREATE INDEX "VehiclePairing_expiresAt_idx" ON "VehiclePairing"("expiresAt");
CREATE TABLE "RequestLimit" ("key" VARCHAR(100) PRIMARY KEY,"hits" INTEGER NOT NULL,"expiresAt" TIMESTAMPTZ(3) NOT NULL);
CREATE INDEX "RequestLimit_expiresAt_idx" ON "RequestLimit"("expiresAt");
