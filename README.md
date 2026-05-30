# einsatzleiter.cloud Android-App

Native Android-App für [einsatzleiter.cloud](https://einsatzleiter.cloud) — ein digitales Einsatzleiter-Werkzeug für Feuerwehren und BOS-Organisationen.

Die App ist ein schlanker **Capacitor-Wrapper** um die bestehende Progressive Web App. Sie lädt die PWA direkt vom Server und ergänzt sie um native Android-Funktionen, die im Browser nicht zuverlässig verfügbar sind:

| Funktion | Technologie |
|---|---|
| **Zuverlässige Push-Benachrichtigungen** (auch bei geschlossener App) | Firebase Cloud Messaging (FCM) |
| **Dauerhafter Login** (kein tägliches Neu-Einloggen) | Device-Token in Secure Storage |
| **QR-Code Login** | App öffnen → QR scannen → sofort eingeloggt |
| **GPS-Standort im Einsatz** (Hintergrund, nur bei aktivem Einsatz) | Background Geolocation → Lagekarte |
| **Bildschirm aktiv halten** (Atemschutz-Überwachung, Screensaver) | Native Wake Lock |
| **Sideload-APK** (kein Play Store nötig) | Signierte APK via GitHub Actions |

> Die Web-App, das Dashboard und alle Browser-Nutzer funktionieren weiterhin unverändert. Diese App ist ein optionaler nativer Client für den Einsatzbetrieb.

---

## Architektur

```
Android App (Capacitor)
  └─ WebView → https://einsatzleiter.cloud
       └─ native-bridge.js  (aus Backend /static/js/)
            ├─ ELNative.keepAwake(on)       → KeepAwake-Plugin
            ├─ ELNative.startLocation()     → BackgroundGeolocation-Plugin
            ├─ ELNative.stopLocation()      → ^
            └─ ELNative.scanQr(callback)    → BarcodeScanning-Plugin
         FCM Push ← Firebase ← Backend (push_service.py)
```

Das `native-bridge.js` im Backend erkennt automatisch, ob es in Capacitor läuft (`window.Capacitor.isNative`) und stellt `window.ELNative` bereit. In der reinen PWA bleiben alle Funktionen No-Ops oder fallen auf Web-APIs zurück — kein Code-Split nötig.

---

## Voraussetzungen

- **Node.js** 20+
- **Java** 17 (Android-Build)
- **Android Studio** (empfohlen für lokale Entwicklung / Emulator)
- Ein aktives **Firebase-Projekt** (für FCM-Push, kostenlos)

---

## Setup (einmalig)

### 1. Firebase-Projekt einrichten

1. [Firebase Console](https://console.firebase.google.com/) → Projekt anlegen
2. „Android App hinzufügen" → Package-Name: `cloud.einsatzleiter.app`
3. `google-services.json` herunterladen → ablegen unter `android/app/google-services.json`
4. Service-Account erstellen: Projekteinstellungen → Dienstkonten → „Neuen privaten Schlüssel generieren"
5. Service-Account-JSON auf dem **Backend-Server** ablegen (z.B. `/etc/einsatzleiter/fcm-service-account.json`)

**Backend `.env` aktualisieren:**
```env
FCM_ENABLED=true
FCM_PROJECT_ID=dein-firebase-projekt-id
FCM_CREDENTIALS_PATH=/etc/einsatzleiter/fcm-service-account.json
```

### 2. Projekt initialisieren

```bash
npm install
npx cap add android
npx cap sync android
```

### 3. AndroidManifest.xml — Berechtigungen

Inhalt von `android-permissions.xml` in `android/app/src/main/AndroidManifest.xml` einfügen — nach `<uses-sdk>`, vor `<application>`.

### 4. App-Icon & Splash

```bash
# Logo aus dem Backend kopieren
cp ../Einsatzleiter-Hilfswerkzeug/app/static/img/logo.png assets/logo.png

# Icons und Splash generieren
npx @capacitor/assets generate --android
```

### 5. Backend-Migration

```bash
cd ../Einsatzleiter-Hilfswerkzeug
alembic upgrade head
```

---

## Entwicklung & Build

### Im Emulator oder Gerät testen

```bash
npx cap run android
# oder
npx cap open android   # öffnet Android Studio
```

### Release-APK lokal bauen

```bash
# Keystore erstellen (einmalig, außerhalb des Repos speichern!)
keytool -genkey -v -keystore einsatzleiter.keystore \
  -keyalg RSA -keysize 2048 -validity 10000 -alias einsatzleiter

# APK bauen
cd android
./gradlew assembleRelease
# APK: android/app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## CI/CD — Automatischer APK-Build

Bei jedem Push auf `main` und bei Tags (`v1.0.0`) baut GitHub Actions eine signierte APK und hängt sie als Release-Artefakt an.

### Secrets in GitHub hinterlegen

| Secret | Inhalt |
|---|---|
| `GOOGLE_SERVICES_JSON` | Vollständiger Inhalt der `google-services.json` |
| `KEYSTORE_FILE` | Base64-kodierter Keystore: `base64 einsatzleiter.keystore` |
| `KEYSTORE_PASSWORD` | Keystore-Passwort |
| `KEY_ALIAS` | Key-Alias (z.B. `einsatzleiter`) |
| `KEY_PASSWORD` | Key-Passwort |

---

## Installation auf dem Gerät (Sideload)

1. Aktuelle APK von [Releases](../../releases) herunterladen
2. Auf das Android-Gerät übertragen (USB, E-Mail, Link)
3. **Einstellungen → Sicherheit → Unbekannte Quellen** erlauben (einmalig)
4. APK antippen → installieren

---

## Erster Start & Login

1. App öffnen → QR-Scanner erscheint automatisch (erster Start ohne gespeichertem Token)
2. Im Backend: **Admin → Geräte-Login → Neues Gerät erstellen** → QR-Code anzeigen
3. QR mit der App scannen → eingeloggt
4. Ab jetzt: dauerhaft eingeloggt (Token gespeichert), automatisches Re-Login nach Session-Ablauf

> Der QR-Code kodiert die URL `/geraet-login?token=...` — die App kann ihn scannen
> **oder** der Link kann direkt im Browser geöffnet werden.

---

## Verwendete Plugins

| Plugin | Zweck |
|---|---|
| `@capacitor/push-notifications` | FCM-Push, auch bei geschlossener App |
| `@capacitor-community/background-geolocation` | GPS im Hintergrund (nur bei aktivem Einsatz) |
| `@capacitor-community/keep-awake` | Bildschirm aktiv halten (Atemschutz, Screensaver) |
| `@capacitor-mlkit/barcode-scanning` | QR-Code scannen (Login) |
| `@capacitor/preferences` | Device-Token sicher speichern |
| `@capacitor/app` | App-Lifecycle (Vordergrund/Hintergrund) |

---

## Zugehöriges Backend-Repo

[BattloXX/Einsatzleiter-Hilfswerkzeug](https://github.com/BattloXX/Einsatzleiter-Hilfswerkzeug) — FastAPI + PWA, der Server hinter dieser App.

Neue Backend-Endpoints für diese App:
- `POST /api/v1/device/fcm-token` — FCM-Token registrieren
- `POST /api/v1/device/location` — GPS-Position übermitteln
- `POST /api/v1/device/duty` — Dienst-Status setzen
- `GET /api/v1/device/duty-state` — Einsatz-Status abfragen (steuert Hintergrund-GPS)
