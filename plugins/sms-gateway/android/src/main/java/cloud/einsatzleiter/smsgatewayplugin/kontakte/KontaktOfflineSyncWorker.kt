package cloud.einsatzleiter.smsgatewayplugin.kontakte

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
import cloud.einsatzleiter.smsgatewayplugin.EinsatzLivePoller
import java.util.concurrent.TimeUnit

/** Periodically persists the native contact feed without creating a WebView. */
class KontaktOfflineSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
        if (prefs.getString(EinsatzLivePoller.PREF_BASE_URL, null).isNullOrBlank()) {
            return Result.success()
        }
        return if (KontaktSyncEngine().syncOnce(applicationContext)) Result.success() else Result.retry()
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "kontakt-offline-sync"
        private const val IMMEDIATE_WORK_NAME = "kontakt-offline-sync-immediate"
        private const val INTERVAL_HOURS = 6L

        private fun networkConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Called from post-login hooks; account sessions do not require a device token. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<KontaktOfflineSyncWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS,
            )
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Coalesced foreground/login request, also reusable by the later contacts UI. */
        fun triggerImmediateSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<KontaktOfflineSyncWorker>()
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            val workManager = WorkManager.getInstance(context.applicationContext)
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
        }
    }
}
