# Analyse: Objektcache meldet sich nicht zurück, obwohl der Hintergrund-Sync läuft (2026-09-21)

Diese Analyse wurde von Claude Code (Sonnet 5) durch Lesen von
`BattloXX/Einsatzcockpit-Android` und `BattloXX/Einsatzcockpit` (Backend,
lokal als Codex-Worktree unter
`/home/johannes/Documents/Codex/2026-09-19-weder-objekte-noch-kontakte-funktionierten-offline/`)
erstellt. Nutzer-Meldung: der Objektcache "funktioniert aktuell nicht" — ohne
weitere Angaben, was genau beobachtet wird (kein Blick ins Gerät nötig; Codex
soll die Ursache aus dem Code herleiten).

**Vorgeschichte:** `docs/objekt-offline-sync-review.md` (2026-08-18) ist
veraltet — der dort vorgeschlagene native WorkManager-Auslöser wurde seither
gebaut (`ObjektOfflineSyncWorker`, Commit `1277f5b`) und in den letzten Tagen
mehrfach erweitert (Toggle, manueller Trigger, Aktivitäts-Log in "Über die
App": Commits `2a1c043`, `071e092`, `41a20b9`, `7738a52`, `cc36026`,
`6b1e2bb` im Android-Repo; `11c79c8b`, `0dc55811`, `a1746d77`, `4e119198`,
`52a15fd0`, `502d5afa`, `0f5c1df2`, `f82696ea` im Backend-Repo). Trotz dieser
vielen Iterationen bleibt das Problem offenbar bestehen — das ist der
eigentliche Hinweis: die Diagnose-Bemühungen selbst könnten am falschen
Kanal ansetzen.

## Hauptbefund — mit hoher Sicherheit bestätigt

**Der Diagnose-/Reporting-Kanal, über den `objekt_offline_sync.js` seinen
Fortschritt und seine Fehlerursache meldet, ist strukturell unerreichbar
genau in dem Ausführungskontext, den die letzten Tage Fixes adressieren
sollten: dem headless Hintergrund-Sync über `ObjektOfflineSyncWorker`.**

- `ObjektOfflineSyncWorker.runSyncInWebView()` (Android-Repo,
  `plugins/sms-gateway/android/.../ObjektOfflineSyncWorker.kt:234`) und
  `clearCache()` (Zeile ~143) erzeugen ein **rohes**
  `android.webkit.WebView(applicationContext)` — nicht die
  Capacitor-`Bridge`, die die echte App-WebView normalerweise umschließt.
  Ein rohes `WebView` bekommt daher **nie** die JS-Interface
  `androidBridge`, die Capacitor nativ injiziert.
- `node_modules/@capacitor/core/dist/index.js:28-38` (`getPlatformId`)
  bestimmt die Plattform ausschließlich über `win.androidBridge` (→
  `'android'`) bzw. `win.webkit.messageHandlers.bridge` (→ `'ios'`), sonst
  `'web'`. Ohne `androidBridge` liefert `window.Capacitor.getPlatform()` in
  diesem WebView also `'web'`, und `window.Capacitor.Plugins.DeviceKeepalive`
  bleibt `undefined` (kein natives Plugin registriert sich auf einer Bridge,
  die nicht existiert).
- `app/static/js/objekt_offline_sync.js:32-36` (Backend-Repo) —
  `cacheStatus()` — ist die **einzige** Stelle, die den reichhaltigen
  Fortschritt/Fehlergrund an die App zurückmeldet (Manifest-Anzahl,
  Org-/Login-Typ, Status-Aufschlüsselung, "X/Y offline verfügbar",
  fehlgeschlagene Downloads — genau die Diagnose-Texte, die die Backend-
  Commits der letzten Tage, u.a. `f82696ea` von heute, extra eingebaut
  haben). Die Funktion prüft `window.Capacitor.Plugins.DeviceKeepalive`
  und gibt bei fehlendem Plugin **still** `undefined` zurück (Zeile 34):
  `if (!plugin || typeof plugin.reportObjectCacheStatus !== "function") { return; }`.
- Genau dieses Plugin ist im headless Worker-WebView nie vorhanden (siehe
  oben) → **jeder `cacheStatus()`-Aufruf aus `synchronisieren()` heraus
  verpufft lautlos**, wenn der Sync über `ObjektOfflineSyncWorker` läuft.
  `OfflineCacheStatusStore.updateObjects()` (Android-Repo,
  `OfflineCacheStatusStore.kt:17`) — die einzige Stelle, die
  `cached`/`total`-Zähler und den letzten Objekt-Aktivitätstext schreibt —
  wird für den Hintergrund-Sync **nie erreicht**.
