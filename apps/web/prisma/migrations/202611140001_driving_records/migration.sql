CREATE TYPE "DrivingDataOrigin" AS ENUM ('LIVE', 'SYNTHETIC');
CREATE TABLE "VehicleInstallation" (
 "id" UUID PRIMARY KEY, "secretHash" CHAR(64) NOT NULL, "createdAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE "DrivingRecord" (
 "id" UUID PRIMARY KEY, "installationId" UUID NOT NULL, "sessionId" UUID NOT NULL,
 "schemaVersion" INTEGER NOT NULL, "origin" "DrivingDataOrigin" NOT NULL,
 "startedAt" TIMESTAMPTZ(3) NOT NULL, "endedAt" TIMESTAMPTZ(3) NOT NULL, "durationMs" INTEGER NOT NULL,
 "engineVersion" VARCHAR(32) NOT NULL, "ruleVersion" VARCHAR(64) NOT NULL,
 "environmentRuleVersion" VARCHAR(64), "scenarioId" VARCHAR(64), "report" JSONB NOT NULL,
 "payloadHash" CHAR(64) NOT NULL, "receivedAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT "DrivingRecord_installationId_fkey" FOREIGN KEY ("installationId") REFERENCES "VehicleInstallation"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
 CONSTRAINT "DrivingRecord_duration_check" CHECK ("durationMs" BETWEEN 1 AND 86400000 AND "endedAt">"startedAt" AND "schemaVersion"=1)
);
CREATE UNIQUE INDEX "DrivingRecord_installationId_sessionId_key" ON "DrivingRecord"("installationId","sessionId");
CREATE INDEX "DrivingRecord_installationId_receivedAt_id_idx" ON "DrivingRecord"("installationId","receivedAt" DESC,"id" DESC);
