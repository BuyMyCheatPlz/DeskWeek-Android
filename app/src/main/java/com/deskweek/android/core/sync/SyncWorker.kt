package com.deskweek.android.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 后台补同步。
 *
 * 诚实的边界（协议文档 §5）：WorkManager 周期任务最小 15 分钟且不精确，
 * Doze / 强停 / 权限拒绝时不会执行，恢复后补齐。这里只保证「有机会时补一次」。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("deskweek_sync", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return Result.success()
        val host = prefs.getString("host", null) ?: return Result.success()
        val pin = prefs.getString("pin", null) ?: return Result.success()
        val port = prefs.getInt("port", SyncSession.P0_PORT)

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val dir = File(applicationContext.filesDir, "sync")
                val store = SyncStore(dir)
                val identity = SyncIdentity.loadOrCreate(dir)
                val trust = TrustStore(dir)
                if (trust.all().isEmpty()) return@withContext Result.success()
                SyncSession(host, port, pin, identity, store, trust).run()
                Result.success()
            }.getOrElse {
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        }
    }

    companion object {
        private const val PERIODIC_NAME = "deskweek-sync-periodic"
        private const val IMMEDIATE_NAME = "deskweek-sync-now"

        fun ensureScheduled(context: Context) {
            if (!context.getSharedPreferences("deskweek_sync", Context.MODE_PRIVATE).getBoolean("enabled", false)) {
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
