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
import androidx.work.NetworkType
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
        private const val INTERVAL_HOURS = 6L
        private const val TIMEOUT_MINUTES = 9L

        fun schedule(context: Context) {
            val prefs = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
            if (prefs.getString("el_device_token", null).isNullOrBlank()) return

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
    }

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
        if (prefs.getString("el_device_token", null).isNullOrBlank()) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(WORK_NAME)
            return Result.success()
        }
        val baseUrl = prefs.getString(EinsatzLivePoller.PREF_BASE_URL, null)?.trimEnd('/')
            ?: return Result.retry()

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
                    (function () {
                      if (typeof window.objektOfflineSync !== "function") {
                        ObjektSyncNative.done(false);
                        return;
                      }
                      Promise.resolve(window.objektOfflineSync())
                        .then(function (ok) { ObjektSyncNative.done(ok === true); })
                        .catch(function () { ObjektSyncNative.done(false); });
                    })();
                    """.trimIndent(),
                    null,
                )
            }
        }
        webView.loadUrl("$baseUrl/")
    }
}
