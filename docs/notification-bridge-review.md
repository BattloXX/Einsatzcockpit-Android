# Analyse: Notifications & native Bridge (2026-08-18)

Diese Analyse wurde von Claude Code (Sonnet 5) durch Lesen von
`BattloXX/Einsatzcockpit-Android` (dieses Repo) und
`BattloXX/Einsatzcockpit` (Backend, lokal unter
`/home/johannes/projects/Einsatzcockpit`) erstellt. Sie ist der
Ausgangspunkt für eine Codex-Gegenprüfung und anschließende Umsetzung –
**Codex soll die Befunde unten im Code verifizieren, bevor etwas umgesetzt
wird**, insbesondere den Abschnitt "Für Codex ungeklärt".

## Architekturüberblick (bestätigt durch Repo-Lektüre)

```
Android App (Capacitor 7, appId cloud.einsatzleiter.app)
  └─ WebView → https://einsatzcockpit.com (allowNavigation, kein server.url)
       └─ native-bridge.js (Backend: app/static/js/native-bridge.js)
            ├─ window.ELNative.{keepAwake, startLocation, stopLocation,
            │                    scanQr, openUrl, registerPush, setBatterySaver}
            └─ pollt /api/v1/device/duty-state, meldet /api/v1/device/location
  Custom Capacitor-Plugins (plugins/sms-gateway/.../smsgatewayplugin/):
    ├─ EinsatzLivePlugin + DeviceKeepaliveService + EinsatzLivePoller
    │    → eigener OkHttp-Client (WebViewCookieJar!) pollt
    │      /api/v1/device/duty-state alle 30s/300s, baut Tier-1-Ongoing-
    │      Notification (EinsatzLiveNotifier, Channel "ec_einsatz_live")
    ├─ AlarmChannelPlugin → akustischer Alarmkanal (Sirenenton, DND-Bypass)
    ├─ SmsGatewayPlugin/-Service → persistente WebSocket-Verbindung, eigener
    │      Foreground-Service (specialUse) für SMS-Versand/-Empfang
    └─ BootReceiver → startet Services nach Reboot neu
  FCM Push: Backend app/services/push_service.py → firebase_admin.messaging
    (parallel zu Web-Push/VAPID für PWA-Browser-Nutzer)
```

## Analyse – bestätigte Befunde

**Notifications**

1. **FCM-Push und die Live-Ongoing-Notification sind zwei getrennte, nicht
   verzahnte Systeme.** `push_service.send_fcm()` setzt immer ein
   `messaging.Notification(title=, body=)`-Feld
   (`app/services/push_service.py:96-99` im Backend-Repo). Bei
   Notification+Data-Payload zeigt Android die Push automatisch im
   System-Tray an, **ohne** App-Code auszuführen, solange die App im
   Hintergrund/beendet ist – es gibt kein eigenes `FirebaseMessagingService`
   in diesem Repo (nur Capacitors Default-Plugin). Der `EinsatzLivePoller`
   (der die Chronometer-/Phase-Ongoing-Notification aktuell hält) erfährt
   von einem neuen Einsatz also **nicht sofort** über den Push, sondern erst
   beim nächsten Poll-Intervall (bis zu 300s im Idle-Modus) oder wenn der
   Nutzer die App manuell öffnet (`EinsatzLivePlugin.handleOnResume()` →
   `LIVE_REFRESH`). Ergebnis: der Nutzer sieht zwei unabhängige
   Benachrichtigungen (System-Push + später die Ongoing-Notification), die
   zeitlich auseinanderfallen können.
