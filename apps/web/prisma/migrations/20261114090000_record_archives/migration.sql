ALTER TABLE "DrivingRecord" ALTER COLUMN "durationMs" TYPE BIGINT;
ALTER TABLE "DrivingRecord" ADD COLUMN "archiveParts" INTEGER,
    ADD COLUMN "archiveBytes" BIGINT, ADD COLUMN "archiveSha256" CHAR(64);
CREATE TABLE "DrivingRecordPart" (
    "installationId" UUID NOT NULL,
    "sessionId" UUID NOT NULL,
    "index" INTEGER NOT NULL,
    "sha256" CHAR(64) NOT NULL,
    "bytes" BYTEA NOT NULL,
    "createdAt" TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "DrivingRecordPart_pkey" PRIMARY KEY ("installationId", "sessionId", "index"),
    CONSTRAINT "DrivingRecordPart_installationId_fkey" FOREIGN KEY ("installationId") REFERENCES "VehicleInstallation"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "DrivingRecordPart_index_check" CHECK ("index" >= 0),
    CONSTRAINT "DrivingRecordPart_size_check" CHECK (octet_length("bytes") BETWEEN 1 AND 196608)
);
CREATE INDEX "DrivingRecordPart_createdAt_idx" ON "DrivingRecordPart"("createdAt");
