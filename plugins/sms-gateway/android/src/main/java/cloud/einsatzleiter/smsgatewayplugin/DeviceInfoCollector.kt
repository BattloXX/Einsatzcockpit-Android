package cloud.einsatzleiter.smsgatewayplugin

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.telephony.SubscriptionManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.webkit.WebView
import androidx.core.app.NotificationManagerCompat
import java.util.Locale
import java.util.TimeZone

/** Read-only queries. No hardware identifiers, subscription identities or location queries. */
internal class DeviceInfoCollector(private val context: Context) {
    fun collect(): Map<String, Map<String, Any?>> {
        val result = linkedMapOf<String, Map<String, Any?>>()
        fun section(name: String, query: () -> Map<String, Any?>) { runCatching { result[name] = query() } }
        val pm = context.packageManager
        val config = context.resources.configuration
        val metrics = context.resources.displayMetrics
        val power = context.getSystemService(PowerManager::class.java)
        section("app") {
            val info = pm.getPackageInfo(context.packageName, 0)
            mapOf("version_name" to info.versionName, "version_code" to if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
                "build_variant" to if (context.resources.getBoolean(R.bool.auto_update_enabled)) "auto_update" else "standard",
                "target_sdk" to context.applicationInfo.targetSdkVersion, "min_sdk" to context.applicationInfo.minSdkVersion,
                "first_install_at" to DiagnoseReportLogic.iso(info.firstInstallTime), "last_update_at" to DiagnoseReportLogic.iso(info.lastUpdateTime))
        }
        section("geraet") {
            val ram = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val values = linkedMapOf<String, Any?>("hersteller" to Build.MANUFACTURER, "marke" to Build.BRAND, "modell" to Build.MODEL, "geraetename" to Build.DEVICE,
                "klasse" to if (config.smallestScreenWidthDp >= 600) "tablet" else "handy", "abi" to Build.SUPPORTED_ABIS.firstOrNull(),
                "ram_mb_total" to ram.totalMem / 1048576, "ram_mb_free" to ram.availMem / 1048576,
                "speicher_mb_free" to StatFs(context.filesDir.path).availableBytes / 1048576,
                "laedt" to ((battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0), "akkusparer" to power.isPowerSaveMode,
                "nfc" to pm.hasSystemFeature(PackageManager.FEATURE_NFC), "kamera" to pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY), "telefonie" to pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
            if (level >= 0 && scale > 0) values["akku_prozent"] = (level * 100 / scale).coerceIn(0, 100)
            // Count only; do not enumerate subscriptions or request a new permission.
            runCatching {
                if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) values["sim_anzahl"] = 0
                else if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    values["sim_anzahl"] = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoCount
                }
            }
            if (pm.hasSystemFeature("android.hardware.sensor.hinge_angle")) values["foldable"] = true
            values
        }
        section("android") { mapOf("version" to Build.VERSION.RELEASE, "sdk_int" to Build.VERSION.SDK_INT, "sicherheitspatch" to Build.VERSION.SECURITY_PATCH, "build_id" to Build.ID,
            "oberflaeche" to when (Build.MANUFACTURER.lowercase(Locale.ROOT)) { "samsung" -> "One UI (Hersteller)"; "xiaomi" -> "MIUI/HyperOS (Hersteller)"; "huawei" -> "EMUI (Hersteller)"; else -> Build.DISPLAY.take(100) }) }
        section("display") {
            val display = context.getSystemService(WindowManager::class.java).defaultDisplay
            val pixels = DisplayMetrics().also { display.getRealMetrics(it) }
            mapOf("breite_px" to pixels.widthPixels, "hoehe_px" to pixels.heightPixels, "dpi" to metrics.densityDpi,
                "dichte_bucket" to when { metrics.densityDpi <= 120 -> "ldpi"; metrics.densityDpi <= 160 -> "mdpi"; metrics.densityDpi <= 240 -> "hdpi"; metrics.densityDpi <= 320 -> "xhdpi"; metrics.densityDpi <= 480 -> "xxhdpi"; else -> "xxxhdpi" },
                "breite_dp" to config.screenWidthDp, "hz" to display.refreshRate, "ausrichtung" to if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "quer" else "hoch",
                "schrift_skala" to config.fontScale, "anzeige_skala" to metrics.densityDpi.toDouble() / DisplayMetrics.DENSITY_DEVICE_STABLE)
        }
        section("webview") { val info = if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage() else null
            mapOf("paket" to info?.packageName, "version" to info?.versionName) }
        section("locale") { val prefs = ReportSender.prefs(context)
            mapOf("sprache" to config.locales[0].toLanguageTag(), "zeitzone" to TimeZone.getDefault().id, "uhr_abweichung_s" to if (prefs.contains("clock_offset_s")) prefs.getLong("clock_offset_s", 0) else null) }
        section("berechtigungen") {
            fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            val nm = context.getSystemService(NotificationManager::class.java)
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val net = cm.getNetworkCapabilities(cm.activeNetwork)
            mapOf("benachrichtigungen" to NotificationManagerCompat.from(context).areNotificationsEnabled(), "akku_optimierung_aus" to power.isIgnoringBatteryOptimizations(context.packageName),
                "vollbild_intent" to (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()), "dnd_zugriff" to nm.isNotificationPolicyAccessGranted,
                "standort" to if (Build.VERSION.SDK_INT >= 29 && granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) "immer" else if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) "vordergrund" else "nein",
                "sms" to granted(Manifest.permission.SEND_SMS), "netz" to when { net?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wlan"; net?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "mobil"; else -> "keins" })
        }
        section("dienste") {
            val prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            val registered = !prefs.getString("ec_fcm_caps_registered", null).isNullOrBlank()
            val values = linkedMapOf<String, Any?>("poller_laeuft" to EinsatzLivePoller.isRunning, "keepalive" to DeviceKeepaliveService.isRunning,
                "gateway_verbunden" to SmsGatewayService.isConnected, "fcm_registriert" to registered, "fcm_faehigkeiten" to if (registered) listOf("native_alarm") else emptyList<String>(),
                "device_token_vorhanden" to !prefs.getString("el_device_token", null).isNullOrBlank(), "gateway_token_vorhanden" to !prefs.getString("el_gateway_token", null).isNullOrBlank())
            val times = ReportSender.prefs(context)
            listOf("letzter_poll_ok_at", "letzte_push_bestaetigung_at", "letzter_kontaktsync_at").forEach { key -> if (times.contains(key)) values[key] = DiagnoseReportLogic.iso(times.getLong(key, 0)) }
            values
        }
        return result
    }
}