2. **Zwei getrennte Deep-Link-Mechanismen ohne Verzahnung.**
   `EinsatzLiveNotifier` nutzt einen `PendingIntent` mit `EXTRA_EC_URL`, den
   `EinsatzLivePlugin.load()`/`handleOnNewIntent()` nativ ausliest und per
   `bridge.webView.loadUrl()` navigiert – funktioniert zuverlässig auch bei
   Kaltstart. Der generische FCM-Tap läuft dagegen über
   `PushNotifications.addListener('pushNotificationActionPerformed', …)` in
   `native-bridge.js:129-132` (Backend-Repo), der `window.location.href =
   url` setzt – das läuft nur, wenn zu diesem Zeitpunkt bereits die richtige
   Seite mit registriertem Listener geladen ist. Bei Kaltstart lädt zuerst
   `www/index.html` (eigene Login-/Launcher-Logik) in diesem Repo, der
   Listener aus `native-bridge.js` (der erst auf der Remote-PWA-Seite
   eingebunden ist) existiert zu diesem Zeitpunkt noch gar nicht → Tap auf
   eine generische Push-Notification kann bei Kaltstart im Launcher landen
   statt beim Ziel.
3. **Boot-Race beim `EinsatzLivePoller`.** `BootReceiver` startet
   `DeviceKeepaliveService` sofort nach Reboot; `EinsatzLivePoller.start()`
   ruft `schedule(0L)` auf, pollt also **sofort**, bevor die WebView (und
   damit `CookieManager`, von dem `WebViewCookieJar` die Session-Cookies
   liest) überhaupt geladen wurde. Der erste Poll nach Reboot bekommt
   plausibel einen 401 und geht in den 15-Minuten-`AUTH_INTERVAL_MS`-Backoff
   (`EinsatzLivePoller.kt` `handleAuthFailure()`), bevor die App überhaupt
   Gelegenheit hatte, die Session zu erneuern.
