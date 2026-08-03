package cloud.einsatzleiter.smsgatewayplugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class EinsatzLiveDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val incidentId = intent.getLongExtra(EinsatzLiveNotifier.EXTRA_INCIDENT_ID, -1L)
        if (incidentId < 0L) return
        context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            .edit()
            .putString(EinsatzLivePoller.PREF_DISMISSED_INCIDENT_ID, incidentId.toString())
            .apply()
    }
}
