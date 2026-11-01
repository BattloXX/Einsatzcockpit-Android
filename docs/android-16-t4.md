# T4: targetSdk 36

Statische Prüfung und Build ersetzen keinen Gerätetest.

## 1. SDK — betroffen: ja

`android/variables.gradle` und der eigenständige Fallback in
`plugins/sms-gateway/android/build.gradle` verwenden jetzt targetSdk 36.
compileSdk 36 und minSdk 24 bleiben bestehen.

## 2. Edge-to-Edge — betroffen: ja

Quellen der tatsächlich installierten Version **Capacitor 8.6.0**:

- `node_modules/@capacitor/cli/dist/declarations.d.ts`, `PluginsConfig.SystemBars`:
  `insetsHandling` kennt `native`, `css`, `disable`. `android.adjustMarginsForEdgeToEdge`
  ist in dieser Version weder deklariert noch im Android-Quellcode implementiert.
- `node_modules/@capacitor/android/capacitor/src/main/java/com/getcapacitor/plugin/SystemBars.java`,
  `initWindowInsetsListener()`: Padding am DecorView bei fehlendem `viewport-fit=cover`;
  bei Chromium >=140 und `cover` werden Systemleisten-/Cutout-Insets an das WebView
  durchgereicht. Auch `native` erzwingt dann keinen geschützten Inhaltsbereich.
- Derselbe Quellcode, `injectSafeAreaCSS()`: `css` injiziert zusätzlich
  `--safe-area-inset-*`; `setStyle()`/`setHidden()` verwenden die Compat-Controller.

Alle vier lokalen HTML-Seiten (`index`, `gateway`, `about`, `offline`) haben einen
Viewport, aber kein `viewport-fit=cover` und keine `env(safe-area-inset-*)`-Regeln.
Die Remote-PWA liegt außerhalb dieses Repositorys; ihr Viewport/CSS wird nicht
als Voraussetzung für die Sicherheit der nativen Fenster verwendet.

Kleinster gemeinsamer robuster Weg: `SafeWindowInsets.install(window)` schützt die
gesamte Fensterhierarchie einschließlich ActionBar und SwipeRefreshLayout. Es
verwendet WindowCompat und einen ViewCompat-Inset-Listener, addiert ursprüngliches
Padding und das Maximum aus Systemleisten/Cutout und IME pro Seite. Dadurch
werden auch seitliche Cutouts und Tastaturwechsel behandelt. Die behandelten Insets
werden für Nachfahren auf null gesetzt, damit WebView/CSS sie nicht doppelt anwenden.
`SystemBars.insetsHandling: 'disable'` verhindert einen konkurrierenden Listener;
der eigene Listener wird nach dem Aufbau der Bridge installiert. HTML und Themes
brauchen damit keine Safe-Area-Sonderregeln oder Edge-to-Edge-Opt-outs.

Angewendet auf MainActivity, AlarmActivity, KontaktListActivity,
KontaktDetailActivity und die Activity hinter der Fahrt-Widget-Konfiguration.
Der dortige AlertDialog ist ein separates, vom Framework eingepasstes Fenster.
RemoteViews der Homescreen-Widgets werden vom Launcher eingepasst und erhalten
keinen zusätzlichen Activity-Inset-Code.

AlarmActivity: `FLAG_FULLSCREEN`, `statusBarColor`, `navigationBarColor` ersetzt
durch WindowInsetsControllerCompat (Statusleiste ausblenden, temporär einblendbar,
helle Symbole) und roten Decor-Hintergrund. Die Navigationsleiste bleibt bedienbar.
`showWhenLocked`, `turnScreenOn`, alte Lock-Screen-Fallbacks und
`FLAG_KEEP_SCREEN_ON` bleiben unverändert. Bei eingeblendeten Leisten schützt der
Listener den Inhalt; der ScrollView hält beide Alarmaktionen erreichbar.

