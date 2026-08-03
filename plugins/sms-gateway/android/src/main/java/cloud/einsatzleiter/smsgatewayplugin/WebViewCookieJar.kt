package cloud.einsatzleiter.smsgatewayplugin

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class WebViewCookieJar(
    private val manager: CookieManager = CookieManager.getInstance()
) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        manager.getCookie(url.toString())
            ?.split(';')
            ?.mapNotNull { Cookie.parse(url, it.trim()) }
            ?: emptyList()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
        manager.flush()
    }
}
