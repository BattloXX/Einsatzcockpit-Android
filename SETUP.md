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

### Keystore erstellen (einmalig)

**Wichtig:** Keytool direkt über die Kommandozeile aufrufen, **nicht** über den Android Studio-Dialog. Android Studio erstellt PKCS12-Dateien mit veralteten Algorithmen (PBEWithSHA1AndDESede), die mit Java 17+ im CI inkompatibel sind.

```bash
# Windows (cmd.exe):
"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v ^
  -keystore einsatzleiter.keystore -alias einsatzleiter ^
  -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 9999 ^
  -storetype PKCS12 ^
  -dname "CN=Name, OU=Org, O=Org, L=Stadt, ST=Bundesland, C=AT"

# Linux/macOS:
keytool -genkeypair -v \
  -keystore einsatzleiter.keystore -alias einsatzleiter \
  -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 9999 \
  -storetype PKCS12 \
  -dname "CN=Name, OU=Org, O=Org, L=Stadt, ST=Bundesland, C=AT"
```

Den Keystore **nicht** ins Repo committen.

### Lokale APK bauen

```bash
cd android
./gradlew assembleRelease
```

APK liegt unter `android/app/build/outputs/apk/release/`.

## 7. CI/CD (GitHub Actions)

Bei jedem Push auf `main` oder einem Tag `v*` baut der Workflow automatisch eine signierte Release-APK und erstellt einen GitHub Release (nur bei Tags mit gesetztem Keystore-Secret).

### Secrets hinterlegen

Alle Secrets über die GitHub CLI setzen (nicht über Copy-Paste im Browser, um Encoding-Probleme zu vermeiden):

**Keystore als Base64:**

```powershell
# Windows PowerShell:
[Convert]::ToBase64String([System.IO.File]::ReadAllBytes("einsatzleiter.keystore")) `
  | Set-Content -Encoding ascii -NoNewline "$env:TEMP\ks_b64.txt"
Get-Content -Raw "$env:TEMP\ks_b64.txt" `
  | gh secret set KEYSTORE_FILE --repo OWNER/REPO
Remove-Item "$env:TEMP\ks_b64.txt"
```

```bash
# Linux/macOS:
base64 -w0 einsatzleiter.keystore | gh secret set KEYSTORE_FILE --repo OWNER/REPO
```

**Weitere Secrets** (jeweils interaktiv eingeben — Wert wird nicht in der Shell-History gespeichert):

```bash
gh secret set KEYSTORE_PASSWORD --repo OWNER/REPO   # Keystore-Passwort
gh secret set KEY_ALIAS --repo OWNER/REPO           # z.B. einsatzleiter (ohne Leerzeichen/Newline!)
gh secret set KEY_PASSWORD --repo OWNER/REPO        # Key-Passwort (oft = Keystore-Passwort)
gh secret set GOOGLE_SERVICES_JSON --repo OWNER/REPO
```

> **Hinweis zu KEY_ALIAS:** Nie mit `echo "alias" | gh secret set ...` setzen — echo fügt ein Newline an, das als Teil des Alias gespeichert wird. Stattdessen `printf "alias" | gh secret set ...` verwenden oder interaktiv eingeben.

### Wie das Signing im CI funktioniert

Der Workflow in `.github/workflows/build-apk.yml` führt folgende Schritte durch:

1. **Build:** `./gradlew assembleRelease` erstellt eine unsignierte APK
2. **Konvertierung:** `openssl pkcs12` re-packaged den hochgeladenen Keystore in ein Java 21-kompatibles Format (der originale Keystore verwendet möglicherweise ältere PKCS12-Algorithmen, die `apksigner` unter Java 17+ nicht lesen kann)
3. **Signierung:** `apksigner sign` signiert die APK mit dem konvertierten Keystore
4. **Upload:** Artifact + GitHub Release werden erstellt

```
KEYSTORE_FILE (Base64)
  └─ base64 --decode → /tmp/ks-orig.p12
       └─ openssl pkcs12 (re-pack) → /tmp/keystore.jks  (AES-256, Java 21-kompatibel)
            └─ apksigner sign → app-release-signed.apk
                 └─ GitHub Release v*
```

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
