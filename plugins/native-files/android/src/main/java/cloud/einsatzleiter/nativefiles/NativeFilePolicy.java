package cloud.einsatzleiter.nativefiles;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class NativeFilePolicy {
    static final String INVALID_URL = "Ungültige URL: Nur HTTPS auf einsatzcockpit.com und deren Subdomains ist erlaubt";
    static final long MAX_BYTES = 100L * 1024 * 1024;

    private NativeFilePolicy() {}

    // Mirrors capacitor.config.ts server.allowNavigation. Check before EVERY connection.
    static URL checkedUrl(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (host == null || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IllegalArgumentException(INVALID_URL);
            }
            host = host.toLowerCase(Locale.ROOT);
            if (!(host.equals("einsatzcockpit.com") || host.endsWith(".einsatzcockpit.com"))) {
                throw new IllegalArgumentException(INVALID_URL);
            }
            return uri.toURL();
        } catch (Exception ignored) {
            // Never include the supplied URL (which may contain access tokens) in errors.
            throw new IllegalArgumentException(INVALID_URL);
        }
    }

    static URL redirect(URL current, String location) {
        try {
            if (location == null || location.trim().isEmpty()) throw new IllegalArgumentException();
            return checkedUrl(new URL(current, location).toExternalForm());
        } catch (Exception ignored) {
            throw new IllegalArgumentException(INVALID_URL);
        }
    }

    static String safeFilename(String value) {
        String name = value == null ? "" : value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replace("..", "_").trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.isEmpty()) name = "download.pdf";
        if (name.getBytes(StandardCharsets.UTF_8).length > 180) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
            String stem = extension.isEmpty() ? name : name.substring(0, dot);
            while ((stem + extension).getBytes(StandardCharsets.UTF_8).length > 180) {
                stem = stem.substring(0, stem.offsetByCodePoints(stem.length(), -1));
            }
            name = (stem + extension).replace("..", "_");
        }
        return name;
    }
}
