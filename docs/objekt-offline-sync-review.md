# Analyse: Offline-Objektdaten-Cache (Objektverwaltung, Android-App) (2026-08-18)

Diese Analyse wurde von Claude Code (Sonnet 5) durch Lesen von
`BattloXX/Einsatzcockpit-Android` und `BattloXX/Einsatzcockpit` (Backend,
lokal unter `/home/johannes/projects/Einsatzcockpit`) erstellt. Sie ist der
Ausgangspunkt für eine Codex-Gegenprüfung und anschließende Umsetzung —
**Codex soll die Hypothese unten verifizieren, bevor etwas geändert wird**,
und den restlichen Correctness-Audit durchführen.

## Feature-Überblick (bestätigt durch Repo-Lektüre)

Die Android-App precacht Objektinformationen (Objektverwaltung: Einsatzansicht-
HTML, Thumbnails, Hi-Res-Seiten, Einzel-PDFs) für den Einsatz ohne Netz
("Fahrzeug im Funkloch"). Der Mechanismus lebt komplett in der Remote-PWA
(Backend-Repo) und läuft automatisch im Capacitor-WebView — **kein nativer
Code beteiligt**:

- `app/static/js/objekt_offline_sync.js` — läuft nur wenn
  `window.Capacitor.getPlatform() === 'android'`. Plant per `setTimeout`
  einen Lauf 90s nach Skriptstart, danach alle 6h. Holt
  `GET /api/objekte/sync` (Manifest aller freigegebenen Objekte der eigenen
  Org, Session-Auth der WebView), gleicht die Soll-URL-Menge gegen den Cache
  `ec-objekt-v1` ab: fehlende Dateien laden, nicht mehr referenzierte
  Einträge löschen. Einsatzansicht-HTML wird immer neu geladen (ändert sich),
  Mediendateien nur wenn fehlend (unveränderlich, UUID-Pfade). Erfolgreicher
  Lauf schreibt Zeitstempel nach `localStorage['ec_objekt_sync_zuletzt']`.
  Manuell auslösbar: `window.objektOfflineSync()`.
- `app/static/sw.js` — Service Worker liefert `/objekt-medien/*` cache-first
  aus `OBJEKT_CACHE` ('ec-objekt-v1'), `/objekte/<id>/einsatz` network-first
  mit Fallback auf `BOARD_CACHE` dann `OBJEKT_CACHE` (inkl. Offline-Banner-
  Injection). `OBJEKT_CACHE` ist im `activate`-Handler explizit von der
  Cache-Bereinigung bei App-Updates ausgenommen (`protectedCaches`-Liste).
- `app/services/objekt_service.py::build_sync_manifest` — liefert nur
  `status == freigegeben`, Org-gescoped, alle Seiten-URLs
  (Thumb/Bild/Einzel-PDF je nachdem was gesetzt ist).
- `app/routers/ui_objekt_dokumente.py::objekte_sync_manifest` —
  `GET /api/objekte/sync`, geschützt durch `require_role(*_LESE_ROLLEN)`
  (`app/routers/ui_objekt.py`, `_LESE_ROLLEN` deckt praktisch alle
  Standard-Rollen ab) und `require_objekt_enabled` (404 wenn Modul aus).
- Eingebunden in `app/templates/base.html`, nur wenn
  `request.state.objekt_enabled` (defer-Script, auf jeder Seite geladen).

## Bereits geprüft — sieht auf Code-Ebene korrekt aus

- Cache-Delta-Logik (Löschen nicht mehr referenzierter Einträge, Neuladen nur
  fehlender Dateien, immer-neu für Einsatzansicht-HTML): korrekt.
- Cache-Schutz bei App-Updates (`sw.js`-`activate`): korrekt, `OBJEKT_CACHE`
  explizit ausgenommen.
- Offline-Auslieferung (`sw.js`-Fetch-Handler): korrekt implementiert,
  cache-first für Medien, network-first mit Cache-Fallback für die
  Einsatzansicht.
- Server-Manifest: korrekt gescoped und gefiltert.

## Hauptverdacht — bitte zuerst verifizieren