4. **CalVer/Versionierung.** `package.json` nutzt SemVer (`0.5.1`), manuell
   per PR gepflegt ("chore: bump version to X", PRs #7/#12/#14). Es gibt
   keinen automatisierten Bump-Workflow wie im Hauptrepo
   (`.github/workflows/version-bump.yml`, CalVer, ausgelöst durch
   `release: published`). `build-apk.yml` leitet `versionName` bei
   Tag-Builds aus dem Git-Tag ab, sonst aus `package.json` – funktioniert mit
   CalVer-Tags unverändert, solange das Präfix `v` bleibt.

**Bridge / generelle Architektur**

5. `native-bridge.js` dokumentiert selbst eine bekannte Einschränkung
   (`ionic-team/capacitor#7454`): die Capacitor-Bridge wird auf per
   `allowNavigation` extern nachgeladenen Seiten nicht zuverlässig
   injiziert. Für FCM-Registrierung/Duty-Poll wurde das bereits mitigiert
   (Registrierung läuft in `www/index.html`/`about.html`, nicht auf der
   Remote-Seite). Andere `ELNative`-Aufrufe (`keepAwake`, `startLocation`,
   `scanQr`, `openUrl`), die auf Remote-Seiten laufen, haben dieses
   Mitigation-Muster nicht – jeder Aufrufer prüft `_isNative()` selbst statt
   eines gemeinsamen "Bridge bereit"-Gates.
6. Drei potenziell gleichzeitig aktive Foreground-Services/-Notifications
   bei kombiniertem Einheit-Gerät+SMS-Gateway-Modus (PR #20):
   `DeviceKeepaliveService` (ID 7302, low-importance), `EinsatzLiveNotifier`
   (ID 7303) und `SmsGatewayService` (eigene Notification) –
   architektonisch nachvollziehbar getrennt, aber nicht zentral
   dokumentiert, dass alle drei koexistieren können.

**Hintergrundbetrieb – läuft die App wirklich dauerhaft, obwohl es nicht nötig wäre?**

8. **`DeviceKeepalive.startKeepalive()` wird an 5 Stellen in `www/index.html`
   bedingungslos aufgerufen** (PIN-Pairing-Rückkehr, direkter Geräte-/QR-Login
   als Einheit-Gerät, kombiniertes Gerät, "leiser" Wiedereinstieg per
   bestehender Account-Session mit `el_live_enabled==='1'`) – jedes Mal
   sobald ein Geräte-Token vorhanden ist bzw. Live-Status einmal opt-in
   aktiviert wurde. **`DeviceKeepalive.stopKeepalive()` wird dagegen an
   keiner einzigen Stelle in beiden Repos aufgerufen** (verifiziert per
   `gh api search/code` + `grep -r` über Android- und Backend-Repo). Der
   Service (`DeviceKeepaliveService`, `START_STICKY`) läuft also ab dem
   ersten Login **dauerhaft weiter** – `PARTIAL_WAKE_LOCK` (alle 24h neu
   erworben), Low-Priority-Dauerbenachrichtigung "App läuft im Hintergrund"
   (Channel `ec_device`, permanent sichtbar) und `EinsatzLivePoller` (pollt
   auch ohne Einsatz alle 300s weiter) – unabhängig davon, ob gerade Dienst
   oder ein Einsatz aktiv ist. Das ist exakt der vom Nutzer angesprochene
   Dauerbetrieb: Akkuverbrauch und eine dauerhaft sichtbare "App
   läuft"-Notification, auch tagelang außer Dienst.
9. **`duty_active`/`POST /api/v1/device/duty` ist totes Feature.** Das
   Datenmodell (`DeviceToken.duty_active`, Default `False`,
   `app/models/user.py:160` im Backend-Repo) und der Endpoint
   (`device_api.py::set_duty`) existieren, werden aber von **keinem**
   Code-Pfad in Android-App oder Backend-Frontend jemals
   aufgerufen/gesetzt – einzige Fundstelle ist eine Read-only-Anzeige in
   `app/templates/admin/device_token_detail.html:35`. `should_track` in
   `get_duty_state()` (`duty_active OR incident_active`) reduziert sich in
   der Praxis also auf `incident_active` allein: das GPS-Tracking ist
   bereits rein einsatzgetrieben (deckt sich mit der README-Aussage
   "GPS-Standort... nur bei aktivem Einsatz"). Der Dauerbetrieb der
   Keepalive-Notification hat damit **keinen** funktionalen "Dienst"-Bezug,
   den er absichern müsste – er existiert nur, weil die Ongoing-Notification
   (Punkt 1) sich aktuell ausschließlich über Dauer-Polling aktuell hält
   statt über einen Weckruf.

   → **Antwort auf die Nutzerfrage "muss die App wirklich dauerhaft im
   Hintergrund laufen?":** Nein, ein permanenter Hintergrundbetrieb ist nach
   aktuellem Code nicht zwingend nötig. Sobald FCM den Poller
   ereignisgesteuert weckt (Empfehlung 3), kann der Foreground-Service auf
   den tatsächlichen Bedarfszeitraum (laufender Einsatz) begrenzt werden,
   statt dauerhaft ab Login zu laufen.

**Für Codex ungeklärt – vor Umsetzung verifizieren**

7. **Mögliche doppelte Push-Zustellung.** Ob die im WebView geladene PWA für
   angemeldete native Nutzer zusätzlich zur FCM-Registrierung auch eine
   VAPID-Web-Push-Subscription über den Service Worker anlegt (dann kämen
   pro Ereignis potenziell zwei System-Notifications an). Dazu wurde der
   PWA-seitige Service-Worker/Subscribe-Code in dieser Analyse nicht geprüft
   – bitte `app/static/**` (Service Worker, Push-Subscribe-JS) im
   Backend-Repo durchsuchen und mit `notify_org`/`notify_all` in
   `push_service.py` abgleichen, bevor an diesem Punkt etwas geändert wird.

## Empfohlene Maßnahmen (Priorität absteigend)

1. **Keepalive-Service-Lebenszyklus an echten Bedarf koppeln statt
   Dauerbetrieb** (bestätigt, Punkte 8+9 – höchste Priorität, direkter
   Nutzerwunsch):
   - `stopKeepalive()` tatsächlich aufrufen: beim Logout, beim Deaktivieren
     von "Live-Einsatzstatus" (Opt-out-Pfad ergänzen, aktuell existiert nur
     der Opt-in-Pfad), und automatisch nach einer Leerlauffrist ohne
     aktiven Einsatz (z.B. `EinsatzLivePoller` seit N Minuten im
     `IDLE_INTERVAL_MS`-Zustand ohne Incident) → Service ruft `stopSelf()`
     statt endlos mit `START_STICKY` weiterzulaufen.
   - Abhängig von Empfehlung 3 (FCM als Weckruf): den Keepalive-Service nur
     noch reaktiv starten – (a) bei neuem Einsatz per FCM, (b) für die Dauer
     des aktiven Einsatzes, (c) beim manuellen Öffnen der App – und nach
     Einsatzende automatisch stoppen statt dauerhaft weiterzulaufen.
   - `duty_active`/`POST /api/v1/device/duty` klären statt unbenutzt stehen
     lassen: entweder als echten manuellen "Dienst"-Schalter anbinden (dann
     wäre Keepalive klar auf die Dienstzeit begrenzbar), oder als toten Code
     entfernen.
   - `acquire(24 * 60 * 60 * 1000L)`-Wake-Lock-Dauer in
     `DeviceKeepaliveService.kt` ist nur ein Sicherheitsnetz gegen
     vergessenes `release()`; nach der Umstellung auf bedarfsgesteuerten
     Betrieb (Service läuft dann nur noch für die Dauer eines aktiven
     Einsatzes statt tagelang) entsprechend verkürzen.
   - Falls für fest verbaute Fahrzeug-Tablets weiterhin echter
     24/7-Betrieb gewünscht ist: das als explizite, separate Einstellung
     anbieten statt impliziten Dauerbetriebs für **alle** Login-Arten (auch
     private Diensthandys mit Live-Opt-in).

2. **CalVer-Umstellung Android-App** (bestätigt, klar umsetzbar):
   - `package.json` `version` von `0.5.1` auf CalVer (`YYYY.MM.DD`, heutiges
     Datum als Startpunkt) umstellen.
   - Neuen Workflow `.github/workflows/version-bump.yml` in diesem Repo
     anlegen, analog zum Hauptrepo-Workflow
     (`Einsatzcockpit/.github/workflows/version-bump.yml` als Vorlage:
     gleiche CalVer-Bump-Logik, aber Ziel-Datei `package.json` statt
     `pyproject.toml`, kein `app/config.py`/README-Badge-Äquivalent –
     stattdessen ggf. `README.md`, falls dort eine Versionsangabe steht).
   - `build-apk.yml` prüfen: Tag-Ableitung (`refs/tags/v*` → `VERSION`)
     funktioniert unverändert mit CalVer-Tags; keine Änderung nötig, nur
     verifizieren.
   - Alte SemVer-Verweise in README/SETUP (falls vorhanden) auf das neue
     Schema anpassen.

3. **FCM als Weckruf statt Notification-Payload für Android** (bestätigter
   Architektur-Gap, Punkt 1 – Voraussetzung für Empfehlung 1):
   - Backend: für Android-FCM-Tokens (`platform == "android"`) Nachrichten
     ohne `notification=`-Feld senden (reine Data-Message), sodass Android
     immer App-Code ausführt statt automatisch eine System-Notification zu
     zeigen (`app/services/push_service.py::send_fcm`, `_notify_fcm_users`).
     Web-Push/VAPID-Pfad für Browser-PWA-Nutzer bleibt unverändert.
   - Android: neuen `FirebaseMessagingService` in
     `plugins/sms-gateway/android/src/main/java/.../smsgatewayplugin/`
     ergänzen (registriert im Plugin-Manifest), der beim Empfang sofort
     `DeviceKeepaliveService.ACTION_LIVE_REFRESH` auslöst (gleicher Pfad wie
     `EinsatzLivePlugin.refreshNow()`), damit die Ongoing-Notification
     innerhalb von Sekunden statt bis zu 5 Minuten aktualisiert wird. Für
     Fälle ohne aktiven Einsatz (z.B. reine Systemmeldungen) weiterhin eine
     einfache Notification bauen, aber über denselben `EinsatzLiveNotifier`-
     Stil statt Capacitor-Default, um Branding/Deep-Link konsistent zu
     halten.

4. **Deep-Link-Konsistenz** (Punkt 2): FCM-Tap-Ziel-URL über den gleichen
   `EXTRA_EC_URL`-Intent-Mechanismus wie `EinsatzLiveNotifier` routen statt
   ausschließlich über den JS-`pushNotificationActionPerformed`-Listener,
   damit Kaltstart-Taps zuverlässig ankommen.

5. **Boot-Race entschärfen** (Punkt 3): `EinsatzLivePoller` beim allerersten
   Start nach Boot eine kurze Anfangsverzögerung geben (z.B. 5–10s statt
   `schedule(0L)`), oder `handleAuthFailure()` in der ersten Minute nach
   Service-Start mit kürzerem Backoff statt direkt `AUTH_INTERVAL_MS`
   (15 min) behandeln. Relevant auch für Empfehlung 1: der reaktiv
   gestartete Service muss nach Boot genauso robust hochfahren wie bisher
   der Dauerbetrieb.

6. **Nur nach Verifikation (Punkt 7):** falls bestätigt wird, dass native
   Clients zusätzlich VAPID-Web-Push registrieren, PWA-seitig die
   Service-Worker-Push-Subscription für `window.Capacitor.isNativePlatform()`
   unterdrücken, damit FCM der einzige Push-Kanal für die native App bleibt.

Nicht in diesem Durchgang umsetzen (nur als Beobachtung vermerken, keine
Code-Änderung verlangen): fehlende Unit-Tests für
`EinsatzLiveState`/`EinsatzLivePoller`-Logik, gemeinsames "Bridge-ready"-Gate
für alle `ELNative`-Aufrufe (Punkt 5) – beides optionale Folge-Tickets, kein
Teil dieses Auftrags.

## Auftrag an Codex

1. **Verifikation:** Prüfe Befunde 1–6, 8–9 gegen den tatsächlichen Code in
   diesem Repo und im Backend-Repo (`/home/johannes/projects/Einsatzcockpit`).
   Korrigiere falsche/veraltete Aussagen. Kläre Punkt 7 (mögliche doppelte
   VAPID+FCM-Zustellung) durch Durchsuchen von `app/static/**` (Service
   Worker, Push-Subscribe-JS) im Backend-Repo.
2. **Umsetzung** der bestätigten Maßnahmen 1, 3, 4, 5 (und 2, 6 falls durch
   Schritt 1 bestätigt) in beiden Repos. Empfehlung 1 (Keepalive-
   Lebenszyklus) hängt von Empfehlung 3 (FCM-Weckruf) ab und sollte danach
   umgesetzt werden, nicht davor. Getrennte Commits/PRs pro Repo, mit
   Beschreibung der jeweiligen Änderung.
3. **Verifikation nach Umsetzung:**
   - Android: `npx cap sync android && cd android && ./gradlew
     assembleDebug` lokal bzw. Push auf einen Feature-Branch → GitHub-
     Actions-Workflow `build-apk.yml` muss grün sein.
   - Backend: bestehende Test-Suite (`pytest`) laufen lassen, falls
     `push_service.py` angefasst wurde.
   - Code-Review: für jeden `startKeepalive()`-Aufruf muss ein
     entsprechender `stopKeepalive()`- bzw. Idle-Auto-Stop-Pfad existieren;
     ein aktiver Einsatz muss weiterhin zuverlässig laufen und die
     Notification aktuell halten (kein Regressions-Risiko: "Notification
     verschwindet fälschlich während Einsatz läuft").
   - Versions-Workflow: Trockentest der Bump-Logik statt echtem
     Release-Trigger.
