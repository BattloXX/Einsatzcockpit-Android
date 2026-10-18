package cloud.einsatzleiter.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private boolean started;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Cold starts are handled by App.getLaunchUrl() in the local launcher.
        started = true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        setIntent(intent);
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
