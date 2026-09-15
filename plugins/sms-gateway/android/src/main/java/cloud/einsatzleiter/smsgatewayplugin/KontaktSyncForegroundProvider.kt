package cloud.einsatzleiter.smsgatewayplugin

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import cloud.einsatzleiter.smsgatewayplugin.kontakte.KontaktOfflineSyncWorker

/** Registers the process foreground trigger through manifest merging. */
class KontaktSyncForegroundProvider : ContentProvider() {
    override fun onCreate(): Boolean = try {
        val appContext = context?.applicationContext
        if (appContext != null) {
            registerOfflineContactsShortcut(appContext)
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

    private fun registerOfflineContactsShortcut(context: android.content.Context) {
        val shortcut = ShortcutInfoCompat.Builder(context, OFFLINE_KONTAKTE_SHORTCUT_ID)
            .setShortLabel("Kontakte offline")
            .setLongLabel("Kontakte offline öffnen")
            .setIcon(IconCompat.createWithResource(context, android.R.drawable.ic_menu_call))
            .setIntent(Intent(context, KontaktListActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    private companion object {
        const val OFFLINE_KONTAKTE_SHORTCUT_ID = "offline_kontakte"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
