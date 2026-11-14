package com.noonmaru.routy.pleos.data.records

import android.content.Context
import androidx.work.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ArchiveRecoveryWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val result=ArchiveRecovery(File(applicationContext.filesDir,"sensor-traces"),RecordSync.outbox(applicationContext)).run()
        if(result.recovered>0)RecordSync.schedule(applicationContext)
        // Corrupt evidence stays local with a diagnostic; do not spin on an unrecoverable file.
        Result.success(workDataOf("recovered" to result.recovered,"failed" to result.failed))
    }
}