- Was *stattdessen* ankommt, ist der separate, parallel existierende Kanal
  `ObjektSyncNative` (echtes `addJavascriptInterface`, unabhängig von
  Capacitor, siehe `ObjektOfflineSyncWorker.kt:261`) — der funktioniert auch
  headless, liefert aber nur generische Meldungen ("Offline-Skript geladen,
  Abgleich läuft", "Offline-Skript hat keinen erfolgreichen Abschluss
  gemeldet") **ohne** den eigentlichen Grund (HTTP-Status, leeres Manifest,
  Org/Login-Diagnose, Downloadfehler).

**Konsequenz:** Die reichhaltige Diagnose, die die letzten ~15 Commits in
beiden Repos aufgebaut haben, kommt für den Fall, um den es eigentlich geht
(Sync im Hintergrund, App nicht offen — der ursprüngliche Feature-Zweck laut
`docs/objekt-offline-sync-review.md`: "Fahrzeug im Funkloch"), **nie in
"Über die App" an**. Das erklärt plausibel, warum trotz vieler Fix-Versuche
weiterhin unklar ist/blieb, woran es hakt — es wurde an einem Kanal
diagnostiziert, der für den eigentlich betroffenen Pfad nie feuert.

Ob der eigentliche Cache-Aufbau (fetch + `caches.open()`/`cache.put()` in
`synchronisieren()`) im headless WebView tatsächlich gelingt, ist damit
**weiterhin ungeklärt** — das lässt sich aus dem Code allein nicht
beweisen, weil genau die Meldungen fehlen, die es zeigen würden. Die reine
Fetch-/Cache-Storage-API-Nutzung selbst hängt nicht von Capacitor ab und
sollte technisch auch ohne Bridge funktionieren, *sofern* die Session
(Cookie) in diesem neuen WebView verfügbar ist (siehe unten).

## Erwartete Fix-Richtung

`cacheStatus()` in `app/static/js/objekt_offline_sync.js` (und die
analoge Meldung in `objektOfflineCacheLeeren()`, Zeile ~187) soll **zuerst**
prüfen, ob ein generischer, Capacitor-unabhängiger Kanal vorhanden ist
(`window.ObjektSyncNative`/`window.ObjektCacheClearNative` — exakt die
Namen, die `ObjektOfflineSyncWorker.kt` per `addJavascriptInterface`
bereits injiziert, siehe `runSyncInWebView()` Zeile 261 bzw. `clearCache()`
Zeile 160), und nur wenn der nicht existiert auf
`window.Capacitor.Plugins.DeviceKeepalive.reportObjectCacheStatus`
zurückfallen (für den Fall, dass der Sync in der echten App-WebView läuft,
wo weiterhin *auch* das Capacitor-Plugin die about.html-Anzeige live
aktualisieren soll, falls die App gerade offen ist).

Sinnvoll dafür: `ObjektSyncNative`/`ObjektCacheClearNative` um eine
strukturierte Status-Methode erweitern (oder die bestehende `status(message)`
wiederverwenden), die `cached`/`total`/`activity` genauso wie
`reportObjectCacheStatus` an `OfflineCacheStatusStore.updateObjects()`
durchreicht — aktuell nimmt `ObjektSyncNative.status()`
(`ObjektOfflineSyncWorker.kt:249`) nur einen freien Text ohne Zähler entgegen
und ruft `OfflineCacheStatusStore.logActivity()`, nicht `updateObjects()`.

Nach diesem Fix sollte der nächste Hintergrund-Sync (oder ein manuell über
"Objekt-Cache löschen" + Re-Sync ausgelöster Lauf) zum ersten Mal die
*tatsächliche* Fehlerursache im Aktivitäts-Log von "Über die App" zeigen
(z. B. HTTP-Status von `/api/objekte/sync`, leeres Manifest mit
Org/Login-Info, oder Downloadfehler pro Datei) — das ist die Voraussetzung,
um das eigentliche Problem (falls es über das Reporting hinausgeht)
überhaupt zu erkennen.

## Weitere zu prüfende Punkte (sekundär, aber mit demselben Hintergrund-Sync-Kontext zusammenhängend)