Plattformquelle: [Android 16: Änderungen für targetSdk 36](https://developer.android.com/about/versions/16/behavior-changes-16).
Der Opt-out entfällt auf Android 16; Android 15 muss separat getestet werden.

## 3. Große Bildschirme — betroffen: keine Sperren, Insets wie oben

Alle eigenen Manifest-Activities geprüft: Main, Alarm, Kontaktliste, Kontaktdetail,
Fahrt-Widget-Konfiguration. Keine `screenOrientation`, `resizeableActivity`,
`minAspectRatio`/`maxAspectRatio` oder entsprechenden Laufzeit-Sperren vorhanden.
Kein temporärer Resizability-Opt-out nötig. Im gemergten Manifest gibt es
zusätzlich SDK-Activities (ScanActivity, GmsBarcodeScanningDelegateActivity,
GoogleApiActivity). Googles Barcode-Delegate deklariert `screenOrientation="portrait"`;
diese Fremd-SDK-Vorgabe wird auf großen Android-16-Displays ignoriert. Kein
Manifest-Override ohne Fehlernachweis; Barcode-Scan auf Tablet gesondert testen.

Lokale Seiten verwenden flexible Breiten, maximale Kartenbreiten und Dokument-
bzw. Listen-Scrolling. Alarm und Kontaktdetail sind ScrollViews mit dynamischen
Texthöhen; die Kontaktliste nutzt einen gewichteten RecyclerView. Die Widget-
Konfiguration nutzt einen AlertDialog mit ListView. Die übrigen Plugin-Layouts
sind Widget-RemoteViews, Listenzeilen oder Dialoge. Kein konkreter zusätzlicher
Layout-Fehler aus der statischen Prüfung; Rotation und kleine Split-Screen-Fenster
bleiben Teil des Gerätetests. Die Remote-PWA muss dabei ebenfalls geprüft werden.

Plattformquelle: derselbe Android-16-Leitfaden, Abschnitt „Adaptive layouts“.
Ab kleinster Displaybreite 600dp ignoriert Android 16 die genannten Sperren.

## 4. Vorausschauendes Zurück — betroffen: ja, Main-Fallback

Keine eigene Activity überschreibt `onBackPressed()` oder fängt `KEYCODE_BACK`
ab. AlarmActivity behandelt nur Lautstärketasten (Quittieren); normales Zurück
schließt weiterhin nur die Activity, ohne den Alarmdienst zu quittieren.
Kontakt-Activities und Widget-Dialog verwenden AppCompat/Framework-Verhalten.

`node_modules/@capacitor/app/android/src/main/java/com/capacitorjs/plugins/app/AppPlugin.java`
registriert bereits einen OnBackPressedCallback: ohne JS-Listener WebView-History,
aber ohne History keine Aktion. Es gibt im lokalen Web-Code keine `backButton`-
Listener. MainActivity übernimmt deshalb genau einen Dispatcher-Callback:
WebView zurück, sonst `moveTaskToBack(true)`. Der App-Plugin-Handler wird per
`App.disableBackButtonHandler` deaktiviert; die native Navigation ist damit auch
für die Remote-PWA zuständig. `android:enableOnBackInvokedCallback="true"` steht
explizit im App-Manifest, ohne Opt-out. Ein Dispatcher-Callback bedeutet nicht,
dass die WebView eine interaktive Vorschau ihrer History rendert.

Plattformquelle: Android-16-Leitfaden oben, Abschnitt „predictive back“.

## 5. Weitere Änderungen — teilweise betroffen, keine vorsorglichen Umbauten

- **Jobkontingente: ja.** AutoUpdateWorker (12h), ObjektOfflineSyncWorker (6h,
  internes Limit 9 Minuten), KontaktOfflineSyncWorker (6h) verwenden WorkManager,
  eindeutige Work-Namen und Netzwerkbedingungen. Einmalige Anforderungen und
  Wiederholungen bleiben durch WorkManager verwaltet. Keine Annahme eines
  unbegrenzten Kontingents durch parallele FGS und kein eigener JobService.
  Verzögerungen bei erschöpften Kontingenten sind möglich; kein belegter Anlass,
  Intervalle zu verkürzen, Jobs zu beschleunigen oder auf FGS zu verlagern.
- **FGS: kein neuer Typ-/Timeout-Fehler gefunden.** Gateway und Keepalive sind
  `specialUse` mit Subtyp-Beschreibung, Alarm ist `mediaPlayback`, das installierte
  Background-Geolocation-Plugin deklariert `location`; die Berechtigungen sind
  vorhanden. BootReceiver startet nur das konfigurierte Gateway, keinen
  mediaPlayback-/location-Dienst. Bestehende Start-/Berechtigungsregeln bleiben
  relevant; `specialUse` hebt Jobkontingente nicht auf.
- **Intents/Broadcast-Reihenfolge: kein konkreter Anpassungsbedarf.** Interne
  Activity-/Service-Intents sind explizit; SMS-Ergebnis-Broadcasts paketgebunden.
  Keine Broadcast-Prioritäts-/Abbruchlogik oder Abhängigkeit von globaler
  Empfangsreihenfolge gefunden. Dial/SMS/Mail verwenden normale implizite Intents.
- **Intent-Redirect-Schutz: relevante Stelle geprüft.** AutoUpdateInstallReceiver
  übernimmt den Bestätigungs-Intent aus dem PackageInstaller-Ergebnis in ein
  unveränderliches PendingIntent für eine Nutzeraktion. Der Receiver ist nicht
  exportiert. Kein ungeprüfter verschachtelter Intent aus dem exportierten
  MainActivity-Eingang und kein `removeLaunchSecurityProtection()`-Opt-out.
  Die echte System-Installationsbestätigung ist im Gerätetest zu prüfen.
- **16KB-Seiten: native Bibliotheken vorhanden.** Das Barcode-Plugin verwendet
  ML Kit Barcode Scanning 17.3.0. APK-ELF-/ZIP-Prüfung und verbleibender
  Laufzeittest werden unten dokumentiert. Kein `pageSizeCompat`-Opt-out.
- Keine Verwendung von `scheduleAtFixedRate`, `elegantTextHeight`-Overrides oder
  festen Textzeilenhöhen in den geprüften eigenen Activity-Layouts gefunden.

Quellen: [Android 16: Änderungen für alle Apps](https://developer.android.com/about/versions/16/behavior-changes-all),
[16KB-Speicherseiten prüfen](https://developer.android.com/guide/practices/page-sizes).

## Verifikation

Plugin-Tests (44, davon 3 neue Insets-Tests), native-files-Tests (3) und `node scripts/offline-page.test.mjs` (3) sind grün, `assembleDebug` baut, das gemergte Manifest zeigt targetSdkVersion 36. `lint` ist ohne Baseline nicht grün (10 Fehler, 89 Warnungen, nicht durch T4 verursacht) und läuft deshalb nicht in der CI.

16KB-Seiten: alle 64-Bit-Bibliotheken (arm64-v8a, x86_64) der Debug-APK sind auf >=16384 ausgerichtet, `zipalign -c -P 16 4` besteht. Die 32-Bit-Dateien von ML Kit (armeabi-v7a, x86) haben 4096; das betrifft 16KB-Geräte nicht. Ein Scan auf einem echten 16KB-System steht aus.

## Gerätetest (noch durchzuführen)

- [ ] Dieselbe Debug-APK auf Android 15 und 16 installieren; jeweils Gestennavigation
  und Drei-Tasten-Navigation, Hoch- und Querformat sowie Cutout-Gerät testen.
- [ ] Launcher, Gateway, About und Offline-Seite sowie angemeldete Remote-PWA:
  oberste/unterste Aktionen und seitliche Inhalte sichtbar; Scrollen und
  Pull-to-Refresh samt Spinner funktionieren. Offline wechseln und Retry testen.
- [ ] Tastatur in PWA-Formularen und Kontaktsuche öffnen/schließen; fokussiertes Feld
  erreichbar, kein doppelter Rand und nach Schließen keine Restlücke. WebView
  möglichst vor und ab Chromium 140 prüfen.
- [ ] Zurück-Geste und Zurück-Taste: History genau einen Schritt zurück; ohne History
  App verstecken; erneutes Öffnen funktioniert. Kontakt-Detail/-Liste und
  Widget-Dialog schließen normal; abgebrochene Geste führt keine Aktion aus.
- [ ] Testalarm bei gesperrtem Bildschirm und ausgeschaltetem Display: Anzeige
  geht weiterhin an und über den Sperrbildschirm; Bildschirm bleibt wach.
  Lange Meldung scrollen, beide Aktionen erreichen; Leisten einblenden,
  Lautstärketaste quittiert, „Einsatz öffnen“ entsperrt wie bisher.
  Zurück allein quittiert den Alarmdienst weiterhin nicht.
- [ ] Android-16-Tablet >=600dp: alle lokalen Seiten, Remote-PWA, Alarm,
  Kontaktansichten und Fahrt-Widget-Konfiguration rotieren; Split-Screen mehrfach
  verkleinern/vergrößern. Lange Kontakt-/Fahrzeuglisten, lange Alarmtexte und
  große Schrift testen; keine verdeckten Aktionen oder doppelten Insets.
  Auch Googles Barcode-Scanner im Querformat und Split-Screen öffnen, Scan
  abschließen/abbrechen und Rückkehr zur PWA prüfen.
- [ ] Gateway nach Boot und bei ausgeschaltetem Display; Alarm per Push,
  Standortaufzeichnung mit erteilter Berechtigung und deren Stop testen.
- [ ] Auto-Update (dafür Auto-Update-APK verwenden), Objekt- und Kontakt-Sync bei
  Netzverlust/-wiederkehr und Standby-Buckets active/rare prüfen. Laufenden Sync
  in Hintergrund schicken, auch parallel zum Gateway; spätere Wiederaufnahme
  und Datenstand prüfen. Bei Verzögerungen WorkInfo.stopReason und
  `adb shell dumpsys jobscheduler` auswerten.
- [ ] Auto-Update mit erforderlicher Nutzerbestätigung: Benachrichtigung öffnet
  den PackageInstaller-Dialog; Bestätigen und Abbrechen testen.
- [ ] Auf 16KB-Emulator/-Gerät (`adb shell getconf PAGE_SIZE` muss 16384 liefern)
  App starten und ML-Kit-Barcode-Scan ausführen; kein Kompatibilitätsdialog,
  kein nativer Ladefehler.
