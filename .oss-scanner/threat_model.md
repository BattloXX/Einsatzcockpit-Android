# Threat model – Einsatzcockpit Android app

## What this project does and where untrusted input enters

A Capacitor 7 wrapper app (`cloud.einsatzleiter.app`) for the Einsatzcockpit web application
(https://github.com/BattloXX/Einsatzcockpit). It is distributed by sideloading, not via the
Play Store. At start, `www/index.html` acts as a local launcher and decides between two
modes:

- **unit/device mode**: navigates the WebView to the remote PWA on `einsatzcockpit.com`
- **SMS gateway mode**: `www/gateway.html`

Native functionality lives in the local Capacitor plugin `plugins/sms-gateway` (Kotlin):

- **SMS gateway** (`SmsGatewayService`, `SmsGatewayPlugin`). A foreground service holds a
  WebSocket to the server, authenticated with a gateway bearer token stored in
  `CapacitorStorage` SharedPreferences. It **sends SMS on server instruction** and reports
  received SMS.
- **Push/FCM** (`EinsatzFirebaseMessagingService`, `FcmTokenRegistration`): alarm
  notifications, a live incident notification and widgets (`EinsatzLive*`, `GslLiveState`,
  `GslQueueState`, `EcpFahrtWidget`).
- **Auto-update** (`AutoUpdateWorker`, `AutoUpdateInstallReceiver`) in the auto-update build
  flavour. It fetches GitHub releases of this repo, downloads the APK and installs it via
  `PackageInstaller`, without user action where Android allows it.
- **Offline caches**: contacts (Room DB, `kontakte/`) and objects (`ObjektOfflineSyncWorker`),
  synced with the WebView session cookie (`WebViewCookieJar`).
- **Background location, keep-alive service, boot receiver, barcode scanning, DND/alarm
  channel**.

Untrusted input:

- Content loaded into the WebView from the allowed origins (`allowNavigation`:
  `einsatzcockpit.com`, `*.einsatzcockpit.com`). Any page there can call the Capacitor bridge and the plugin
  methods (`@PluginMethod`).
- FCM push payloads.
- WebSocket messages to the SMS gateway.
- Responses of the duty-state, live-state, contact-sync and object-sync APIs.
- GitHub release metadata and downloaded APKs (auto-update).
- Intents and broadcasts from other apps on the device, if any component is reachable.
- Deep/native links.

## Components that matter most / least

Most important:

1. **SMS sending**:
   - any way to make the device send SMS other than through the authenticated gateway
     connection
   - leaking the gateway token (logs, intents, URLs: note `?token=` in the WebSocket URL)
2. **Auto-update**: installing an APK that is not an official release, including
   downgrade, path traversal on download, TOCTOU between download and install, and spoofed
   release metadata.
3. **WebView/bridge exposure**:
   - navigation or redirects to non-allowed origins while keeping bridge access
   - mixed content
   - cleartext (it is disabled, `cleartext: false`)
   - JS injection into native methods
   - plugin methods that do privileged things (wipe/export contacts, start/stop gateway,
     battery-optimisation/DND changes) without user intent
4. **Exported components**: receivers, services or activities reachable by other apps.
   `PendingIntent` mutability.
5. **Data at rest**: contacts and object cache, session cookies, tokens in SharedPreferences,
   backups (`allowBackup`), logs.

Less important / out of scope:

- The remote web application. It is scanned separately as the Einsatzcockpit repository.
- Generated `android/` project files (created by `npx cap add android` at build time).
- Third-party Capacitor plugins unless misused by our code.
- Root, physical access, or a compromised server. A compromised server is trusted by design
  for content, but should not lead to native code execution outside the intended update
  path.

## How to exercise it

- `scripts/prepare-android.sh` creates the Android project exactly as CI does.
- `cd android && ./gradlew assembleDebug` builds the debug APK into
  `android/app/build/outputs/apk/debug/`.
- Kotlin sources: `plugins/sms-gateway/android/src/main/java/cloud/einsatzleiter/smsgatewayplugin/`.
  Manifest: `plugins/sms-gateway/android/src/main/AndroidManifest.xml` plus permissions
  patched by `scripts/prepare-android.sh`.
- There is no emulator in the image. Static analysis of Kotlin, the manifests and the
  `www/` launcher is expected.

## How you rate severity

- **Critical**:
  - SMS sending or app/APK installation triggerable by another app, a non-allowed website or
    a push without valid server authentication
  - arbitrary code execution in the app
  - installation of a non-release APK through auto-update
- **High**:
  - leakage of the gateway token, session cookie or FCM credentials to other apps or
    origins
  - bridge access from non-allowed origins
  - exported components that allow privileged actions
  - reading the contact/object cache from other apps
- **Medium**:
  - DoS of the alarm notification path (missed alarms) triggerable remotely
  - sensitive data in logs
  - weak `PendingIntent` usage without a demonstrated exploit
- **Low**: hardening issues without a demonstrated attack.

## Reports and patches

- Please give minimal patches in Kotlin or TypeScript, plus a description of the attacker
  model.
- Comments and UI strings are in German.

## Anything to leave alone

- Do not contact the production servers (`einsatzcockpit.com`) or GitHub releases during
  analysis.
- The placeholder `google-services.json` written by the build is intentionally fake.
