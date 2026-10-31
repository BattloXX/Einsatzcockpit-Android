package cloud.einsatzleiter.smsgatewayplugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {
    @Test
    fun allowsHttpsOnBothDomainsAndTheirSubdomains() {
        listOf(
            "https://einsatzcockpit.com",
            "https://einsatzcockpit.com:443/path?token=example#fragment",
            "https://board.einsatzcockpit.com/",
            "https://a.b.einsatzcockpit.com/",
            "https://einsatzleiter.cloud/",
            "https://board.einsatzleiter.cloud:443/",
            "HTTPS://BOARD.EINSATZCOCKPIT.COM/",
            "hTtPs://EINSATZLEITER.CLOUD/"
        ).forEach { assertTrue(it, UrlPolicy.isAllowedRemoteUrl(it)) }
    }

    @Test
    fun rejectsLookalikeAndUnrelatedHosts() {
        listOf(
            "https://evil-einsatzcockpit.com/",
            "https://einsatzcockpit.com.evil.org/",
            "https://evil-einsatzleiter.cloud/",
            "https://einsatzleiter.cloud.evil.org/",
            "https://example.org/",
            "https://localhost/"
        ).forEach { assertFalse(it, UrlPolicy.isAllowedRemoteUrl(it)) }
    }

    @Test
    fun rejectsOtherSchemesUserInfoAndNonStandardPorts() {
        listOf(
            "http://einsatzcockpit.com/",
            "javascript:alert(1)",
            "file:///einsatzcockpit.com/",
            "intent://einsatzcockpit.com/",
            "data:text/html,example",
            "//einsatzcockpit.com/",
            "https://user@einsatzcockpit.com/",
            "https://user:password@einsatzleiter.cloud/",
            "https://@einsatzcockpit.com/",
            "https://einsatzcockpit.com:8443/",
            "https://einsatzleiter.cloud:80/"
        ).forEach { assertFalse(it, UrlPolicy.isAllowedRemoteUrl(it)) }
    }

    @Test
    fun rejectsMissingAndMalformedUrlsWithoutThrowing() {
        listOf(null, "", " ", "https://", "https://einsatzcockpit.com/%zz",
            "https://einsatzcockpit.com:invalid/", "https://einsatzcockpit.com\\@evil.org/")
            .forEach { assertFalse(it, UrlPolicy.isAllowedRemoteUrl(it)) }
    }

    @Test
    fun localPageComparesOnlySchemeHostAndPort() {
        assertTrue(UrlPolicy.isLocalPage("https://localhost/index.html?x=1#fragment", "https://localhost"))
        assertTrue(UrlPolicy.isLocalPage("HTTPS://LOCALHOST/gateway.html", "https://localhost"))
        assertTrue(UrlPolicy.isLocalPage("https://localhost:443/index.html", "https://localhost:443"))
        assertFalse(UrlPolicy.isLocalPage("http://localhost/", "https://localhost"))
        assertFalse(UrlPolicy.isLocalPage("https://einsatzcockpit.com/", "https://localhost"))
        assertFalse(UrlPolicy.isLocalPage("https://localhost.evil.org/", "https://localhost"))
        assertFalse(UrlPolicy.isLocalPage("https://localhost:8443/", "https://localhost"))
        assertFalse(UrlPolicy.isLocalPage("https://localhost:443/", "https://localhost"))
        assertFalse(UrlPolicy.isLocalPage("https://user@localhost/", "https://localhost"))
    }

    @Test
    fun localPageRejectsMissingAndMalformedOrigins() {
        listOf(null, "", " ", "https://", "/index.html", "javascript:alert(1)")
            .forEach {
                assertFalse(UrlPolicy.isLocalPage(it, "https://localhost"))
                assertFalse(UrlPolicy.isLocalPage("https://localhost", it))
            }
        assertFalse(UrlPolicy.isLocalPage(null, null))
        assertFalse(UrlPolicy.isLocalPage("/index.html", "/index.html"))
    }
}
