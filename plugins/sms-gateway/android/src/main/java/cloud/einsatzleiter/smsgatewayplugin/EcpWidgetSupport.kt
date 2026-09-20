package cloud.einsatzleiter.smsgatewayplugin

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

/** Shared deep-link and state helpers for the ECP home-screen widgets. */
object EcpWidgetSupport {
    private const val PREFS = "ec_widget_state"
    private const val KEY_ID = "incident_id"
    private const val KEY_URL = "incident_url"
    private const val KEY_ALARM = "incident_alarm"
    private const val KEY_ADDRESS = "incident_address"
    private const val KEY_PHASE = "incident_phase"

    data class IncidentWidgetState(
        val id: Long,
        val url: String,
        val alarmType: String,
        val address: String,
        val phase: String,
    )

    fun saveIncident(context: Context, state: EinsatzLiveState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_ID, state.id)
            .putString(KEY_URL, state.url)
            .putString(KEY_ALARM, state.alarmTypeCode)
            .putString(KEY_ADDRESS, state.address)
            .putString(KEY_PHASE, state.phaseLabel)
            .apply()
        updateAll(context)
    }

    fun clearIncident(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        updateAll(context)
    }

    fun incident(context: Context): IncidentWidgetState? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong(KEY_ID, -1L)
        val url = prefs.getString(KEY_URL, null)
        if (id < 0 || url.isNullOrBlank()) return null
        return IncidentWidgetState(
            id = id,
            url = url,
            alarmType = prefs.getString(KEY_ALARM, "Einsatz") ?: "Einsatz",
            address = prefs.getString(KEY_ADDRESS, "") ?: "",
            phase = prefs.getString(KEY_PHASE, "Einsatz läuft") ?: "Einsatz läuft",
        )
    }

    fun contentIntent(context: Context, path: String, requestCode: Int): PendingIntent? {
        val baseUrl = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            .getString(EinsatzLivePoller.PREF_BASE_URL, "https://einsatzcockpit.com")
            ?.trimEnd('/') ?: return null
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                data = Uri.parse("ec-widget://open/$requestCode")
                putExtra(EinsatzLiveNotifier.EXTRA_EC_URL, "$baseUrl/${path.trimStart('/')}")
            } ?: return null
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun isExpanded(options: Bundle): Boolean =
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) >= 150

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        listOf(
            EcpKontakteWidgetProvider::class.java,
            EcpObjekteWidgetProvider::class.java,
            EcpEinsatzWidgetProvider::class.java,
        ).forEach { provider ->
            val ids = manager.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isNotEmpty()) manager.notifyAppWidgetViewDataChanged(ids, android.R.id.content)
            ids.forEach { id ->
                val receiver = provider.getDeclaredConstructor().newInstance()
                receiver.onUpdate(context, manager, intArrayOf(id))
            }
        }
    }
}

abstract class EcpShortcutWidgetProvider : android.appwidget.AppWidgetProvider() {
    abstract val title: String
    abstract val route: String
    abstract val iconRes: Int
    abstract val requestCode: Int

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.ec_widget_shortcut)
            views.setTextViewText(R.id.widget_title, title)
            views.setImageViewResource(R.id.widget_icon, iconRes)
            EcpWidgetSupport.contentIntent(context, route, requestCode + id)
                ?.let { views.setOnClickPendingIntent(R.id.widget_root, it) }
            manager.updateAppWidget(id, views)
        }
    }
}

class EcpKontakteWidgetProvider : EcpShortcutWidgetProvider() {
    override val title = "Kontakte"
    override val route = "/kontakte"
    override val iconRes = R.drawable.ic_ec_widget_contacts
    override val requestCode = 8100
}

class EcpObjekteWidgetProvider : EcpShortcutWidgetProvider() {
    override val title = "Objekte"
    override val route = "/objekte/"
    override val iconRes = R.drawable.ic_ec_widget_objects
    override val requestCode = 8200
}

class EcpEinsatzWidgetProvider : android.appwidget.AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> update(context, manager, id) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) = update(context, manager, appWidgetId, newOptions)

    private fun update(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: Bundle = manager.getAppWidgetOptions(id),
    ) {
        val views = RemoteViews(context.packageName, R.layout.ec_widget_einsatz)
        val state = EcpWidgetSupport.incident(context)
        if (state == null) {
            views.setTextViewText(R.id.einsatz_label, "EINSATZCOCKPIT")
            views.setTextViewText(R.id.einsatz_title, "Kein aktiver Einsatz")
            views.setTextViewText(R.id.einsatz_detail, "Der Einsatzstatus wird automatisch aktualisiert.")
            views.setViewVisibility(R.id.einsatz_map, View.GONE)
            views.setInt(R.id.einsatz_root, "setBackgroundResource", R.drawable.ec_widget_background)
            EcpWidgetSupport.contentIntent(context, "/", 8300 + id)
                ?.let { views.setOnClickPendingIntent(R.id.einsatz_root, it) }
        } else {
            views.setTextViewText(R.id.einsatz_label, "LAUFENDER EINSATZ · ${state.phase.uppercase()}")
            views.setTextViewText(R.id.einsatz_title, state.alarmType)
            views.setTextViewText(R.id.einsatz_detail, state.address.ifBlank { "Einsatzdetails öffnen" })
            views.setViewVisibility(
                R.id.einsatz_map,
                if (EcpWidgetSupport.isExpanded(options)) View.VISIBLE else View.GONE,
            )
            views.setInt(R.id.einsatz_root, "setBackgroundResource", R.drawable.ec_widget_background_alert)
            EcpWidgetSupport.contentIntent(context, state.url, 8300 + id)
                ?.let { views.setOnClickPendingIntent(R.id.einsatz_root, it) }
        }
        manager.updateAppWidget(id, views)
    }
}
