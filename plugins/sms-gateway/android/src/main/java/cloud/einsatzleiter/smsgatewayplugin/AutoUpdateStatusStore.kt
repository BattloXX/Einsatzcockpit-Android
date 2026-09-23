package cloud.einsatzleiter.smsgatewayplugin

import android.content.Context

/** Persistent, cross-process diagnostics for the background APK updater. */
object AutoUpdateStatusStore {
    private const val PREFS = "ec_auto_update_status"
    private const val KEY_LAST_CHECKED = "last_checked"
    private const val KEY_RELEASE_VERSION = "release_version"
    private const val KEY_DOWNLOAD_RESULT = "download_result"
    private const val KEY_INSTALL_RESULT = "install_result"
    private const val KEY_ERROR = "error"
    private const val KEY_ACTIVITY = "activity"

    fun checked(context: Context, activity: String) {
        prefs(context).edit()
            .putLong(KEY_LAST_CHECKED, System.currentTimeMillis())
            .putString(KEY_ACTIVITY, activity)
            .apply()
    }

    fun releaseFound(context: Context, version: String) {
        prefs(context).edit()
            .putString(KEY_RELEASE_VERSION, version)
            .putString(KEY_ACTIVITY, "Update-Version $version gefunden")
            .remove(KEY_ERROR)
            .apply()
    }

    fun download(context: Context, result: String, error: String? = null) {
        prefs(context).edit()
            .putString(KEY_DOWNLOAD_RESULT, result)
            .putString(KEY_ACTIVITY, result)
            .also {
                if (error == null) it.remove(KEY_ERROR) else it.putString(KEY_ERROR, error)
            }
            .apply()
    }

    fun install(context: Context, result: String, error: String? = null) {
        prefs(context).edit()
            .putString(KEY_INSTALL_RESULT, result)
            .putString(KEY_ACTIVITY, result)
            .also {
                if (error == null) it.remove(KEY_ERROR) else it.putString(KEY_ERROR, error)
            }
            .apply()
    }

    fun log(context: Context, activity: String, error: String? = null) {
        prefs(context).edit()
            .putString(KEY_ACTIVITY, activity)
            .also {
                if (error == null) it.remove(KEY_ERROR) else it.putString(KEY_ERROR, error)
            }
            .apply()
    }

    fun snapshot(context: Context): Snapshot {
        val prefs = prefs(context)
        return Snapshot(
            lastCheckedAtMs = prefs.getLong(KEY_LAST_CHECKED, 0L).takeIf { it > 0L },
            releaseVersion = prefs.getString(KEY_RELEASE_VERSION, null),
            downloadResult = prefs.getString(KEY_DOWNLOAD_RESULT, null),
            installResult = prefs.getString(KEY_INSTALL_RESULT, null),
            error = prefs.getString(KEY_ERROR, null),
            activity = prefs.getString(KEY_ACTIVITY, null),
        )
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Snapshot(
        val lastCheckedAtMs: Long?,
        val releaseVersion: String?,
        val downloadResult: String?,
        val installResult: String?,
        val error: String?,
        val activity: String?,
    )
}
