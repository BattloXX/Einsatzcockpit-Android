package cloud.einsatzleiter.nativefiles;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@CapacitorPlugin(name = "NativeFiles")
public class NativeFilesPlugin extends Plugin {
    private static final String NO_VIEWER = "Keine App zum Öffnen von PDF-Dateien installiert";
    private static final String CHANNEL = "native_files_downloads";
    private static final AtomicInteger NOTIFICATION_ID = new AtomicInteger(10000);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService transfers = Executors.newFixedThreadPool(2);
    private final List<Download> awaitingPermission = new ArrayList<>();
    private ActivityResultLauncher<String[]> permissions;
    private boolean askedNotifications;

    @Override
    public void load() {
        permissions = getActivity().getActivityResultRegistry().register(
                "native-files-permissions", new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    List<Download> pending = new ArrayList<>(awaitingPermission);
                    awaitingPermission.clear();
                    for (Download download : pending) {
                        if (needsStoragePermission()) download.fail("Berechtigung zum Speichern in Downloads fehlt");
                        else startDownload(download);
                    }
                });
    }

    @PluginMethod
    public void download(PluginCall call) {
        main.post(() -> prepareDownload(new Download(call.getString("url"), call.getString("filename"),
                null, null, getBridge().getWebView().getSettings().getUserAgentString(), call)));
    }

    // Called by MainActivity's DownloadListener. Blob/data URLs are deliberately ignored.
    public void downloadFromWebView(String url, String userAgent, String disposition, String mime) {
        if (url != null && (url.regionMatches(true, 0, "blob:", 0, 5)
                || url.regionMatches(true, 0, "data:", 0, 5))) return;
        main.post(() -> prepareDownload(new Download(url, null, disposition, mime,
                userAgent == null ? getBridge().getWebView().getSettings().getUserAgentString() : userAgent, null)));
    }

    private boolean needsStoragePermission() {
        return Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(getContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    private void prepareDownload(Download download) {
        try {
            NativeFilePolicy.checkedUrl(download.url);
        } catch (IllegalArgumentException error) {
            download.fail(NativeFilePolicy.INVALID_URL);
            return;
        }
        List<String> needed = new ArrayList<>();
        if (needsStoragePermission()) needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications && ContextCompat.checkSelfPermission(
                getContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
            askedNotifications = true;
        }
        if (!awaitingPermission.isEmpty() || !needed.isEmpty()) {
            boolean launch = awaitingPermission.isEmpty();
            awaitingPermission.add(download);
            if (launch) permissions.launch(needed.toArray(new String[0]));
        } else {
            startDownload(download);
        }
    }

    private void startDownload(Download download) {
        transfers.execute(() -> {
            NativeFilesTransfer.cleanCache(getContext());
            try (NativeFilesTransfer.Response response = NativeFilesTransfer.connect(download.url, download.userAgent)) {
                String mime = response.mime(download.mime);
                String filename = response.filename(download.filename, download.disposition, download.mime);
                try (NativeFilesTransfer.Destination destination = new NativeFilesTransfer.Destination(getContext(), filename, mime)) {
                    main.post(() -> {
                        Toast.makeText(getContext(), "Download gestartet", Toast.LENGTH_SHORT).show();
                        if (download.call != null) {
                            JSObject result = new JSObject();
                            result.put("started", true);
                            result.put("filename", destination.filename);
                            download.call.resolve(result);
                        }
                        download.started = true;
                    });
                    NativeFilesTransfer.copy(response, destination.output);
                    destination.publish();
                    main.post(() -> completed(destination.uri, destination.filename, mime));
                }
            } catch (Exception error) {
                main.post(() -> download.fail(safeError(error)));
            }
        });
    }

    @PluginMethod
    public void openFile(PluginCall call) {
        String url = call.getString("url");
        try {
            NativeFilePolicy.checkedUrl(url);
        } catch (IllegalArgumentException error) {
            call.reject(NativeFilePolicy.INVALID_URL);
            return;
        }
        main.post(() -> {
            String userAgent = getBridge().getWebView().getSettings().getUserAgentString();
            transfers.execute(() -> {
                NativeFilesTransfer.cleanCache(getContext());
                File file = null;
                try (NativeFilesTransfer.Response response = NativeFilesTransfer.connect(url, userAgent)) {
                    String mime = response.mime(call.getString("mime"));
                    file = NativeFilesTransfer.cacheFile(getContext(), response);
                    File ready = file;
                    main.post(() -> openCachedFile(call, ready, mime));
                } catch (Exception error) {
                    if (file != null) file.delete();
                    main.post(() -> call.reject(safeError(error)));
                }
            });
        });
    }

    private Intent viewIntent(Uri uri, String mime) {
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri("Datei", uri));
        return view;
    }

    private void openCachedFile(PluginCall call, File file, String mime) {
        try {
            Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", file);
            Intent view = viewIntent(uri, mime);
            if (view.resolveActivity(getActivity().getPackageManager()) == null) throw new ActivityNotFoundException();
            getActivity().startActivity(Intent.createChooser(view, "Datei öffnen")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            JSObject result = new JSObject();
            result.put("opened", true);
            call.resolve(result);
        } catch (ActivityNotFoundException error) {
            file.delete();
            call.reject(NO_VIEWER);
        } catch (Exception error) {
            file.delete();
            call.reject("Datei konnte nicht geöffnet werden");
        }
    }

    private void completed(Uri uri, String filename, String mime) {
        Toast.makeText(getContext(), "Download abgeschlossen", Toast.LENGTH_LONG).show();
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(getContext(),
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = (NotificationManager) getContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Datei-Downloads", NotificationManager.IMPORTANCE_DEFAULT));
        }
        int id = NOTIFICATION_ID.incrementAndGet();
        Intent chooser = Intent.createChooser(viewIntent(uri, mime), "Datei öffnen")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent open = PendingIntent.getActivity(getContext(), id, chooser,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder notification = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(getContext(), CHANNEL) : new Notification.Builder(getContext());
        notification.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download abgeschlossen").setContentText(filename)
                .setContentIntent(open).setAutoCancel(true);
        try {
            manager.notify(id, notification.build());
        } catch (SecurityException ignored) {
            // Notification permission may be revoked while a transfer is running.
        }
    }

    private static String safeError(Exception error) {
        String message = error.getMessage();
        if (NativeFilePolicy.INVALID_URL.equals(message) || "Datei ist größer als 100 MB".equals(message)
                || "Zu viele Weiterleitungen".equals(message) || "Zeitüberschreitung beim Download".equals(message)
                || "Download unvollständig".equals(message) || "Datei konnte nicht gespeichert werden".equals(message)
                || (message != null && message.matches("Download fehlgeschlagen \\(HTTP [0-9]{3}\\)"))) return message;
        return "Download fehlgeschlagen";
    }

    @Override
    protected void handleOnDestroy() {
        permissions.unregister();
        for (Download download : awaitingPermission) download.fail("Download abgebrochen");
        awaitingPermission.clear();
        transfers.shutdown();
    }

    private final class Download {
        final String url;
        final String filename;
        final String disposition;
        final String mime;
        final String userAgent;
        final PluginCall call;
        boolean started;

        Download(String url, String filename, String disposition, String mime, String userAgent, PluginCall call) {
            this.url = url;
            this.filename = filename;
            this.disposition = disposition;
            this.mime = mime;
            this.userAgent = userAgent;
            this.call = call;
        }

        void fail(String message) {
            if (call != null && !started) call.reject(message);
            else Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
        }
    }
}
