package cloud.einsatzleiter.smsgatewayplugin

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import cloud.einsatzleiter.smsgatewayplugin.kontakte.KontaktOfflineSyncWorker

/** Registers the process foreground trigger through manifest merging. */
class KontaktSyncForegroundProvider : ContentProvider() {
    override fun onCreate(): Boolean = try {
        val appContext = context?.applicationContext
        if (appContext != null) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    KontaktOfflineSyncWorker.triggerImmediateSync(appContext)
                }
            })
        }
        true
    } catch (_: Exception) {
        // A startup helper must never delay or prevent opening the host application.
        true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
