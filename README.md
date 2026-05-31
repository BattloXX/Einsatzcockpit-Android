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

Das `native-bridge.js` im Backend erkennt automatisch, ob es in Capacitor läuft (`window.Capacitor.isNativePlatform()`) und stellt `window.ELNative` bereit. In der reinen PWA bleiben alle Funktionen No-Ops oder fallen auf Web-APIs zurück — kein Code-Split nötig.

---

## Voraussetzungen

- **Node.js** 20+
- **Java** 21 (Capacitor 7 erfordert Java 21; Java 17 reicht nicht)
- **Android Studio** (empfohlen für lokale Entwicklung / Emulator)
- Ein aktives **Firebase-Projekt** (für FCM-Push, kostenlos)

---

## Lokale Entwicklung

### 1. Abhängigkeiten installieren

```bash
npm install
```

### 2. Android-Plattform einrichten

```bash
npx cap add android
mkdir -p android/app/src/main/assets   # wird von cap sync benötigt
npx cap sync android
```

> **Hinweis:** Der `android/`-Ordner ist gitignored (Capacitor-generiert). Er wird lokal und im CI bei jedem Build neu angelegt. Eigene Anpassungen am Manifest müssen entweder lokal gemacht oder als Patch im Workflow hinterlegt werden.

### 3. Firebase-Datei ablegen

`google-services.json` aus der Firebase Console herunterladen und ablegen:

```
android/app/google-services.json
```

(Datei ist gitignored — nie committen!)

### 4. Im Emulator oder auf dem Gerät testen

```bash
npx cap run android
# oder
npx cap open android   # öffnet Android Studio
```

---

## CI/CD — Automatischer Build via GitHub Actions

Bei jedem Push auf `main` und bei Tags (`v1.x.x`) baut GitHub Actions automatisch eine APK und stellt sie als Artefakt bereit.

### Was der Workflow macht

```
Checkout
  → npm ci
  → npx cap add android          # Android-Projekt frisch anlegen
  → mkdir android/app/src/main/assets
  → npx cap sync android         # Plugins + Config synchronisieren
  → google-services.json schreiben (aus Secret GOOGLE_SERVICES_JSON)
  → AndroidManifest.xml patchen  # Standort-Berechtigungen einfügen
  → ./gradlew assembleRelease    # falls KEYSTORE_FILE gesetzt
    oder assembleDebug           # Fallback ohne Keystore
  → APK signieren (apksigner)    # nur wenn KEYSTORE_FILE gesetzt
  → Artefakt hochladen
  → GitHub Release erstellen     # nur bei v-Tags + Keystore
```

Der Manifest-Patch fügt automatisch folgende Berechtigungen ein, die Capacitor nicht selbst setzt:

| Berechtigung | Zweck |
|---|---|
| `ACCESS_FINE_LOCATION` | GPS-Standort |
| `ACCESS_BACKGROUND_LOCATION` | GPS bei gesperrtem Display (Android 10+) |
| `FOREGROUND_SERVICE_LOCATION` | Pflicht für standortbasierte Dienste (Android 14+) |

### Secrets in GitHub hinterlegen

Unter **Settings → Secrets and variables → Actions**:

| Secret | Inhalt | Pflicht |
|---|---|---|
| `GOOGLE_SERVICES_JSON` | Vollständiger Inhalt der `google-services.json` | Ja (ohne → FCM inaktiv, Platzhalter) |
| `KEYSTORE_FILE` | Base64-kodierter Keystore | Nein (ohne → Debug-APK) |
| `KEYSTORE_PASSWORD` | Keystore-Passwort | Nur mit `KEYSTORE_FILE` |
| `KEY_ALIAS` | Key-Alias | Nur mit `KEYSTORE_FILE` |
| `KEY_PASSWORD` | Key-Passwort | Nur mit `KEYSTORE_FILE` |

**Secret setzen (Beispiel):**

```bash
# google-services.json
gh secret set GOOGLE_SERVICES_JSON < android/app/google-services.json

# Keystore (Base64)
gh secret set KEYSTORE_FILE < <(base64 -w0 einsatzleiter.keystore)
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS
gh secret set KEY_PASSWORD
```

### Keystore erstellen (einmalig, außerhalb des Repos speichern!)

```bash
keytool -genkey -v \
  -keystore einsatzleiter.keystore \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias einsatzleiter
```

### Build manuell auslösen

```bash
# Normaler Build (Debug-APK als Artefakt)
git push origin main

# Release mit GitHub Release-Seite (erfordert Keystore-Secrets)
git tag v1.0.0 && git push origin v1.0.0
```

Die fertige APK ist unter **Actions → letzter Run → Artifacts → einsatzleiter.apk** abrufbar.

---

## Hintergrund-Standort: Android-Einstellung erforderlich

Damit die App GPS-Koordinaten auch bei gesperrtem Display sendet, muss in den **Android-Systemeinstellungen** die Berechtigung auf **„Immer zulassen"** gesetzt werden:

1. **Einstellungen → Apps → einsatzleiter.cloud**
2. **Berechtigungen → Standort**
3. **„Immer zulassen"** auswählen

> Ohne diese Einstellung sendet die App den Standort nur, solange sie aktiv im Vordergrund läuft. Fahrzeuge ohne aktive Hintergrund-Standortfreigabe erscheinen **nicht** im GeoJSON-/KML-Feed der Lagekarte.

---

## Installation auf dem Gerät (Sideload)

1. Aktuelle APK von [Actions → Artifacts](../../actions) herunterladen (oder von [Releases](../../releases) bei getaggten Versionen)
2. Auf das Android-Gerät übertragen (USB, E-Mail, Link)
3. **Einstellungen → Sicherheit → Unbekannte Quellen** erlauben (einmalig)
4. APK antippen → installieren

---

## Erster Start & Login

1. App öffnen → QR-Scanner erscheint automatisch (erster Start ohne gespeichertem Token)
2. Im Backend: **Admin → Geräte-Login → Neues Gerät erstellen** → QR-Code anzeigen
3. QR mit der App scannen → eingeloggt
4. Ab jetzt: dauerhaft eingeloggt (Token gespeichert), automatisches Re-Login nach Session-Ablauf

> Der QR-Code kodiert die URL `/geraet-login?token=...` — die App kann ihn scannen **oder** der Link kann direkt im Browser geöffnet werden.

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
