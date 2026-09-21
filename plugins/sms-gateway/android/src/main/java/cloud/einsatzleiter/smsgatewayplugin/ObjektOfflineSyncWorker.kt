package cloud.einsatzleiter.smsgatewayplugin

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Fuehrt den PWA-Objekt-Sync etwa alle sechs Stunden in einer kurzlebigen
 * WebView aus. WorkManager haelt den Prozess nur fuer diesen Lauf aktiv; der
 * Einsatz-Keepalive-Service und sein WakeLock werden dabei nicht gestartet.
 */
class ObjektOfflineSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    companion object {
        private const val WORK_NAME = "objekt-offline-sync"
        private const val IMMEDIATE_WORK_NAME = "$WORK_NAME-immediate"
        private const val PREF_OBJECT_SYNC_ENABLED = "ec_object_sync_enabled"
        private const val PREF_OBJECT_CACHE_CLEARING = "ec_object_cache_clearing"
        private const val INTERVAL_HOURS = 6L
        private const val TIMEOUT_MINUTES = 9L

        private fun preferences(context: Context) = context.applicationContext
            .getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)

        /** New installations follow the login type until the user makes an explicit choice. */
        fun isEnabled(context: Context): Boolean {
            val prefs = preferences(context)
            return if (prefs.contains(PREF_OBJECT_SYNC_ENABLED)) {
                prefs.getBoolean(PREF_OBJECT_SYNC_ENABLED, false)
            } else {
                !prefs.getString("el_device_token", null).isNullOrBlank()
            }
        }

        fun isCacheClearing(context: Context): Boolean =
            preferences(context).getBoolean(PREF_OBJECT_CACHE_CLEARING, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            preferences(context).edit().putBoolean(PREF_OBJECT_SYNC_ENABLED, enabled).apply()
            if (enabled) {
                OfflineCacheStatusStore.logActivity(context, "Objekt-Sync wurde aktiviert")
                schedule(context)
                forceImmediateSync(context)
            } else {
                cancel(context)
                OfflineCacheStatusStore.logActivity(context, "Objekt-Sync wurde deaktiviert")
            }
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).apply {
                cancelUniqueWork(WORK_NAME)
                cancelUniqueWork(IMMEDIATE_WORK_NAME)
            }
        }

        fun schedule(context: Context) {
            if (!isEnabled(context)) {
                cancel(context)
                return
            }
            val prefs = preferences(context)
            if (prefs.getString(EinsatzLivePoller.PREF_BASE_URL, null).isNullOrBlank()) {
                OfflineCacheStatusStore.logActivity(context, "Objekt-Sync wartet auf die Anmeldung")
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<ObjektOfflineSyncWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS,
            )
                .setInitialDelay(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Starts one Android WebView sync after login instead of waiting for the 6 h interval. */
        fun triggerImmediateSync(context: Context) {
            if (!isEnabled(context)) return
            val request = OneTimeWorkRequestBuilder<ObjektOfflineSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /** A refresh initiated by the user must not remain behind a stale retry. */
        fun forceImmediateSync(context: Context) {
            if (!isEnabled(context)) return
            val request = OneTimeWorkRequestBuilder<ObjektOfflineSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /** Removes only the object precache; contacts and login data remain untouched. */
        @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
        fun clearCache(context: Context) {
            val appContext = context.applicationContext
            cancel(appContext)
            OfflineCacheStatusStore.clearObjects(appContext)
            val prefs = preferences(appContext)
            val baseUrl = prefs.getString(EinsatzLivePoller.PREF_BASE_URL, null)?.trimEnd('/')
            if (baseUrl.isNullOrBlank()) return

            prefs.edit().putBoolean(PREF_OBJECT_CACHE_CLEARING, true).apply()
            Handler(Looper.getMainLooper()).post {
                val finished = AtomicBoolean(false)
                val webView = WebView(appContext)
                fun finish(message: String) {
                    if (!finished.compareAndSet(false, true)) return
                    preferences(appContext).edit().putBoolean(PREF_OBJECT_CACHE_CLEARING, false).apply()
                    OfflineCacheStatusStore.logActivity(appContext, message)
                    webView.removeJavascriptInterface("ObjektCacheClearNative")
                    webView.stopLoading()
                    webView.destroy()
                }
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.addJavascriptInterface(object {
                    @JavascriptInterface fun done(ok: Boolean) {
                        webView.post {
                            finish(if (ok) "Objektcache wurde gelöscht" else "Objektcache konnte nicht vollständig gelöscht werden")
                        }
                    }
                }, "ObjektCacheClearNative")
                webView.webViewClient = object : WebViewClient() {
                    private var invoked = false
                    override fun onPageFinished(view: WebView, url: String) {
                        if (invoked) return
                        invoked = true
                        view.evaluateJavascript(
                            """
                            Promise.resolve(typeof window.objektOfflineCacheLeeren === "function"
                              ? window.objektOfflineCacheLeeren() : false)
                              .then(function (ok) { ObjektCacheClearNative.done(ok === true); })
                              .catch(function () { ObjektCacheClearNative.done(false); });
                            """.trimIndent(),
                            null,
                        )
                    }
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    finish("Objektcache konnte nicht vollständig gelöscht werden: Zeitlimit überschritten")
                }, 20_000)
                webView.loadUrl("$baseUrl/")
            }
        }
    }

    override fun doWork(): Result {
        if (!isEnabled(applicationContext)) return Result.success()
        val prefs = preferences(applicationContext)
        if (prefs.getString(EinsatzLivePoller.PREF_BASE_URL, null).isNullOrBlank()) {
            OfflineCacheStatusStore.logActivity(applicationContext, "Objekt-Sync übersprungen: App ist nicht angemeldet")
            WorkManager.getInstance(applicationContext).cancelUniqueWork(WORK_NAME)
            return Result.success()
        }
        val baseUrl = prefs.getString(EinsatzLivePoller.PREF_BASE_URL, null)?.trimEnd('/')
            ?: run {
                OfflineCacheStatusStore.logActivity(applicationContext, "Objekt-Sync fehlgeschlagen: Server-URL fehlt")
                return Result.retry()
            }

        OfflineCacheStatusStore.logActivity(applicationContext, "Objekt-Sync wird im Hintergrund gestartet")

        val completed = CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        val terminated = AtomicBoolean(false)
        val webViewRef = AtomicReference<WebView?>(null)
        Handler(Looper.getMainLooper()).post {
            runSyncInWebView(baseUrl, completed, succeeded, terminated, webViewRef)
        }
        val finished = completed.await(TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (!finished && terminated.compareAndSet(false, true)) {
            Handler(Looper.getMainLooper()).post {
                webViewRef.getAndSet(null)?.let {
                    it.removeJavascriptInterface("ObjektSyncNative")
                    it.stopLoading()
                    it.destroy()
                }
            }
        }
        if (!finished) {
            OfflineCacheStatusStore.logActivity(applicationContext, "Objekt-Sync fehlgeschlagen: Zeitlimit überschritten")
        } else if (!succeeded.get()) {
            OfflineCacheStatusStore.logActivity(applicationContext, "Objekt-Sync fehlgeschlagen: WebView-Abgleich nicht erfolgreich")
        }
        return if (finished && succeeded.get()) Result.success() else Result.retry()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun runSyncInWebView(
        baseUrl: String,
        completed: CountDownLatch,
        succeeded: AtomicBoolean,
        terminated: AtomicBoolean,
        webViewRef: AtomicReference<WebView?>,
    ) {
        val webView = WebView(applicationContext)
        webViewRef.set(webView)
        val finish = {
            if (terminated.compareAndSet(false, true)) {
                webViewRef.compareAndSet(webView, null)
                webView.removeJavascriptInterface("ObjektSyncNative")
                webView.stopLoading()
                webView.destroy()
                completed.countDown()
            }
        }
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun status(message: String) {
                OfflineCacheStatusStore.logActivity(
                    applicationContext,
                    "Objekt-Sync: ${message.take(400)}",
                )
            }

            @JavascriptInterface
            fun done(ok: Boolean) {
                succeeded.set(ok)
                webView.post { finish() }
            }
        }, "ObjektSyncNative")
        webView.webViewClient = object : WebViewClient() {
            private var invoked = false

            override fun onPageFinished(view: WebView, url: String) {
                if (invoked) return
                invoked = true
                view.evaluateJavascript(
                    """
                    (function waitForOfflineScript(attemptsLeft) {
                      if (typeof window.objektOfflineSync !== "function") {
                        if (attemptsLeft <= 0) {
                          ObjektSyncNative.status("Offline-Skript wurde nicht geladen – Anmeldung oder WebView-Verbindung prüfen");
                          ObjektSyncNative.done(false);
                          return;
                        }
                        if (attemptsLeft === 30) {
                          ObjektSyncNative.status("WebView geladen, warte auf Offline-Skript");
                        }
                        window.setTimeout(function () { waitForOfflineScript(attemptsLeft - 1); }, 500);
                        return;
                      }
                      ObjektSyncNative.status("Offline-Skript geladen, Abgleich läuft");
                      Promise.resolve(window.objektOfflineSync())
                        .then(function (ok) {
                          if (ok !== true) {
                            ObjektSyncNative.status("Offline-Skript hat keinen erfolgreichen Abschluss gemeldet");
                          }
                          ObjektSyncNative.done(ok === true);
                        })
                        .catch(function (error) {
                          ObjektSyncNative.status("Offline-Skriptfehler: " + (error && error.message ? error.message : "unbekannt"));
                          ObjektSyncNative.done(false);
                        });
                    })(30);
                    """.trimIndent(),
                    null,
                )
            }
        }
        webView.loadUrl("$baseUrl/")
    }
}
