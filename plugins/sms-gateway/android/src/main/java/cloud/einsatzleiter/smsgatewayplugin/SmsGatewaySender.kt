package cloud.einsatzleiter.smsgatewayplugin

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/** Direkter Testversand, ohne Zugriff auf den Service-Zustand. */
internal object SmsGatewaySender {
        fun sendDirectSms(context: Context, to: String, text: String,
                          callback: (ok: Boolean, msg: String) -> Unit, requestCounter: AtomicInteger) {
            try {
                val smsManager = Compat.getSmsManager(context)
                val parts = smsManager.divideMessage(text) ?: arrayListOf(text)
                val action = "cloud.einsatzleiter.smsgatewayplugin.TEST_SMS_SENT"
                val partCount = parts.size
                val sentParts = AtomicInteger(0)
                val failed = AtomicInteger(0)
                val counter = requestCounter

                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val done = sentParts.incrementAndGet()
                        if (resultCode != android.app.Activity.RESULT_OK) failed.incrementAndGet()
                        if (done >= partCount) {
                            try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                            val ok = failed.get() == 0
                            callback(ok, if (ok) "OK" else "SmsManager resultCode=$resultCode")
                        }
                    }
                }
                ContextCompat.registerReceiver(
                    context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)

                val intents = ArrayList<PendingIntent>(partCount)
                repeat(partCount) {
                    intents.add(PendingIntent.getBroadcast(
                        context, counter.getAndIncrement(),
                        Intent(action).setPackage(context.packageName),
                        PendingIntent.FLAG_IMMUTABLE))
                }
                if (partCount == 1) smsManager.sendTextMessage(to, null, text, intents[0], null)
                else smsManager.sendMultipartTextMessage(to, null, parts, intents, null)
            } catch (e: Exception) {
                callback(false, e.message ?: "Unknown error")
            }
        }

}
