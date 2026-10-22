package cloud.einsatzleiter.smsgatewayplugin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import okhttp3.Request
import okhttp3.Response

/**
 * Zustand "Gerätezugang endgültig ungültig".
 *
 * Der Server antwortet bei widerrufenem, unbekanntem/ersetztem Token oder gesperrtem
 * Benutzer mit HTTP 401 und dem Header [HEADER]. Nur dann gilt der Zugang als endgültig
 * ungültig: der gespeicherte Token wird gelöscht (ein widerrufener Token wird nie erneut
 * verwendet), alle automatischen Anfragen stoppen und der Nutzer sieht einen Hinweis mit
 * der Möglichkeit, das Gerät erneut zu verbinden. Netzwerkfehler, 5xx und 401 ohne Code
 * bleiben temporär und laufen über die bestehende Backoff-Logik weiter.
 */
object DeviceAccessState {
    const val HEADER = "X-ECP-Auth-Error"
    const val PREF_INVALID = "ec_device_access_invalid"
    const val PREF_DEVICE_TOKEN = "el_device_token"
    const val MESSAGE =
        "Der Gerätezugang ist abgelaufen oder wurde deaktiviert. Bitte das Gerät erneut verbinden."
    const val ACTION_RECONNECT = "Erneut verbinden"
    const val EXTRA_RECONNECT = "cloud.einsatzleiter.smsgatewayplugin.EXTRA_RECONNECT"

    private const val CHANNEL_ID = "ec_device_access"
    private const val NOTIFICATION_ID = 7305
    private const val REQUEST_CODE = 7305
    private val FINAL_CODES = setOf("device_revoked", "device_unknown", "user_inactive")

    private fun prefs(context: Context) =
        context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)

    /** Fehlercode, wenn die Antwort einen endgültig ungültigen Gerätezugang meldet, sonst null. */
    fun finalCode(response: Response): String? = finalCode(response.code, response.header(HEADER))

    fun finalCode(statusCode: Int, headerValue: String?): String? =
        if (statusCode == 401 && headerValue in FINAL_CODES) headerValue else null

    fun isInvalid(context: Context): Boolean = !prefs(context).getString(PREF_INVALID, null).isNullOrBlank()

    /** Merkt den ungültigen Zugang, löscht den Token und zeigt den Hinweis (idempotent). */
    fun markInvalid(context: Context, code: String) {
        val p = prefs(context)
        val first = p.getString(PREF_INVALID, null).isNullOrBlank()
        p.edit()
            .putString(PREF_INVALID, code)
            .remove(PREF_DEVICE_TOKEN)
            .putString(EinsatzLivePoller.PREF_ENABLED, "0")
            .apply()
        EcpWidgetSupport.clearIncident(context)
        EcpWidgetSupport.clearGslQueue(context)
        EcpWidgetSupport.clearGslLive(context)
        SmsGatewayService.log("Gerätezugang ungültig ($code) - automatische Anfragen gestoppt")
        if (first) notifyUser(context)
    }

    /** Nach erfolgreicher Neukopplung aufrufen (neuer Token gespeichert). */
    fun clear(context: Context) {
        prefs(context).edit().remove(PREF_INVALID).apply()
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** Hebt den Zustand auf, sobald ein neuer Geräte-Token gespeichert wurde (Neukopplung). */
    fun reconcile(context: Context) {
        if (isInvalid(context) && !prefs(context).getString(PREF_DEVICE_TOKEN, null).isNullOrBlank()) clear(context)
    }

    /** Standortfreie Gerätemerkmale für Admin-Übersicht und Diagnose (nur was bekannt ist). */
    fun withDeviceHeaders(builder: Request.Builder): Request.Builder = builder
        .header("X-ECP-Platform", "android")
        .header("X-ECP-OS-Version", Build.VERSION.RELEASE.orEmpty().take(40))
        .header("X-ECP-Device-Model", listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }
            .joinToString(" ").take(80))

    private fun notifyUser(context: Context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Gerätezugang", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Hinweis, wenn der Gerätezugang abgelaufen ist oder deaktiviert wurde"
            },
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                putExtra(EXTRA_RECONNECT, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            } ?: return
        val pending = PendingIntent.getActivity(
            context, REQUEST_CODE, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("Gerätezugang ungültig")
            .setContentText(MESSAGE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(MESSAGE))
            .setContentIntent(pending)
            .addAction(0, ACTION_RECONNECT, pending)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            SmsGatewayService.log("Hinweis Gerätezugang nicht möglich: POST_NOTIFICATIONS fehlt")
        }
    }
}
