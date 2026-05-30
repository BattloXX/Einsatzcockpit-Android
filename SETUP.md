# Einsatzleiter-Android – Setup-Anleitung

Capacitor-Wrapper-App für [einsatzleiter.cloud](https://einsatzleiter.cloud).
Lädt die PWA direkt vom Server, ergänzt um native Android-Funktionen.

## Voraussetzungen

- Node.js 20+
- Java 17 (Android-Build)
- Android Studio (für lokales Testen)
- Aktives [Firebase-Projekt](https://console.firebase.google.com/) (für FCM-Push)

## 1. Firebase-Projekt einrichten (einmalig)

1. Firebase Console → Projekt anlegen → Android-App hinzufügen
2. Package-Name: `cloud.einsatzleiter.app`
3. `google-services.json` herunterladen
4. Datei ablegen: `android/app/google-services.json`

### Service-Account für Backend
1. Firebase Console → Projekteinstellungen → Service-Accounts
2. „Neuen privaten Schlüssel generieren" → JSON herunterladen
3. Auf dem Backend-Server ablegen (z.B. `/etc/einsatzleiter/fcm-service-account.json`)
4. Im Backend `.env`: `FCM_ENABLED=true`, `FCM_PROJECT_ID=<projekt-id>`, `FCM_CREDENTIALS_PATH=/etc/einsatzleiter/fcm-service-account.json`

## 2. Lokales Projekt initialisieren

```bash
npm install
npx cap add android
npx cap sync android
```

## 3. Android-Berechtigungen eintragen

Inhalt von `android-permissions.xml` in `android/app/src/main/AndroidManifest.xml`
einfügen (nach `<uses-sdk>`, vor `<application>`).

## 4. App-Icon & Splash generieren

Logo aus dem Backend (`app/static/img/logo.png`) nach `assets/logo.png` kopieren, dann:

```bash
npx @capacitor/assets generate --android
```

## 5. Lokal bauen & testen

```bash
# Im Emulator oder angeschlossenem Gerät
npx cap run android

# Oder in Android Studio öffnen
npx cap open android
```

## 6. Release-APK erstellen

```bash
cd android
./gradlew assembleRelease
```

APK liegt dann unter `android/app/build/outputs/apk/release/`.

### Keystore erstellen (einmalig)

```bash
keytool -genkey -v -keystore einsatzleiter.keystore \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias einsatzleiter
```

Keystore **nicht** ins Repo! Als GitHub-Secret `KEYSTORE_FILE` (Base64) hinterlegen.

## 7. CI/CD (GitHub Actions)

Folgende Secrets in GitHub hinterlegen:
- `GOOGLE_SERVICES_JSON` – Inhalt der google-services.json
- `KEYSTORE_FILE` – Base64-kodierter Keystore: `base64 einsatzleiter.keystore`
- `KEYSTORE_PASSWORD` – Keystore-Passwort
- `KEY_ALIAS` – Key-Alias (z.B. `einsatzleiter`)
- `KEY_PASSWORD` – Key-Passwort

Bei jedem Push auf `main` oder Tag `v*` wird automatisch eine signierte APK gebaut.

## Erster Start auf dem Gerät

1. APK sideloaden (Einstellungen → Sicherheit → Unbekannte Quellen)
2. App starten → zeigt Login-QR-Scanner
3. Im Backend: Admin → Geräte-Login → Neues Gerät erstellen → QR scannen
4. App bleibt dauerhaft eingeloggt (Token gespeichert)

## Architektur

Die App lädt `https://einsatzleiter.cloud` in einem WebView. Das Backend-seitige
`native-bridge.js` erkennt Capacitor (`window.Capacitor.isNative`) und stellt
`window.ELNative` bereit — alle nativen Funktionen werden darüber aufgerufen.

```
Android App (Capacitor)
  └─ WebView lädt einsatzleiter.cloud
       └─ native-bridge.js (aus Backend/static/js/)
            ├─ ELNative.keepAwake()       → @capacitor-community/keep-awake
            ├─ ELNative.startLocation()   → @capacitor-community/background-geolocation
            ├─ ELNative.stopLocation()    → ^
            └─ ELNative.scanQr()          → @capacitor-mlkit/barcode-scanning
         FCM Push ← firebase/messaging (Backend → FCM → App)
```
