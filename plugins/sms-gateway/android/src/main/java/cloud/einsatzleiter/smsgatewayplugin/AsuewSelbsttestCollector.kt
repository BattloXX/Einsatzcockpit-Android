package cloud.einsatzleiter.smsgatewayplugin

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

/** Android-Zugriffe für den nativen Selbsttest. Fehler gelten als fehlende Freigabe. */
object AsuewSelbsttestCollector {
    private const val TESTED_AT_MS = "ec_asuew_alarm_tested_at_ms"

    fun confirmAlarmTest(context: Context) {
        context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE).edit()
            .putString(TESTED_AT_MS, System.currentTimeMillis().toString()).apply()
    }

    fun collect(context: Context): JSONObject {
        NativeLog.init(context)
        val testedAtMs: Long? = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            .getString(TESTED_AT_MS, null)?.toLongOrNull()
        val notifications: Boolean = read(false) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
        val online: Boolean = read(false) {
            val manager: ConnectivityManager = context.getSystemService(ConnectivityManager::class.java)
            manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
        val exact: Boolean = read(false) {
            Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        }
        val fullScreen: Boolean = read(false) {
            Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        }
        val batteryIgnored: Boolean = read(false) {
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        }
        val volume: Int? = read<Int?>(null) {
            val audio: AudioManager = context.getSystemService(AudioManager::class.java)
            val maximum: Int = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (maximum > 0) audio.getStreamVolume(AudioManager.STREAM_ALARM) * 100 / maximum else null
        }
        return AsuewSelbsttest.build(notifications, online,
            AsuewScheduling.isAlarmToneTestValid(testedAtMs, System.currentTimeMillis()),
            exact, fullScreen, batteryIgnored, volume)
    }

    private inline fun <T> read(fallback: T, block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        NativeLog.append("ASÜW Selbsttest: ${error.javaClass.simpleName}")
        fallback
    }
}
