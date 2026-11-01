# NativeFiles

App-local Capacitor 7 Java plugin, called by the remote PWA through
`window.Capacitor.Plugins.NativeFiles`. No npm dependency or PWA changes.
Sources live in `plugins/native-files/android`; `scripts/prepare-android.sh`
wires the Gradle module into the generated Android project. `android/app/src/main/java/cloud/einsatzleiter/app/MainActivity.java`
registers the plugin before bridge creation and installs the WebView DownloadListener.

## Contract

```js
await window.Capacitor.Plugins.NativeFiles.download({
  url: 'https://einsatzcockpit.com/report.pdf',
  filename: 'Einsatzbericht.pdf' // optional
}); // { started: true, filename: 'Einsatzbericht.pdf' }

await window.Capacitor.Plugins.NativeFiles.openFile({
  url: 'https://einsatzcockpit.com/report.pdf',
  mime: 'application/pdf' // optional
}); // { opened: true }
```

`download` checks the response headers and creates the destination before resolving.
The returned filename is sanitized, using the explicit filename first, otherwise
Content-Disposition / `URLUtil.guessFileName`. It shows `Download gestartet` at start.
Resolution means the transfer started, not that it completed. Later failures show
an error toast; successful completion shows `Download abgeschlossen` and a notification
whose action opens the downloaded file. Notification permission is requested on
Android 13+; denial/disabled notifications still allow downloads and completion toasts.
Android 6–9 requests write permission when needed.

Downloads use a background HttpURLConnection transfer with manually checked redirects
(DownloadManager cannot safely enforce per-hop validation). Android 10+ writes to
`MediaStore.Downloads` with IS_PENDING until complete. Older Android versions write
to the public Downloads folder with collision-safe filenames and notify MediaScanner.
Incomplete destinations are deleted. The DownloadListener uses exactly this flow
for ordinary WebView downloads, including attachment responses; blob/data URLs are ignored.
Transfers belong to the app process and do not survive process termination.

`openFile` downloads into `getCacheDir()/shared/`, then launches an ACTION_VIEW chooser
with a FileProvider URI and temporary read permission. MIME selection uses the explicit
parameter, then response Content-Type, with `application/pdf` for unknown/octet-stream
types. The existing `${applicationId}.fileprovider` cache-path already covers `shared/`.
Cache files older than one day are removed on each transfer call. Files with no viewer
or failed transfers are removed immediately. `{ opened: true }` means the chooser was
launched, not that the user selected a viewer.

## Errors

Promises reject with these exact messages (no URLs or cookies are included):

- `Ungültige URL: Nur HTTPS auf einsatzcockpit.com und deren Subdomains ist erlaubt`
- `Keine App zum Öffnen von PDF-Dateien installiert` (openFile: no viewer; PWA may fall back to download)
- `Datei konnte nicht geöffnet werden`
- `Berechtigung zum Speichern in Downloads fehlt`
- `Download abgebrochen` (activity destroyed while waiting for permission)
- `Download fehlgeschlagen (HTTP NNN)` (actual three-digit HTTP status)
- `Datei ist größer als 100 MB`
- `Zu viele Weiterleitungen`
- `Zeitüberschreitung beim Download`
- `Download unvollständig`
- `Datei konnte nicht gespeichert werden`
- `Download fehlgeschlagen` (other network/storage errors)

For `download`, errors after the start result are reported by toast instead of
rejecting an already resolved promise. The DownloadListener reports errors by toast.

## Security

Only HTTPS on `einsatzcockpit.com`, and `*.einsatzcockpit.com` is permitted, matching `capacitor.config.ts`
server.allowNavigation. Changes to that allowlist must also update NativeFilePolicy.
Userinfo, nonstandard ports, suffix lookalikes and foreign hosts are rejected.
Every redirect target is validated before connecting; there are at most ten redirects.
Cookies come from CookieManager for each validated hop's own URL, never from the
previous host. Both paths use the WebView user agent. No URLs/cookies are logged
by the plugin or included in errors/notifications.

Both transfers enforce a 100 MiB limit from headers and while streaming, plus connect,
read and transfer timeouts. Filenames cannot contain slashes, backslashes, traversal
`..`, or control characters. Cache names are unique and file grants are read-only.
