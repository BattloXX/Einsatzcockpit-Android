package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Keeps the incident widget current even while the foreground live service is stopped. */
class EinsatzWidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {
    companion object {
        private const val PERIODIC_WORK_NAME = "einsatz-widget-refresh"
        private const val IMMEDIATE_WORK_NAME = "$PERIODIC_WORK_NAME-immediate"
        private const val INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val periodic = PeriodicWorkRequestBuilder<EinsatzWidgetRefreshWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic,
            )
            refreshNow(context)
        }

        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<EinsatzWidgetRefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME, ExistingWorkPolicy.REPLACE, request,
            )
        }

        fun refreshDelayed(context: Context, delaySeconds: Long) {
            val request = OneTimeWorkRequestBuilder<EinsatzWidgetRefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "$PERIODIC_WORK_NAME-delayed-$delaySeconds", ExistingWorkPolicy.REPLACE, request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).apply {
                cancelUniqueWork(PERIODIC_WORK_NAME)
                cancelUniqueWork(IMMEDIATE_WORK_NAME)
            }
        }
    }

    override fun doWork(): Result {
        return when (DutyStateFetcher.fetchAndApply(applicationContext, "Worker")) {
            DutyStateFetcher.Outcome.NO_CONFIG,
            DutyStateFetcher.Outcome.APPLIED,
            DutyStateFetcher.Outcome.AUTH_FAILED -> Result.success()
            DutyStateFetcher.Outcome.PARTIAL,
            DutyStateFetcher.Outcome.FAILED -> Result.retry()
        }
    }
}
