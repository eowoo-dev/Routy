package org.eowoo.routy.pleos

import android.app.Application
import androidx.work.*
import org.eowoo.routy.pleos.data.records.ArchiveRecoveryWorker

class RoutyApplication:Application() {
    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniqueWork("driving-archive-recovery",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ArchiveRecoveryWorker>().build())
    }
}
