package cloud.einsatzleiter.nativefiles;

import static org.junit.Assert.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class NativeFilePolicyTest {
    @Test
    public void permitsOnlyConfiguredHttpsHosts() {
        for (String host : new String[] { "einsatzcockpit.com", "files.einsatzcockpit.com",
                "a.b.einsatzcockpit.com", "EINSATZCOCKPIT.COM" }) {
            assertNotNull(NativeFilePolicy.checkedUrl("https://" + host + ":443/report.pdf"));
        }
        for (String url : new String[] { null, "", "https://", "http://einsatzcockpit.com/a",
                "https://einsatzcockpit.com.evil.test/a", "https://evileinsatzcockpit.com/a",
                "https://einsatzcockpit.com@evil.test/a", "https://user@einsatzcockpit.com/a",
                "https://einsatzcockpit.com:8443/a", "https://einsatzcockpit.com./a",
                "https://einsatzcockpit.com\\@evil.test/a", "https://127.0.0.1/a",
                "blob:https://einsatzcockpit.com/a", "data:application/pdf,test",
                "https://%65insatzcockpit.com/a", "https://evil.test/?token=secret",
                "https://einsatzleiter.cloud/a" }) {
            reject(url);
        }
    }

    @Test
    public void checksRelativeAndAbsoluteRedirectTargets() throws Exception {
        URL base = NativeFilePolicy.checkedUrl("https://einsatzcockpit.com/files/a");
        assertEquals("https://einsatzcockpit.com/report.pdf", NativeFilePolicy.redirect(base, "../report.pdf").toString());
        assertEquals("files.einsatzcockpit.com", NativeFilePolicy.redirect(base, "https://files.einsatzcockpit.com/a").getHost());
        for (String location : new String[] { "//evil.test/a", "http://einsatzcockpit.com/a", null, "", "https://evil.test/a" }) {
            try {
                NativeFilePolicy.redirect(base, location);
                fail("Redirect must be rejected");
            } catch (IllegalArgumentException error) {
                assertEquals(NativeFilePolicy.INVALID_URL, error.getMessage());
            }
        }
    }

    @Test
    public void removesTraversalSeparatorsAndControlCharacters() {
        for (String name : new String[] { "../../report.pdf", "..\\..\\report.pdf", "/report.pdf",
                "a\r\n.pdf", "....", "", null, "a".repeat(200) + ".pdf",
                "ä".repeat(200) + ".pdf", "a".repeat(175) + ".more.pdf", "🚒".repeat(100) + ".pdf" }) {
            String safe = NativeFilePolicy.safeFilename(name);
            assertFalse(safe.isEmpty());
            assertFalse(safe.contains("/"));
            assertFalse(safe.contains("\\"));
            assertFalse(safe.contains(".."));
            assertFalse(safe.contains("\r"));
            assertFalse(safe.contains("\n"));
            assertTrue(safe.length() <= 180);
            assertTrue(safe.getBytes(StandardCharsets.UTF_8).length <= 180);
        }
        assertEquals("Einsatzübersicht.pdf", NativeFilePolicy.safeFilename("Einsatzübersicht.pdf"));
        assertTrue(NativeFilePolicy.safeFilename("a".repeat(200) + ".pdf").endsWith(".pdf"));
    }

    private void reject(String url) {
        try {
            NativeFilePolicy.checkedUrl(url);
            fail("URL must be rejected");
        } catch (IllegalArgumentException error) {
            assertEquals(NativeFilePolicy.INVALID_URL, error.getMessage());
        }
    }
}
