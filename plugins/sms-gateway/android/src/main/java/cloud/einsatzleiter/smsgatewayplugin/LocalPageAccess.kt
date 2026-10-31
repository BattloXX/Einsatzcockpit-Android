package cloud.einsatzleiter.smsgatewayplugin

import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall

internal fun Plugin.withLocalPage(call: PluginCall, action: () -> Unit) {
    // WebView.url nur im UI-Thread lesen; Prüfung und Aktion zusammen ausführen,
    // ohne blockierendes Warten oder einen nach Navigation veralteten Origin-Cache.
    bridge.executeOnMainThread {
        if (!UrlPolicy.isLocalPage(bridge.webView.url, bridge.localUrl)) {
            call.reject("Nur von der lokalen App-Seite erlaubt")
        } else {
            action()
        }
    }
}
