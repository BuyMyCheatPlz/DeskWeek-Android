package com.deskweek.android.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/** Compatibility entry for jobs persisted by older builds. Offline JSON exchange retires LAN polling. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
    companion object {
        fun ensureScheduled(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork("deskweek-sync-periodic")
                cancelUniqueWork("deskweek-sync-now")
            }
        }
        fun syncNow(context: Context) = ensureScheduled(context)
    }
}
