package cloud.einsatzleiter.nativefiles;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

final class NativeFilesTransfer {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final long TRANSFER_TIMEOUT_MS = 5L * 60 * 1000;

    private NativeFilesTransfer() {}

    static void cleanCache(Context context) {
        File[] files = new File(context.getCacheDir(), "shared").listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - DAY_MS;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) file.delete();
        }
    }

    static Response connect(String url, String userAgent) throws IOException {
        URL current = NativeFilePolicy.checkedUrl(url);
        for (int hop = 0; hop <= 10; hop++) {
            // Do not reuse the previous hop's cookies, even between allowed hosts.
            current = NativeFilePolicy.checkedUrl(current.toExternalForm());
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept-Encoding", "identity");
            String cookies = CookieManager.getInstance().getCookie(current.toExternalForm());
            if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
            boolean retained = false;
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    current = NativeFilePolicy.redirect(current, connection.getHeaderField("Location"));
                    continue;
                }
                if (status < 200 || status >= 300) throw new IOException("Download fehlgeschlagen (HTTP " + status + ")");
                if (contentLength(connection) > NativeFilePolicy.MAX_BYTES) {
                    throw new IOException("Datei ist größer als 100 MB");
                }
                retained = true;
                return new Response(connection, current);
            } finally {
                if (!retained) connection.disconnect();
            }
        }
        throw new IOException("Zu viele Weiterleitungen");
    }

    static void copy(Response response, OutputStream output) throws IOException {
        long total = 0;
        long deadline = android.os.SystemClock.elapsedRealtime() + TRANSFER_TIMEOUT_MS;
        try (InputStream input = response.connection.getInputStream()) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > NativeFilePolicy.MAX_BYTES) throw new IOException("Datei ist größer als 100 MB");
                if (android.os.SystemClock.elapsedRealtime() > deadline) throw new IOException("Zeitüberschreitung beim Download");
                output.write(buffer, 0, count);
            }
            long expected = contentLength(response.connection);
            if (expected >= 0 && total != expected) throw new IOException("Download unvollständig");
        }
    }

    private static long contentLength(HttpURLConnection connection) {
        // URLConnection.getContentLengthLong() requires API 24; the app supports API 23.
        try {
            return Long.parseLong(connection.getHeaderField("Content-Length"));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static File cacheFile(Context context, Response response) throws IOException {
        File directory = new File(context.getCacheDir(), "shared");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Datei konnte nicht gespeichert werden");
        File file = File.createTempFile("native-", "-" + response.filename(null, null, null), directory);
        boolean complete = false;
        try {
            try (OutputStream output = new FileOutputStream(file)) {
                copy(response, output);
            }
            complete = true;
        } finally {
            if (!complete) file.delete();
        }
        return file;
    }

    static final class Response implements AutoCloseable {
        final HttpURLConnection connection;
        private final URL url;

        Response(HttpURLConnection connection, URL url) {
            this.connection = connection;
            this.url = url;
        }

        String mime(String requested) {
            String type = requested;
            if (type == null || type.trim().isEmpty()) type = connection.getContentType();
            if (type != null) type = type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (type == null || !type.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
                    || type.equals("application/octet-stream")) return "application/pdf";
            return type;
        }

        String filename(String requested, String disposition, String fallbackMime) {
            if (requested != null && !requested.trim().isEmpty()) return NativeFilePolicy.safeFilename(requested);
            String header = connection.getHeaderField("Content-Disposition");
            if (header == null) header = disposition;
            return NativeFilePolicy.safeFilename(URLUtil.guessFileName(url.toExternalForm(), header, mime(fallbackMime)));
        }

        @Override
        public void close() {
            connection.disconnect();
        }
    }

    static final class Destination implements AutoCloseable {
        final String filename;
        final Uri uri;
        final OutputStream output;
        private final Context context;
        private final File legacyFile;
        private final String mime;
        private boolean complete;

        Destination(Context context, String filename, String mime) throws IOException {
            this.context = context;
            this.mime = mime;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("Datei konnte nicht gespeichert werden");
                legacyFile = null;
                String storedName = filename;
                try {
                    try (Cursor cursor = context.getContentResolver().query(uri,
                            new String[] { MediaStore.Downloads.DISPLAY_NAME }, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) storedName = cursor.getString(0);
                    }
                    output = context.getContentResolver().openOutputStream(uri);
                    if (output == null) throw new IOException("Datei konnte nicht gespeichert werden");
                } catch (Exception error) {
                    context.getContentResolver().delete(uri, null, null);
                    throw new IOException("Datei konnte nicht gespeichert werden");
                }
                this.filename = storedName;
            } else {
                File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Datei konnte nicht gespeichert werden");
                int dot = filename.lastIndexOf('.');
                String stem = dot > 0 ? filename.substring(0, dot) : filename;
                String extension = dot > 0 ? filename.substring(dot) : "";
                File file = new File(directory, filename);
                int suffix = 0;
                while (!file.createNewFile()) file = new File(directory, stem + " (" + (++suffix) + ")" + extension);
                legacyFile = file;
                this.filename = file.getName();
                try {
                    uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
                    output = new FileOutputStream(file);
                } catch (Exception error) {
                    file.delete();
                    throw new IOException("Datei konnte nicht gespeichert werden");
                }
            }
        }

        void publish() throws IOException {
            output.close();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                if (context.getContentResolver().update(uri, values, null, null) != 1) {
                    throw new IOException("Datei konnte nicht gespeichert werden");
                }
            } else {
                MediaScannerConnection.scanFile(context, new String[] { legacyFile.getAbsolutePath() }, new String[] { mime }, null);
            }
            complete = true;
        }

        @Override
        public void close() throws IOException {
            try {
                output.close();
            } finally {
                if (!complete) {
                    if (legacyFile != null) legacyFile.delete();
                    else context.getContentResolver().delete(uri, null, null);
                }
            }
        }
    }
}
