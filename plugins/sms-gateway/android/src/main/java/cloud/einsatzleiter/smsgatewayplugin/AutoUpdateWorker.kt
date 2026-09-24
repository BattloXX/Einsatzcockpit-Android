package cloud.einsatzleiter.smsgatewayplugin

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Checks GitHub releases and silently installs the dedicated Auto-Update APK. */
class AutoUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    companion object {
        private const val WORK_NAME = "apk-auto-update"
        private const val IMMEDIATE_WORK_NAME = "apk-auto-update-immediate"
        private const val INTERVAL_HOURS = 12L
        private const val PREF_UPDATE_CHANNEL = "ec_update_channel"
        private const val RELEASES_URL = "https://api.github.com/repos/BattloXX/Einsatzcockpit-Android/releases"
        private const val AUTO_UPDATE_ASSET_PREFIX = "einsatzcockpit-autoupdate-v"

        private fun preferences(context: Context) = context.applicationContext
            .getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)

        fun schedule(context: Context) {
            if (!context.resources.getBoolean(R.bool.auto_update_enabled)) return
            val request = PeriodicWorkRequestBuilder<AutoUpdateWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** A user-requested check must replace a stale retry instead of waiting behind it. */
        fun triggerImmediateCheck(context: Context) {
            if (!context.resources.getBoolean(R.bool.auto_update_enabled)) return
            val request = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun doWork(): Result {
        if (!applicationContext.resources.getBoolean(R.bool.auto_update_enabled)) {
            AutoUpdateStatusStore.log(applicationContext, "Auto-Update ist in dieser APK-Variante deaktiviert")
            return Result.success()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            AutoUpdateStatusStore.log(applicationContext, "Auto-Update benötigt mindestens Android 12")
            return Result.success()
        }

        AutoUpdateStatusStore.checked(applicationContext, "Update-Prüfung im Hintergrund gestartet")
        val channel = preferences(applicationContext)
            .getString(PREF_UPDATE_CHANNEL, "stable")
            ?: "stable"

        val release = try {
            findRelease(channel)
        } catch (error: Exception) {
            AutoUpdateStatusStore.log(applicationContext, "Update-Prüfung fehlgeschlagen", error.message)
            return Result.retry()
        }
        if (release == null) {
            AutoUpdateStatusStore.log(applicationContext, "Keine passende GitHub-Veröffentlichung gefunden")
            return Result.success()
        }

        val version = release.optString("tag_name").removePrefix("v")
        if (version.isBlank()) {
            AutoUpdateStatusStore.log(applicationContext, "Update-Prüfung fehlgeschlagen", "Release-Tag fehlt")
            return Result.success()
        }
        AutoUpdateStatusStore.releaseFound(applicationContext, version)
        val installedVersion = try {
            applicationContext.packageManager
                .getPackageInfo(applicationContext.packageName, 0)
                .versionName
                ?: "0"
        } catch (error: Exception) {
            AutoUpdateStatusStore.log(applicationContext, "Installierte Version nicht lesbar", error.message)
            return Result.retry()
        }
        if (compareVersions(version, installedVersion) <= 0) {
            AutoUpdateStatusStore.log(applicationContext, "Keine neue Version: installiert v$installedVersion, Release v$version")
            return Result.success()
        }

        val asset = findAutoUpdateAsset(release)
        if (asset == null) {
            AutoUpdateStatusStore.log(applicationContext, "Update-APK fehlt", "Release v$version enthält keine Auto-Update-APK")
            return Result.success()
        }
        val downloadUrl = asset.optString("browser_download_url")
        if (downloadUrl.isBlank()) {
            AutoUpdateStatusStore.download(applicationContext, "APK-Download fehlgeschlagen", "Download-URL fehlt")
            return Result.retry()
        }

        val apk = File(applicationContext.cacheDir, "einsatzcockpit-autoupdate-$version.apk")
        try {
            download(downloadUrl, apk)
            AutoUpdateStatusStore.download(applicationContext, "APK v$version heruntergeladen")
        } catch (error: Exception) {
            apk.delete()
            AutoUpdateStatusStore.download(applicationContext, "APK-Download fehlgeschlagen", error.message)
            return Result.retry()
        }

        if (isSafetyCritical()) {
            AutoUpdateStatusStore.install(
                applicationContext,
                "Installation verschoben: Live-Einsatz oder Gateway-Wiederverbindung aktiv",
            )
            return Result.retry()
        }

        return try {
            install(apk, version)
            AutoUpdateStatusStore.install(applicationContext, "Installation von v$version an PackageInstaller übergeben")
            Result.success()
        } catch (error: Exception) {
            AutoUpdateStatusStore.install(applicationContext, "Installation konnte nicht gestartet werden", error.message)
            Result.retry()
        }
    }

    private fun findRelease(channel: String): JSONObject? {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .get()
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Einsatzcockpit-Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub HTTP ${response.code}")
            val releases = JSONArray(response.body?.string() ?: "[]")
            for (index in 0 until releases.length()) {
                val release = releases.optJSONObject(index) ?: continue
                if (release.optBoolean("draft", false)) continue
                if (channel == "stable" && release.optBoolean("prerelease", false)) continue
                return release
            }
        }
        return null
    }

    private fun findAutoUpdateAsset(release: JSONObject): JSONObject? {
        val assets = release.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            if (name.startsWith(AUTO_UPDATE_ASSET_PREFIX) && name.endsWith(".apk")) return asset
        }
        return null
    }

    private fun download(url: String, destination: File) {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Einsatzcockpit-Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("APK-Download HTTP ${response.code}")
            val body = response.body ?: throw IOException("APK-Download ohne Inhalt")
            body.byteStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (!destination.isFile || destination.length() == 0L) throw IOException("APK-Download ist leer")
    }

    private fun isSafetyCritical(): Boolean {
        val prefs = preferences(applicationContext)
        val activeIncident = !prefs.getString(EinsatzLivePoller.PREF_INCIDENT_ID, null).isNullOrBlank()
        return activeIncident || SmsGatewayService.connectingSince > 0L
    }

    private fun install(apk: File, version: String) {
        val installer = applicationContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            apk.inputStream().use { input ->
                session.openWrite("einsatzcockpit-autoupdate-v$version.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(applicationContext, AutoUpdateInstallReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pendingIntent.intentSender)
        } catch (error: Exception) {
            session.abandon()
            throw error
        } finally {
            session.close()
        }
    }

    private fun compareVersions(first: String, second: String): Int {
        val firstParts = first.split('.').map { it.toIntOrNull() ?: 0 }
        val secondParts = second.split('.').map { it.toIntOrNull() ?: 0 }
        val length = maxOf(firstParts.size, secondParts.size)
        for (index in 0 until length) {
            val left = firstParts.getOrElse(index) { 0 }
            val right = secondParts.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }
}
