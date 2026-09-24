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
    private const val KEY_LAT = "incident_lat"
    private const val KEY_LNG = "incident_lng"
    private const val KEY_GMAPS_URL = "incident_gmaps_url"
    private const val KEY_MELDUNG = "incident_meldung"
    private const val KEY_OBJEKT_ID = "incident_objekt_id"
    private const val KEY_OBJEKT_NAME = "incident_objekt_name"
    private const val KEY_OBJEKT_URL = "incident_objekt_url"
    private const val KEY_GSL_LAGE_ID = "gsl_lage_id"
    private const val KEY_GSL_LAGE_NAME = "gsl_lage_name"
    private const val KEY_GSL_LAGE_URL = "gsl_lage_url"
    private const val KEY_GSL_REMAINING = "gsl_remaining_count"

    private fun gslSiteKey(slot: String, field: String) = "gsl_${slot}_$field"

    data class IncidentWidgetState(
        val id: Long,
        val url: String,
        val alarmType: String,
        val address: String,
        val phase: String,
        val lat: Double?,
        val lng: Double?,
        val gmapsUrl: String?,
        val meldung: String?,
        val objektId: Long?,
        val objektName: String?,
        val objektUrl: String?,
    )

    data class GslQueueWidgetState(
        val lageId: Long,
        val lageName: String,
        val lageUrl: String,
        val current: GslSiteInfo,
        val upcoming: List<GslSiteInfo>,
        val remainingCount: Int,
    )

    fun saveIncident(context: Context, state: EinsatzLiveState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_ID, state.id)
            .putString(KEY_URL, state.url)
            .putString(KEY_ALARM, state.alarmTypeCode)
            .putString(KEY_ADDRESS, state.address)
            .putString(KEY_PHASE, state.phaseLabel)
            .putString(KEY_LAT, state.lat?.toString())
            .putString(KEY_LNG, state.lng?.toString())
            .putString(KEY_GMAPS_URL, state.gmapsUrl)
            .putString(KEY_MELDUNG, state.meldung)
            .putString(KEY_OBJEKT_ID, state.objektId?.toString())
            .putString(KEY_OBJEKT_NAME, state.objektName)
            .putString(KEY_OBJEKT_URL, state.objektUrl)
            .apply()
        updateAll(context)
    }

    fun clearIncident(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ID).remove(KEY_URL).remove(KEY_ALARM).remove(KEY_ADDRESS).remove(KEY_PHASE)
            .remove(KEY_LAT).remove(KEY_LNG).remove(KEY_GMAPS_URL).remove(KEY_MELDUNG)
            .remove(KEY_OBJEKT_ID).remove(KEY_OBJEKT_NAME).remove(KEY_OBJEKT_URL)
            .apply()
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
            lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull(),
            lng = prefs.getString(KEY_LNG, null)?.toDoubleOrNull(),
            gmapsUrl = prefs.getString(KEY_GMAPS_URL, null),
            meldung = prefs.getString(KEY_MELDUNG, null),
            objektId = prefs.getString(KEY_OBJEKT_ID, null)?.toLongOrNull(),
            objektName = prefs.getString(KEY_OBJEKT_NAME, null),
            objektUrl = prefs.getString(KEY_OBJEKT_URL, null),
        )
    }

    fun saveGslQueue(context: Context, state: GslQueueState) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_GSL_LAGE_ID, state.lageId)
            .putString(KEY_GSL_LAGE_NAME, state.lageName)
            .putString(KEY_GSL_LAGE_URL, state.lageUrl)
            .putInt(KEY_GSL_REMAINING, state.remainingCount)
        putGslSite(editor, "current", state.current)
        listOf("upcoming_0", "upcoming_1").forEachIndexed { index, slot ->
            state.upcoming.getOrNull(index)?.let { putGslSite(editor, slot, it) } ?: removeGslSite(editor, slot)
        }
        editor.apply()
        updateAll(context)
    }

    fun clearGslQueue(context: Context) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_GSL_LAGE_ID).remove(KEY_GSL_LAGE_NAME).remove(KEY_GSL_LAGE_URL)
            .remove(KEY_GSL_REMAINING)
        removeGslSite(editor, "current")
        removeGslSite(editor, "upcoming_0")
        removeGslSite(editor, "upcoming_1")
        editor.apply()
        updateAll(context)
    }

    fun gslQueue(context: Context): GslQueueWidgetState? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lageId = prefs.getLong(KEY_GSL_LAGE_ID, -1L)
        if (lageId < 0L) return null
        val current = gslSite(prefs, "current") ?: return null
        return GslQueueWidgetState(
            lageId = lageId,
            lageName = prefs.getString(KEY_GSL_LAGE_NAME, "Grossschadenslage") ?: "Grossschadenslage",
            lageUrl = prefs.getString(KEY_GSL_LAGE_URL, "/lage/$lageId") ?: "/lage/$lageId",
            current = current,
            upcoming = listOfNotNull(gslSite(prefs, "upcoming_0"), gslSite(prefs, "upcoming_1")),
            remainingCount = prefs.getInt(KEY_GSL_REMAINING, 0).coerceAtLeast(0),
        )
    }

    private fun putGslSite(editor: android.content.SharedPreferences.Editor, slot: String, site: GslSiteInfo) {
        editor.putLong(gslSiteKey(slot, "id"), site.id)
            .putString(gslSiteKey(slot, "bezeichnung"), site.bezeichnung)
            .putString(gslSiteKey(slot, "meldung"), site.meldung)
            .putString(gslSiteKey(slot, "address"), site.address)
            .putString(gslSiteKey(slot, "lat"), site.lat?.toString())
            .putString(gslSiteKey(slot, "lng"), site.lng?.toString())
            .putString(gslSiteKey(slot, "gmaps_url"), site.gmapsUrl)
            .putString(gslSiteKey(slot, "priority"), site.priority)
            .putString(gslSiteKey(slot, "phase"), site.phase)
    }

    private fun removeGslSite(editor: android.content.SharedPreferences.Editor, slot: String) {
        listOf("id", "bezeichnung", "meldung", "address", "lat", "lng", "gmaps_url", "priority", "phase")
            .forEach { editor.remove(gslSiteKey(slot, it)) }
    }

    private fun gslSite(prefs: android.content.SharedPreferences, slot: String): GslSiteInfo? {
        val id = prefs.getLong(gslSiteKey(slot, "id"), -1L)
        val bezeichnung = prefs.getString(gslSiteKey(slot, "bezeichnung"), null)
        if (id < 0L || bezeichnung.isNullOrBlank()) return null
        return GslSiteInfo(id, bezeichnung, prefs.getString(gslSiteKey(slot, "meldung"), null),
            prefs.getString(gslSiteKey(slot, "address"), "") ?: "",
            prefs.getString(gslSiteKey(slot, "lat"), null)?.toDoubleOrNull(),
            prefs.getString(gslSiteKey(slot, "lng"), null)?.toDoubleOrNull(),
            prefs.getString(gslSiteKey(slot, "gmaps_url"), null),
            prefs.getString(gslSiteKey(slot, "priority"), null),
            prefs.getString(gslSiteKey(slot, "phase"), "") ?: "")
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

    fun mapsIntent(context: Context, gmapsUrl: String, requestCode: Int): PendingIntent? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(gmapsUrl))
        return PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
        val gsl = EcpWidgetSupport.gslQueue(context)
        when {
            state == null && gsl == null -> {
            views.setTextViewText(R.id.einsatz_label, "EINSATZCOCKPIT")
            views.setTextViewText(R.id.einsatz_title, "Kein aktiver Einsatz")
            views.setTextViewText(R.id.einsatz_detail, "Der Einsatzstatus wird automatisch aktualisiert.")
            views.setViewVisibility(R.id.einsatz_map, View.GONE)
            views.setViewVisibility(R.id.einsatz_meldung, View.GONE)
            views.setViewVisibility(R.id.einsatz_actions, View.GONE)
            views.setViewVisibility(R.id.gsl_queue_container, View.GONE)
            views.setInt(R.id.einsatz_root, "setBackgroundResource", R.drawable.ec_widget_background)
            EcpWidgetSupport.contentIntent(context, "/", 8300 + id)
                ?.let { views.setOnClickPendingIntent(R.id.einsatz_root, it) }
            }
            // A GSL assignment is more urgent and information-dense than a parallel incident.
            gsl != null -> renderGsl(context, views, id, gsl)
            else -> renderIncident(context, views, id, options, state!!)
        }
        manager.updateAppWidget(id, views)
    }

    private fun renderIncident(
        context: Context,
        views: RemoteViews,
        id: Int,
        options: Bundle,
        state: EcpWidgetSupport.IncidentWidgetState,
    ) {
            views.setTextViewText(R.id.einsatz_label, "LAUFENDER EINSATZ · ${state.phase.uppercase()}")
            views.setTextViewText(R.id.einsatz_title, state.alarmType)
            views.setTextViewText(R.id.einsatz_detail, state.address.ifBlank { "Einsatzdetails öffnen" })
            views.setTextViewText(R.id.einsatz_meldung, state.meldung ?: "")
            views.setViewVisibility(
                R.id.einsatz_meldung,
                if (state.meldung != null && EcpWidgetSupport.isExpanded(options)) View.VISIBLE else View.GONE,
            )
            views.setViewVisibility(R.id.gsl_queue_container, View.GONE)
            views.setViewVisibility(R.id.einsatz_actions, View.VISIBLE)
            views.setViewVisibility(R.id.einsatz_maps_action, if (state.gmapsUrl != null) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.einsatz_object_action, View.VISIBLE)
            views.setViewVisibility(R.id.einsatz_fahrt_action, View.VISIBLE)
            if (state.objektId != null && !state.objektUrl.isNullOrBlank()) {
                views.setTextViewText(R.id.einsatz_object_action, "Objekt: ${state.objektName ?: "öffnen"}")
                EcpWidgetSupport.contentIntent(context, state.objektUrl, 8320 + id)
                    ?.let { views.setOnClickPendingIntent(R.id.einsatz_object_action, it) }
            } else {
                views.setTextViewText(R.id.einsatz_object_action, "Alle Objekte")
                EcpWidgetSupport.contentIntent(context, "/objekte/", 8330 + id)
                    ?.let { views.setOnClickPendingIntent(R.id.einsatz_object_action, it) }
            }
            state.gmapsUrl?.let { url ->
                EcpWidgetSupport.mapsIntent(context, url, 8310 + id)
                    ?.let { views.setOnClickPendingIntent(R.id.einsatz_maps_action, it) }
            }
            EcpWidgetSupport.contentIntent(context, "/fahrtenbuch/neu", 8340 + id)
                ?.let { views.setOnClickPendingIntent(R.id.einsatz_fahrt_action, it) }
            views.setViewVisibility(
                R.id.einsatz_map,
                if (state.gmapsUrl == null && EcpWidgetSupport.isExpanded(options)) View.VISIBLE else View.GONE,
            )
            views.setInt(R.id.einsatz_root, "setBackgroundResource", R.drawable.ec_widget_background_alert)
            EcpWidgetSupport.contentIntent(context, state.url, 8300 + id)
                ?.let { views.setOnClickPendingIntent(R.id.einsatz_root, it) }
    }

    private fun renderGsl(
        context: Context,
        views: RemoteViews,
        id: Int,
        state: EcpWidgetSupport.GslQueueWidgetState,
    ) {
        views.setTextViewText(R.id.einsatz_label, "GROSSSCHADENSLAGE · ${state.lageName}")
        views.setTextViewText(R.id.einsatz_title, state.current.bezeichnung)
        views.setTextViewText(R.id.einsatz_detail, state.current.address.ifBlank { "Einsatzstelle öffnen" })
        views.setViewVisibility(R.id.einsatz_meldung, View.GONE)
        views.setViewVisibility(R.id.einsatz_map, View.GONE)
        views.setViewVisibility(R.id.einsatz_actions, View.VISIBLE)
        views.setViewVisibility(R.id.einsatz_maps_action, View.GONE)
        views.setViewVisibility(R.id.einsatz_object_action, View.GONE)
        views.setViewVisibility(R.id.einsatz_fahrt_action, View.VISIBLE)
        EcpWidgetSupport.contentIntent(context, "/fahrtenbuch/neu", 8340 + id)
            ?.let { views.setOnClickPendingIntent(R.id.einsatz_fahrt_action, it) }
        views.setViewVisibility(R.id.gsl_queue_container, View.VISIBLE)
        views.removeAllViews(R.id.gsl_queue_container)
        val current = RemoteViews(context.packageName, R.layout.ec_widget_gsl_current)
        current.setTextViewText(R.id.gsl_current_title, state.current.bezeichnung)
        current.setTextViewText(R.id.gsl_current_meldung, state.current.meldung ?: "")
        current.setViewVisibility(R.id.gsl_current_meldung, if (state.current.meldung != null) View.VISIBLE else View.GONE)
        current.setTextViewText(R.id.gsl_current_address, state.current.address)
        EcpWidgetSupport.contentIntent(context, state.lageUrl, 8350 + id)
            ?.let { current.setOnClickPendingIntent(R.id.gsl_current_root, it) }
        state.current.gmapsUrl?.let { url ->
            current.setViewVisibility(R.id.gsl_current_maps, View.VISIBLE)
            EcpWidgetSupport.mapsIntent(context, url, 8351 + id)
                ?.let { current.setOnClickPendingIntent(R.id.gsl_current_maps, it) }
        } ?: current.setViewVisibility(R.id.gsl_current_maps, View.GONE)
        views.addView(R.id.gsl_queue_container, current)
        state.upcoming.forEachIndexed { index, site ->
            val row = RemoteViews(context.packageName, R.layout.ec_widget_gsl_row)
            row.setTextViewText(R.id.gsl_row_title, site.bezeichnung)
            row.setTextViewText(R.id.gsl_row_address, site.address)
            val requestCode = if (index == 0) 8352 + id else 8354 + id
            EcpWidgetSupport.contentIntent(context, state.lageUrl, requestCode)
                ?.let { row.setOnClickPendingIntent(R.id.gsl_row_root, it) }
            views.addView(R.id.gsl_queue_container, row)
        }
        if (state.remainingCount > 0) {
            val more = RemoteViews(context.packageName, R.layout.ec_widget_gsl_more)
            more.setTextViewText(R.id.gsl_more_root, "+${state.remainingCount} weitere")
            EcpWidgetSupport.contentIntent(context, state.lageUrl, 8356 + id)
                ?.let { more.setOnClickPendingIntent(R.id.gsl_more_root, it) }
            views.addView(R.id.gsl_queue_container, more)
        }
        views.setInt(R.id.einsatz_root, "setBackgroundResource", R.drawable.ec_widget_background_alert)
        EcpWidgetSupport.contentIntent(context, state.lageUrl, 8300 + id)
            ?.let { views.setOnClickPendingIntent(R.id.einsatz_root, it) }
    }
}
