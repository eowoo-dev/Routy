CREATE TABLE "RevokedInstallation" (
    "installationHash" CHAR(64) NOT NULL,
    CONSTRAINT "RevokedInstallation_pkey" PRIMARY KEY ("installationHash")
);
CREATE TABLE "AccountErasure" (
    "accountHash" CHAR(64) NOT NULL,
    "deletedAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expiresAt" TIMESTAMPTZ(3) NOT NULL,
    CONSTRAINT "AccountErasure_pkey" PRIMARY KEY ("accountHash")
);
CREATE INDEX "AccountErasure_expiresAt_idx" ON "AccountErasure"("expiresAt");