Im vorigen Auftrag (dieses Repo, gemergter PR #25, siehe
`docs/notification-bridge-review.md`) wurde `DeviceKeepaliveService` von
"läuft ab Login dauerhaft" auf "läuft nur bei aktivem Einsatz/Dienst, stoppt
nach 15 Min. Leerlauf selbst" umgestellt (Akku sparen, keine permanente
"App läuft im Hintergrund"-Notification mehr). Vor der Umstellung startete
`DeviceKeepalive.startKeepalive()` unconditional bei **jedem** Geräte-Login
(QR/PIN, `el_device_token` vorhanden) — also genau bei den Fahrzeug-/
Einheit-Geräten, die das Objekt-Offline-Feature laut README adressiert
("Fahrzeug im Funkloch"). Nach der Umstellung läuft der Foreground-Service
+ WakeLock-Schutz nur noch, während `incident_active`/`duty_active` ist.

`objekt_offline_sync.js`s 6-Stunden-Rhythmus ist eine reine
`setTimeout`-Kette **im WebView-JS-Kontext** — sie feuert nur, solange der
Android-Prozess (und die geladene WebView darin) nicht eingefroren oder vom
System beendet wird. Vor der Keepalive-Umstellung war das für jedes
Geräte-Login-Fahrzeug durchgehend garantiert (permanenter Foreground-Service).
Nach der Umstellung ist ein Fahrzeug-Tablet, das gerade **nicht** im Einsatz
ist (der Normalzustand — genau dann soll die Offline-Daten trotzdem aktuell
gehalten werden, für den nächsten Einsatz!), nach spätestens 15 Minuten ohne
diesen Schutz — Android dürfte den Hintergrundprozess je nach Hersteller
innerhalb kurzer Zeit einfrieren/beenden (vgl. die ausführliche
OEM-Doze-Dokumentation, die für den SMS-Gateway-Modus bereits in `SETUP.md`
existiert, aber für den Einheit-Gerät-Modus nirgends berücksichtigt wird).

**Zu verifizieren:**
1. Bricht das den zuverlässigen 6h-Rhythmus tatsächlich? Recherche/Analyse zu
   Android-Prozess-Lifecycle und Chromium-WebView-Hintergrund-Timer-Throttling
   ohne aktiven Foreground-Service — wird der Sync dadurch faktisch nur noch
   ausgeführt, wenn ein Nutzer die App aktiv offen hält (statt zuverlässig im
   Hintergrund alle 6h)?
2. Falls ja: das ist eine Regression aus dem vorigen Auftrag und sollte
   behoben werden, **ohne** zum alten Dauerbetrieb zurückzukehren (explizit
   unerwünscht — Akku/Notification-Ziel bleibt bestehen).

## Weitere zu prüfende Punkte

3. **Geräte-Login-Berechtigung:** Hat der `User` hinter einem Geräte-Login
   (`DeviceToken.user_id`) in der Praxis immer eine Rolle aus `_LESE_ROLLEN`?
   Falls nicht: `objekt_offline_sync.js` schlägt bei `!antwort.ok` still fehl
   (kein Log, kein User-/Admin-sichtbarer Hinweis) — der Sync könnte für
   manche Geräte nie laufen, ohne dass das auffällt.
4. **Fehlerverhalten bei Teilausfällen:** Netzabbruch mitten im Sync — bleibt
   der Cache in einem konsistenten (wenn auch unvollständigen) Zustand, der
   sich beim nächsten Lauf selbst repariert? Auf den ersten Blick ja (jede
   Datei wird einzeln gefetcht, ein Fehler stoppt nur diese eine Datei), aber
   bitte gegenprüfen.
5. **Sichtbarkeit des letzten Sync-Zeitpunkts:** Aktuell nur in
   `localStorage['ec_objekt_sync_zuletzt']`, nirgends angezeigt — weder in
   der App (z.B. "Über die App"-Screen) noch im Admin-Bereich. Für den
   Nutzer/Admin ist nicht erkennbar, ob/wann ein Gerät zuletzt erfolgreich
   synchronisiert hat. Kein Blocker, aber für die Fehlerdiagnose relevant —
   nur beheben, wenn es sich klein/risikoarm im Rahmen des Hauptfixes ergibt.

