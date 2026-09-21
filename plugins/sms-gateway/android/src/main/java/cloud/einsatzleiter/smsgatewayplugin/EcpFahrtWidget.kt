package cloud.einsatzleiter.smsgatewayplugin

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val FAHRT_WIDGET_PREFS = "ec_widget_fahrt_state"
private const val VEHICLE_ID_PREFIX = "vehicle_id_"
private const val VEHICLE_CODE_PREFIX = "vehicle_code_"

class EcpFahrtWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val prefs = context.getSharedPreferences(FAHRT_WIDGET_PREFS, Context.MODE_PRIVATE)
            val vehicleId = prefs.getLong("$VEHICLE_ID_PREFIX$id", -1L)
            val vehicleCode = prefs.getString("$VEHICLE_CODE_PREFIX$id", null)
            val hasVehicle = vehicleId >= 0L
            val path = if (hasVehicle) "/fahrtenbuch/neu?fahrzeug=$vehicleId" else "/fahrtenbuch/neu"

            val views = RemoteViews(context.packageName, R.layout.ec_widget_fahrt)
            views.setImageViewResource(R.id.widget_icon, R.drawable.ic_ec_widget_fahrt)
            views.setTextViewText(R.id.widget_title, "Fahrt erfassen")
            views.setTextViewText(R.id.widget_subtitle, vehicleCode.orEmpty())
            views.setViewVisibility(
                R.id.widget_subtitle,
                if (hasVehicle && !vehicleCode.isNullOrBlank()) View.VISIBLE else View.GONE,
            )
            EcpWidgetSupport.contentIntent(context, path, 8400 + id)
                ?.let { views.setOnClickPendingIntent(R.id.widget_root, it) }
            manager.updateAppWidget(id, views)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = context.getSharedPreferences(FAHRT_WIDGET_PREFS, Context.MODE_PRIVATE).edit()
        appWidgetIds.forEach { id ->
            editor.remove("$VEHICLE_ID_PREFIX$id")
            editor.remove("$VEHICLE_CODE_PREFIX$id")
        }
        editor.apply()
    }
}

class EcpFahrtWidgetConfigureActivity : AppCompatActivity() {
    private data class Vehicle(val id: Long, val code: String, val name: String)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED, Intent())
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        loadVehicles()
    }

    override fun onDestroy() {
        client.dispatcher.cancelAll()
        super.onDestroy()
    }

    private fun loadVehicles() {
        val prefs = getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString(EinsatzLivePoller.PREF_BASE_URL, "https://einsatzcockpit.com")
            ?.trimEnd('/')
        if (baseUrl.isNullOrBlank()) {
            showVehicleChoices(emptyList(), null)
            return
        }
        val deviceToken = prefs.getString("el_device_token", null)?.takeIf { it.isNotBlank() }
        val request = try {
            Request.Builder()
                .url("$baseUrl/api/v1/device/vehicles")
                .get()
                .apply { deviceToken?.let { header("Authorization", "Bearer $it") } }
                .build()
        } catch (_: IllegalArgumentException) {
            showVehicleChoices(emptyList(), null)
            return
        }
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { showVehicleChoices(emptyList(), null) }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    if (!it.isSuccessful) null else parseVehicles(it.body?.string().orEmpty())
                }
                mainHandler.post {
                    showVehicleChoices(result?.first.orEmpty(), result?.second)
                }
            }
        })
    }

    private fun parseVehicles(body: String): Pair<List<Vehicle>, Vehicle?>? = try {
        val root = JSONObject(body)
        val vehicles = root.optJSONArray("vehicles") ?: return null
        val deviceVehicle = root.optJSONObject("device_vehicle")?.toVehicle()
        val choices = buildList {
            for (index in 0 until vehicles.length()) {
                vehicles.optJSONObject(index)?.toVehicle()?.let(::add)
            }
        }
        choices to deviceVehicle
    } catch (_: Exception) {
        null
    }

    private fun JSONObject.toVehicle(): Vehicle? {
        if (!has("id") || isNull("id")) return null
        return Vehicle(optLong("id"), optString("code"), optString("name"))
    }

    private fun showVehicleChoices(vehicles: List<Vehicle>, deviceVehicle: Vehicle?) {
        if (isFinishing || isDestroyed) return
        val choices = listOf("Kein Fahrzeug festlegen") + vehicles.map { "${it.code} – ${it.name}" }
        AlertDialog.Builder(this)
            .setTitle("Fahrt erfassen")
            .apply {
                deviceVehicle?.let {
                    setMessage(
                        "Dieses Gerät ist bereits mit Fahrzeug ${it.code} verknüpft – " +
                            "diese Auswahl wird dann nicht verwendet.",
                    )
                }
            }
            .setItems(choices.toTypedArray()) { _, which ->
                saveSelection(vehicles.getOrNull(which - 1))
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun saveSelection(vehicle: Vehicle?) {
        val prefs = getSharedPreferences(FAHRT_WIDGET_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("$VEHICLE_ID_PREFIX$appWidgetId")
            .remove("$VEHICLE_CODE_PREFIX$appWidgetId")
            .apply {
                vehicle?.let {
                    putLong("$VEHICLE_ID_PREFIX$appWidgetId", it.id)
                    putString("$VEHICLE_CODE_PREFIX$appWidgetId", it.code)
                }
            }
            .apply()

        val manager = AppWidgetManager.getInstance(this)
        EcpFahrtWidgetProvider().onUpdate(this, manager, intArrayOf(appWidgetId))
        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )
        finish()
    }
}
