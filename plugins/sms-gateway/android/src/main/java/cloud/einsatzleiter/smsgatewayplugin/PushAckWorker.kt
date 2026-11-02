package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context
import androidx.work.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object PushAck {
    fun request(base: String, auth: Pair<String, String>, deliveryId: String): Request =
        DeviceAccessState.withDeviceHeaders(Request.Builder().url("$base/api/v1/device/push-ack"))
            .header(auth.first, auth.second)
            .post(DiagnoseReportLogic.json(mapOf("delivery_id" to deliveryId)).toRequestBody("application/json".toMediaType()))
            .build()

    fun schedule(context: Context, base: String, deliveryId: String) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork("push-ack:$base:$deliveryId", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PushAckWorker>()
                    .setInputData(workDataOf("base" to base, "delivery_id" to deliveryId))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }.onFailure {
            NativeLog.error(context, "fcm", "push_ack_schedule", "Push-Bestätigung nicht planbar: ${it.javaClass.simpleName}")
        }
    }
}

internal class PushAckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        NativeLog.init(context)
        val base = inputData.getString("base") ?: return Result.failure()
        val deliveryId = inputData.getString("delivery_id") ?: return Result.failure()
        val currentBase = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            .getString(EinsatzLivePoller.PREF_BASE_URL, null)?.trimEnd('/')
        // Eine alte Bestätigung nie an einen neu gekoppelten Server senden.
        if (base != currentBase || DeviceAccessState.isInvalid(context)) return Result.failure()
        if (runAttemptCount >= PushAckLogic.MAX_ATTEMPTS - 1) return Result.failure()
        val auth = FcmTokenRegistration.getAuthHeader(context, base) ?: return Result.failure()
        val outcome = try {
            FcmTokenRegistration.httpClient.newCall(PushAck.request(base, auth, deliveryId)).execute().use { response ->
                DeviceAccessState.finalCode(response)?.let {
                    DeviceAccessState.markInvalid(context, it)
                }
                if (!response.isSuccessful) {
                    NativeLog.error(context, "fcm", "push_ack_http", "Push-Bestätigung HTTP ${response.code}")
                }
                PushAckLogic.outcome(response.code, runAttemptCount + 2)
            }
        } catch (e: IOException) {
            NativeLog.error(context, "fcm", "push_ack_network", "Push-Bestätigung fehlgeschlagen: ${e.javaClass.simpleName}")
            PushAckLogic.outcome(null, runAttemptCount + 2)
        } catch (e: Exception) {
            NativeLog.error(context, "fcm", "push_ack_failed", "Push-Bestätigung fehlgeschlagen: ${e.javaClass.simpleName}")
            PushAckLogic.Outcome.FAILURE
        }
        return when (outcome) {
            PushAckLogic.Outcome.SUCCESS -> {
                ReportSender.mark(context, "letzte_push_bestaetigung_at")
                Result.success()
            }
            PushAckLogic.Outcome.RETRY -> Result.retry()
            PushAckLogic.Outcome.FAILURE -> Result.failure()
        }
    }
}
