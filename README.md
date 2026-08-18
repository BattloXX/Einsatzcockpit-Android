# Einsatzcockpit Android-App

Native Android-App für [einsatzcockpit.com](https://einsatzcockpit.com) — ein digitales Einsatzleiter-Werkzeug für Feuerwehren und BOS-Organisationen. (Historischer Name/Domain: einsatzleiter.cloud — seit dem Rebrand nur noch als Übergangs-Redirect aktiv, siehe `capacitor.config.ts`.)

Die App ist ein schlanker **Capacitor-Wrapper** um die bestehende Progressive Web App. Sie lädt die PWA direkt vom Server und ergänzt sie um native Android-Funktionen, die im Browser nicht zuverlässig verfügbar sind:

| Funktion | Technologie |
|---|---|
| **Zuverlässige Push-Benachrichtigungen** (auch bei geschlossener App, sofortiger Weckruf statt System-Default) | Firebase Cloud Messaging (FCM, Data-only) → eigener `FirebaseMessagingService` |
| **Dauerbenachrichtigung für laufenden Einsatz** ("Live-Einsatzstatus", Chronometer/Phase, bedarfsgesteuert – läuft nicht permanent im Hintergrund) | `EinsatzLivePoller` + `DeviceKeepaliveService` (Foreground-Service, stoppt nach 15 Min. Leerlauf selbst) |
| **Alarmton trotz Lautlos/Vibration** (optional, nur neue Einsätze) | Nativer Android Notification-Channel |
| **Dauerhafter Login** (kein tägliches Neu-Einloggen) | Device-Token in Secure Storage |
| **QR-Code-, PIN- oder Account-Login** | App öffnen → QR scannen / PIN eingeben / Benutzername+Passwort → sofort eingeloggt |
| **GPS-Standort im Einsatz** (Hintergrund, nur bei aktivem Einsatz) | Background Geolocation → Lagekarte |
| **Bildschirm aktiv halten** (Atemschutz-Überwachung, Screensaver) | Native Wake Lock |
| **SMS-Gateway-Modus** (Versand/Empfang über SIM-Karte des Geräts, 24/7-Dauerbetrieb) | Eigenes Capacitor-Plugin, persistente WebSocket-Verbindung zum Backend |
| **Sideload-APK** (kein Play Store nötig) | Signierte APK via GitHub Actions, CalVer-Versionierung |

> Die Web-App, das Dashboard und alle Browser-Nutzer funktionieren weiterhin unverändert. Diese App ist ein optionaler nativer Client für den Einsatzbetrieb.

---

## Architektur

```
Android App (Capacitor, appId cloud.einsatzleiter.app)
  └─ WebView → https://einsatzcockpit.com (allowNavigation, kein server.url)
       └─ native-bridge.js  (aus Backend /static/js/)
            ├─ ELNative.keepAwake(on)       → KeepAwake-Plugin
            ├─ ELNative.startLocation()     → BackgroundGeolocation-Plugin
            ├─ ELNative.stopLocation()      → ^
            ├─ ELNative.scanQr(callback)    → BarcodeScanning-Plugin
            └─ pollt /api/v1/device/duty-state, meldet /api/v1/device/location

  Eigene Capacitor-Plugins (plugins/sms-gateway/android/.../smsgatewayplugin/):
    ├─ EinsatzLivePlugin + DeviceKeepaliveService + EinsatzLivePoller
    │    → eigener OkHttp-Client (Session-Cookies aus der WebView) pollt
    │      /api/v1/device/duty-state, baut die Ongoing-Notification
    │      "Laufender Einsatz" (Channel ec_einsatz_live); der Service läuft
    │      nur bei aktivem Einsatz/Dienst und stoppt sich nach 15 Min.
    │      Leerlauf selbst — kein Dauerbetrieb ab Login
    ├─ EinsatzFirebaseMessagingService → empfängt FCM-Data-Messages auch bei
    │      beendeter App, weckt den Live-Poller sofort statt beim nächsten
    │      Poll-Intervall, zeigt sonstige Pushes als eigene Notification
    ├─ AlarmChannelPlugin → akustischer Alarmkanal (Sirenenton, DND-Bypass)
    ├─ SmsGatewayPlugin/-Service → persistente WebSocket-Verbindung, eigener
    │      Foreground-Service (specialUse) für SMS-Versand/-Empfang, 24/7
    └─ BootReceiver → startet nach Neustart nur das dauerhaft konfigurierte
           SMS-Gateway neu; der Live-Status wird reaktiv (App-Start/FCM) geweckt

  FCM Push (Data-only) ← Firebase ← Backend (push_service.py)
```

Das `native-bridge.js` im Backend erkennt automatisch, ob es in Capacitor läuft (`window.Capacitor.isNativePlatform()`) und stellt `window.ELNative` bereit. In der reinen PWA bleiben alle Funktionen No-Ops oder fallen auf Web-APIs zurück — kein Code-Split nötig.

> Ausführliche Analyse der Notification-/Bridge-Architektur inkl. Begründung der aktuellen Entwurfsentscheidungen: [`docs/notification-bridge-review.md`](docs/notification-bridge-review.md).

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

Bei jedem Push auf `main` und bei Tags (`v2026.08.18`, CalVer-Format `vYYYY.MM.DD[.N]` — wie im [Backend-Repo](https://github.com/BattloXX/Einsatzcockpit)) baut GitHub Actions automatisch eine APK und stellt sie als Artefakt bereit. Nach jedem veröffentlichten GitHub Release erhöht `.github/workflows/version-bump.yml` die Version in `package.json`/`package-lock.json` automatisch um einen Tages-Suffix (`.1`, `.2`, …), analog zum Backend-Workflow.

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

# Release mit GitHub Release-Seite (erfordert Keystore-Secrets, CalVer-Tag)
git tag v2026.08.18 && git push origin v2026.08.18
```

Die fertige APK ist unter **Actions → letzter Run → Artifacts → einsatzcockpit-v\<Version\>** abrufbar (bei getaggten Releases zusätzlich direkt unter [Releases](../../releases)).

---

## Hintergrund-Standort: Android-Einstellung erforderlich

Damit die App GPS-Koordinaten auch bei gesperrtem Display sendet, muss in den **Android-Systemeinstellungen** die Berechtigung auf **„Immer zulassen"** gesetzt werden:

1. **Einstellungen → Apps → Einsatzcockpit**
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

Beim ersten Start (kein gespeicherter Token) bietet die App vier Anmeldewege:

| Weg | Für wen |
|---|---|
| **QR-Code scannen** | Geräte-Pairing (Fahrzeug-Tablet, Anzeigegerät, SMS-Gateway) — Admin erzeugt den QR unter **Admin → Geräte-Login** |
| **PIN eingeben** | Geräte-Pairing ohne Kamerazugriff (Admin zeigt zusätzlich eine 10 Minuten gültige PIN) |
| **Mit Account anmelden** | Persönlicher Account (Benutzername/Passwort), optional mit Live-Einsatzstatus-Opt-in |
| **SMS-Gateway koppeln** | Eigener QR-Modus (`mode=sms-gateway` bzw. `unit+sms-gateway` für kombinierte Geräte) |

1. App öffnen → Startbildschirm mit den vier Optionen erscheint
2. Im Backend: **Admin → Geräte-Login → + Gerät registrieren** → QR-Code oder PIN anzeigen
3. QR scannen / PIN eingeben / Account-Login → eingeloggt
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
| `@einsatzleiter/sms-gateway-plugin` (lokal, `plugins/sms-gateway/`) | SMS-Gateway, Live-Einsatzstatus, Alarmkanal, eigener FCM-Empfänger — siehe Architektur oben |

---

## Zugehöriges Backend-Repo

[BattloXX/Einsatzcockpit](https://github.com/BattloXX/Einsatzcockpit) — FastAPI + PWA, der Server hinter dieser App (vormals „Einsatzleiter-Hilfswerkzeug", seit Rebrand 3.0.0 `einsatzcockpit.com`).

Backend-Endpoints für diese App (`app/routers/device_api.py`):
- `POST /api/v1/device/fcm-token` — FCM-Token registrieren
- `POST /api/v1/device/location` — GPS-Position übermitteln
- `POST /api/v1/device/duty` — Dienst-Status setzen (aktuell ohne Aufrufer in App/Admin-UI; `should_track` wird in der Praxis rein über `incident_active` gesteuert)
- `GET /api/v1/device/duty-state` — Einsatz-/Dienst-Status abfragen (steuert Hintergrund-GPS und den Live-Poller)
- `POST /api/v1/device/native-link` — kurzlebiges Token für PDF-Handoff an Custom-Tabs

Details zur FCM-Nutzlast (Data-only statt Notification+Data) siehe `app/services/push_service.py::send_fcm` im Backend-Repo.

---

## Offline-Objektdaten (Objektverwaltung, seit Backend-PR9 2026-07-05)

Die App precacht **Objektinformationen inklusive PDFs** für den Einsatz ohne Netz
(Fahrzeug im Funkloch). Das Befüllen des WebView-Cache-Storage bleibt PWA-Code;
ein nativer WorkManager-Weckruf stellt den periodischen Lauf auch sicher, wenn
die sichtbare App-WebView zwischenzeitlich beendet wurde:

- `objekt_offline_sync.js` (Backend, wird nur geladen wenn das Objekt-Modul aktiv
  ist) erkennt die App über `window.Capacitor.getPlatform() === 'android'` und
  synchronisiert 90 s nach dem Start sowie alle 6 Stunden.
- Quelle: `GET /api/objekte/sync` (Session-Auth der WebView) — Manifest aller
  **freigegebenen** Objekte mit Einsatzansicht-URL, Thumbnails, Hi-Res-Seiten
  und Einzel-PDFs. Gelöschte/zurückgezogene Inhalte werden aus dem Cache geräumt.
- Ablage im Cache-Storage `ec-objekt-v1`; der Service Worker (`sw.js`) bedient
  `/objekt-medien/*` cache-first und `/objekte/<id>/einsatz` als Offline-Fallback.
- Im normalen Browser läuft **kein** Voll-Precaching (Datenvolumen) — dort cacht
  der Service Worker nur besuchte Seiten.
- `ObjektOfflineSyncWorker` startet nur für Einheit-Geräte etwa alle 6 Stunden
  eine kurzlebige, unsichtbare WebView mit derselben Session und ruft den
  PWA-Sync auf. Er startet keinen Foreground-Service und hält keinen WakeLock.

Manuell auslösbar in der WebView-Konsole: `window.objektOfflineSync()`.
