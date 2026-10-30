package cloud.einsatzleiter.app;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.getcapacitor.BridgeActivity;
import cloud.einsatzleiter.nativefiles.NativeFilesPlugin;

public class MainActivity extends BridgeActivity {
    // Gleicher Wert wie DeviceAccessState.EXTRA_RECONNECT im Plugin (Hinweis "Gerätezugang ungültig").
    private static final String EXTRA_RECONNECT = "cloud.einsatzleiter.smsgatewayplugin.EXTRA_RECONNECT";
    private static final long REFRESH_TIMEOUT_MS = 8000L;

    private boolean started;
    private SwipeRefreshLayout swipe;
    // Von der Seite (native-bridge.js) gesetzt: Karte, Drag-and-Drop, Dialog oder Formular mit
    // Eingaben aktiv -> kein Pull-to-Refresh, damit weder Bedienung gestört noch Eingaben verworfen werden.
    private volatile boolean pullBlocked;
    private String pullPageUrl;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stopSpinner = () -> {
        if (swipe != null) swipe.setRefreshing(false);
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NativeFilesPlugin.class);
        super.onCreate(savedInstanceState);
        // Cold starts are handled by App.getLaunchUrl() in the local launcher.
        started = true;
        installPullToRefresh();
        installDownloadListener();
    }

    private void installDownloadListener() {
        if (bridge == null || bridge.getWebView() == null) return;
        if (bridge.getPlugin("NativeFiles") == null) return;
        NativeFilesPlugin files = (NativeFilesPlugin) bridge.getPlugin("NativeFiles").getInstance();
        bridge.getWebView().setDownloadListener((url, userAgent, disposition, mime, length) ->
                files.downloadFromWebView(url, userAgent, disposition, mime));
    }

    /** Legt ein SwipeRefreshLayout um die vorhandene WebView (keine zweite WebView, kein Reload-Zwang). */
    private void installPullToRefresh() {
        if (bridge == null) return;
        final WebView webView = bridge.getWebView();
        if (webView == null || !(webView.getParent() instanceof ViewGroup)) return;
        final ViewGroup parent = (ViewGroup) webView.getParent();
        final int index = parent.indexOfChild(webView);
        final ViewGroup.LayoutParams params = webView.getLayoutParams();
        parent.removeViewAt(index);
        swipe = new SwipeRefreshLayout(this);
        swipe.addView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        parent.addView(swipe, index, params);
        swipe.setOnChildScrollUpCallback((p, child) -> {
            // JS kennt innere Scrollbereiche. Alte Sperren gelten nicht fuer eine neue Seite.
            if (pullPageUrl != null && pullPageUrl.equals(webView.getUrl())) return pullBlocked;
            return webView.canScrollVertically(-1);
        });
        swipe.setOnRefreshListener(this::refreshWebContent);
        webView.addJavascriptInterface(new PullToRefreshBridge(), "ECPtr");
    }

    private void refreshWebContent() {
        if (swipe == null || bridge == null) return;
        if (!isOnline()) {
            swipe.setRefreshing(false);
            Toast.makeText(this, "Keine Internetverbindung – Aktualisierung nicht möglich.", Toast.LENGTH_LONG).show();
            return;
        }
        final WebView webView = bridge.getWebView();
        handler.removeCallbacks(stopSpinner);
        handler.postDelayed(stopSpinner, REFRESH_TIMEOUT_MS);
        // Bevorzugt die Resync-Funktionen der Seite (Board, Lage, Einheit); Seiten ohne Brücke werden neu geladen.
        webView.evaluateJavascript(
                "(function(){try{if(window.ELNative&&typeof window.ELNative.refresh==='function'){"
                        + "window.ELNative.refresh();return 'js';}}catch(e){}return 'reload';})()",
                result -> {
                    if (!"\"js\"".equals(result)) {
                        webView.reload();
                    }
                });
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /** JavaScript-Schnittstelle der Seite; setzt nur zwei Zustände und hat keinen Zugriff auf Daten. */
    private class PullToRefreshBridge {
        @JavascriptInterface
        public void setBlocked(boolean blocked) {
            handler.post(() -> {
                if (bridge == null) return;
                pullPageUrl = bridge.getWebView().getUrl();
                pullBlocked = blocked;
            });
        }

        @JavascriptInterface
        public void done() {
            handler.post(() -> {
                handler.removeCallbacks(stopSpinner);
                if (swipe != null) swipe.setRefreshing(false);
            });
        }

        @JavascriptInterface
        public void failed(String message) {
            handler.post(() -> {
                handler.removeCallbacks(stopSpinner);
                if (swipe != null) swipe.setRefreshing(false);
                String text = (message == null || message.isEmpty())
                        ? "Aktualisierung fehlgeschlagen." : message;
                Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show();
            });
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        setIntent(intent);
        // Hinweis "Gerätezugang ungültig": zurück zur lokalen Startseite, die erneutes Verbinden anbietet.
        if (started && bridge != null && intent.getBooleanExtra(EXTRA_RECONNECT, false)) {
            final String launcher = bridge.getLocalUrl() + "/index.html";
            bridge.getWebView().post(() -> bridge.getWebView().loadUrl(launcher));
            return;
        }
        Uri uri = intent.getData();
        // Only the verified domain may navigate the existing authenticated WebView.
        if (started && bridge != null && Intent.ACTION_VIEW.equals(intent.getAction())
                && uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && "einsatzcockpit.com".equalsIgnoreCase(uri.getHost())
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)) {
            bridge.getWebView().post(() -> bridge.getWebView().loadUrl(uri.toString()));
        }
    }
}