## Erwartete Fix-Richtung (falls Hauptverdacht bestätigt)

Native Kotlin-Seite kann die Web-Cache-Storage-API **nicht** direkt befüllen
(nur JS im WebView kann `caches.open()`/`cache.put()`) — ein rein nativer
Hintergrund-Fetch (z.B. WorkManager) kann den Sync also nicht selbst
durchführen, er kann nur dafür sorgen, dass der Prozess/die WebView lange
genug am Leben bleibt, damit `objekt_offline_sync.js`s eigener Timer feuert.

Richtung: den bestehenden `EinsatzLivePoller`/`DeviceKeepaliveService`-
Mechanismus (bzw. einen neuen, leichten nativen Auslöser, z.B. periodisch
alle ~6h per `WorkManager`/`AlarmManager`) so erweitern, dass er den
Keepalive-Dienst kurz reaktiviert, wenn ein Objekt-Sync fällig ist — ähnlich
wie er es heute schon für Einsätze/Dienst tut — statt dauerhaft zu laufen.
Denkbare Bausteine (nicht als fertige Lösung zu verstehen, Codex soll die
beste Umsetzung wählen):

- Neues Backend-Feld (`DeviceToken.last_objekt_sync_at`), gesetzt von
  `objekt_offline_sync.js` per kleinem POST nach jedem erfolgreichen Sync
  (analog zu `POST /api/v1/device/location`/`fcm-token`), gelesen von
  `GET /api/v1/device/duty-state` oder einem neuen leichten Endpoint, damit
  natives Kotlin-Code weiß, ob ein Sync fällig ist.
- Periodischer nativer Weckruf (z.B. `WorkManager`-`PeriodicWorkRequest`,
  min. Intervall beachten), der bei Fälligkeit den Keepalive-Dienst kurz
  reaktiviert (`DeviceKeepaliveService`, ähnlich `ACTION_LIVE_REFRESH`),
  genug Zeit lässt, damit die bereits geladene WebView den JS-Sync einmal
  durchlaufen kann, und danach wie gewohnt wieder in den Leerlauf-Timeout
  fällt.
- Nur für Geräte-Login (Einheit-Gerät-Modus) relevant, nicht für reine
  Account-Logins ohne Live-Opt-in (dort ist der Objekt-Sync ohnehin nicht der
  Hauptanwendungsfall).

**Wichtig:** Keine Rückkehr zum alten Dauerbetrieb — die Lösung muss den
Objekt-Sync zuverlässig alle ~6h antriggern, ohne den Foreground-Service
wieder dauerhaft laufen zu lassen.

## Auftrag an Codex

1. **Verifikation** der Hauptverdacht (Abschnitt oben) und der Punkte 3–5,
   mit Belegen (Datei/Zeile, ggf. Recherche zu Android-Hintergrundverhalten).
2. **Bestätigte Bugs beheben** — Hauptkandidat: Sync-Zuverlässigkeit ohne
   Dauerbetrieb (siehe Fix-Richtung oben, Details/Umsetzung liegen bei
   Codex); ggf. Rollen-Lücke; ggf. Fehlersichtbarkeit, falls risikoarm im
   selben Rahmen erledigbar.
3. **Verifikation nach Umsetzung:** bestehende Tests laufen lassen (`pytest`
   im Backend-Repo, Fokus auf ggf. betroffene Objekt-/Device-API-Tests),
   Android-Seite so weit wie ohne SDK/Java 21 möglich prüfen (Kotlin-Syntax
   grob, Manifest-Validität) — Gradle-Build bleibt der CI (`build-apk.yml`)
   überlassen.

**Bericht (fünf Abschnitte, konkret mit Datei/Zeile):**
1. Verifikationsergebnis Hauptverdacht + Punkte 3–5.
2. Was umgesetzt wurde, je Datei in jedem Repo.
3. Was übersprungen wurde und warum.
4. Testergebnisse.
5. Was der Nutzer vor dem Mergen/Deployen noch prüfen sollte.