1. **Session-Cookie im headless WebView:** `synchronisieren()` nutzt
   `fetch(..., { credentials: "same-origin" })` — das setzt voraus, dass das
   Session-Cookie aus der echten App-WebView bereits über den geteilten,
   prozessweiten `CookieManager` für das neue, rohe `WebView` sichtbar ist.
   Das sollte im selben App-Prozess grundsätzlich funktionieren (Android
   `CookieManager` ist ein Singleton pro App), aber bitte verifizieren, ob
   irgendwo `CookieManager.getInstance().flush()` nach dem Geräte-Login
   aufgerufen wird — falls nicht, könnte ein noch nicht auf Platte
   persistiertes Cookie in einem frisch gestarteten Worker-Prozess (nach
   Reboot/Prozess-Kill) fehlen. Kein bestätigter Bug, aber eine plausible
   Fehlerquelle, die sich mit einem expliziten `flush()` defensiv ausschließen
   lässt.
2. **Cache-Storage-Sichtbarkeit:** Bitte bestätigen, dass die
   `WorkManager`-Ausführung im selben Android-Prozess läuft wie die
   Haupt-App (kein `android:process`-Split in `AndroidManifest.xml` für den
   Worker) — sonst könnte das rohe `WebView` einen anderen
   WebView-Datenordner verwenden und die per `caches.put()` geschriebenen
   Einträge wären für den Service Worker der echten App-WebView unsichtbar.
3. Die alte Analyse `docs/objekt-offline-sync-review.md` ist mit dem oben
   beschriebenen Hauptbefund **nicht** mehr aktuell — der dort beschriebene
   Verdacht (Keepalive-Service stoppt, Timer feuert nicht) wurde durch den
   WorkManager-Ansatz bereits behoben; nicht erneut als Ausgangspunkt nehmen.

## Auftrag an Codex

1. **Verifikation** des Hauptbefunds (Reporting-Kanal unerreichbar im
   headless WebView) direkt im Code beider Repos, mit Beleg Datei/Zeile.
2. **Fix:** `cacheStatus()` (und die Meldung in `objektOfflineCacheLeeren()`)
   in `app/static/js/objekt_offline_sync.js` so erweitern, dass sie auch
   ohne Capacitor-Plugin-Bridge über `window.ObjektSyncNative`/
   `window.ObjektCacheClearNative` berichten kann; `ObjektOfflineSyncWorker.kt`
   entsprechend erweitern, damit diese native Schnittstelle
   `cached`/`total`/`activity` genauso in `OfflineCacheStatusStore` schreibt
   wie `reportObjectCacheStatus` es für die echte App-WebView tut.
3. Punkte 1–2 aus "Weitere zu prüfende Punkte" verifizieren, mit Beleg;
   `CookieManager.getInstance().flush()` nach Geräte-Login ergänzen, falls
   es aktuell fehlt und der Beleg aus Punkt 1 das nahelegt.
4. Bestehende Tests laufen lassen (`pytest` im Backend-Repo, Fokus auf
   `tests/test_objekt_pr9.py` und alle Offline-Sync-Tests); Android-Seite so
   weit wie ohne SDK möglich prüfen (Kotlin-Syntax, kein Gradle-Build nötig
   — das übernimmt `build-apk.yml`).
5. **Falls nach dem Reporting-Fix aus dem Code selbst (Server-Logs,
   bestehende Tests, Manifest-Logik) ein konkreter, weiterer Fehler im
   Sync-Ablauf erkennbar wird** (z. B. eine Auth-/Scoping-Lücke für
   Geräte-Logins), diesen ebenfalls beheben. Falls nicht: der Reporting-Fix
   allein ist bereits ein sinnvoller, in sich abgeschlossener PR — der
   nächste Schritt wäre dann ein echter Gerätetest mit den jetzt endlich
   sichtbaren Diagnosemeldungen.

**Bericht (fünf Abschnitte, konkret mit Datei/Zeile):**
1. Verifikationsergebnis Hauptbefund + Punkte 1–2.
2. Was umgesetzt wurde, je Datei in jedem Repo.
3. Was übersprungen wurde und warum.
4. Testergebnisse.
5. Was der Nutzer vor dem Mergen/Deployen noch prüfen sollte (insbesondere:
   welche konkrete Fehlermeldung nach dem Fix im Aktivitäts-Log erwartet
   wird, damit beim nächsten Gerätetest gezielt draufgeschaut werden kann).
