package cloud.einsatzleiter.smsgatewayplugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AtemschutzAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        NativeLog.init(context)
        try {
            if (!AsuewStore.isActive(context)) return
            val refresh: Intent = Intent(context, AtemschutzWatchService::class.java)
                .setAction(AtemschutzWatchService.ACTION_REFRESH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(refresh)
            else context.startService(refresh)
        } catch (error: Exception) {
            NativeLog.append("ASÜW Weckerempfang: ${error.javaClass.simpleName}")
        }
    }
}
