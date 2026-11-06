package cloud.einsatzleiter.smsgatewayplugin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object AsuewWakeScheduler {
    private const val REQUEST_CODE = 7310

    private fun operation(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, AtemschutzAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(context: Context, triggerAtWallMs: Long?) {
        if (triggerAtWallMs == null) {
            cancel(context)
            return
        }
        NativeLog.init(context)
        try {
            val manager: AlarmManager = context.getSystemService(AlarmManager::class.java)
            val canScheduleExact: Boolean = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
            val pending: PendingIntent = operation(context)
            if (AsuewScheduling.exactAlarmMode(Build.VERSION.SDK_INT, canScheduleExact) == "alarm_clock") {
                val launch: Intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?: Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${context.packageName}"))
                val showIntent: PendingIntent = PendingIntent.getActivity(context, REQUEST_CODE, launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtWallMs, showIntent), pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtWallMs, pending)
            }
        } catch (error: SecurityException) {
            NativeLog.append("ASÜW Wecker verweigert: ${error.javaClass.simpleName}")
        } catch (error: Exception) {
            NativeLog.append("ASÜW Wecker: ${error.javaClass.simpleName}")
        }
    }

    fun cancel(context: Context) {
        NativeLog.init(context)
        try {
            context.getSystemService(AlarmManager::class.java).cancel(operation(context))
        } catch (error: Exception) {
            NativeLog.append("ASÜW Wecker entfernen: ${error.javaClass.simpleName}")
        }
    }
}
