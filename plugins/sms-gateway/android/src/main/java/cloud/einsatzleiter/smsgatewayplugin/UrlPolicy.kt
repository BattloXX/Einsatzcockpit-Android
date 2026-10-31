package cloud.einsatzleiter.smsgatewayplugin

import java.net.URI
import java.util.Locale

object UrlPolicy {
    // Übergangsdomain zentral halten, bis einsatzleiter.cloud entfällt.
    private val allowedRemoteHosts = listOf("einsatzcockpit.com", "einsatzleiter.cloud")

    fun isAllowedRemoteUrl(url: String?): Boolean {
        return try {
            val uri = URI(url ?: return false)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return false
            uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port == 443) &&
                allowedRemoteHosts.any { host == it || host.endsWith(".$it") }
        } catch (_: Exception) {
            false
        }
    }

    fun isLocalPage(pageUrl: String?, localUrl: String?): Boolean {
        return try {
            val page = URI(pageUrl ?: return false)
            val local = URI(localUrl ?: return false)
            page.scheme != null && page.host != null &&
                page.rawUserInfo == null && local.rawUserInfo == null &&
                page.scheme.equals(local.scheme, ignoreCase = true) &&
                page.host.equals(local.host, ignoreCase = true) && page.port == local.port
        } catch (_: Exception) {
            false
        }
    }
}
