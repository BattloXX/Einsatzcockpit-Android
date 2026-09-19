package cloud.einsatzleiter.smsgatewayplugin

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Laedt eine Einsatz-Seite still in einer kurzlebigen WebView, damit der PWA-
 * Service-Worker sie ueber seine bestehende network-first-Cache-Regel fuer
 * /einsatz/<id> aktuell haelt. Ausgeloest vom FCM-Empfang in
 * EinsatzFirebaseMessagingService (die Push-Nachricht signalisiert bereits
 * zuverlaessig "es gibt einen relevanten Einsatz"). Bewusst NIEDRIGE
 * Prioritaet: normale (nicht expedited) WorkManager-Planung, damit der
 * SMS-Gateway-Betrieb und der bestehende Live-Status-Refresh beim selben
 * FCM-Empfang unbeeinflusst und unverzoegert bleiben.
 */
class EinsatzPreloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    companion object {
        private const val KEY_URL = "url"
        private const val TIMEOUT_MINUTES = 2L

        /** url ist bereits eine vollstaendige https://... URL (siehe absoluteUrl()). */
        fun enqueue(context: Context, url: String) {
            val request = OneTimeWorkRequestBuilder<EinsatzPreloadWorker>()
                .setInputData(Data.Builder().putString(KEY_URL, url).build())
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(request)
        }
    }

    override fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()

        val completed = CountDownLatch(1)
        val terminated = AtomicBoolean(false)
        val webViewRef = AtomicReference<WebView?>(null)
        Handler(Looper.getMainLooper()).post {
            runPreloadInWebView(url, completed, terminated, webViewRef)
        }
        completed.await(TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (terminated.compareAndSet(false, true)) {
            Handler(Looper.getMainLooper()).post {
                webViewRef.getAndSet(null)?.let { it.stopLoading(); it.destroy() }
            }
        }
        // Best effort: ein einzelner fehlgeschlagener Preload ist kein Grund fuer
        // WorkManager-Retries - der naechste Push liefert die naechste Chance.
        return Result.success()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun runPreloadInWebView(
        url: String,
        completed: CountDownLatch,
        terminated: AtomicBoolean,
        webViewRef: AtomicReference<WebView?>,
    ) {
        val webView = WebView(applicationContext)
        webViewRef.set(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            private var invoked = false
            override fun onPageFinished(view: WebView, finishedUrl: String) {
                if (invoked) return
                invoked = true
                if (terminated.compareAndSet(false, true)) {
                    webViewRef.compareAndSet(webView, null)
                    view.post { view.stopLoading(); view.destroy() }
                    completed.countDown()
                }
            }
        }
        webView.loadUrl(url)
    }
}
