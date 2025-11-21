package com.acktarius.hnsgo

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Configure resolver to use external Handshake resolver
            SpvClient.setResolver(Config.DEBUG_RESOLVER_HOST, Config.DEBUG_RESOLVER_PORT)
            SpvClient.init(applicationContext.filesDir, applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<SyncWorker>(12, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(false)  // Don't require battery optimization
                        .setRequiresCharging(false)  // Don't require charging
                        .build()
                )
                .setInitialDelay(1, java.util.concurrent.TimeUnit.HOURS)  // Delay first execution
                .addTag("hns_sync")  // Add tag for better tracking
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "hns_sync", 
                ExistingPeriodicWorkPolicy.KEEP, 
                work
            )
        }
    }
}