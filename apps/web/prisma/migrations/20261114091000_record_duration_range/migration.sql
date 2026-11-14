ALTER TABLE "DrivingRecord" DROP CONSTRAINT "DrivingRecord_duration_check";
ALTER TABLE "DrivingRecord" ADD CONSTRAINT "DrivingRecord_duration_check"
CHECK ("durationMs" BETWEEN 1 AND 9007199254740991 AND "endedAt" > "startedAt"
    AND EXTRACT(EPOCH FROM ("endedAt" - "startedAt")) * 1000 = "durationMs" AND "schemaVersion" = 1);
ALTER TABLE "DrivingRecord" ADD CONSTRAINT "DrivingRecord_archive_check"
CHECK (("archiveParts" IS NULL AND "archiveBytes" IS NULL AND "archiveSha256" IS NULL)
    OR ("archiveParts" IS NOT NULL AND "archiveBytes" IS NOT NULL AND "archiveSha256" IS NOT NULL
        AND "archiveParts" > 0 AND "archiveBytes" > 0
        AND ("archiveBytes" + 196607) / 196608 = "archiveParts"));
