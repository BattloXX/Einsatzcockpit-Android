# Abhängigkeiten aktualisieren

Dependabot prüft GitHub Actions, npm im Root und Gradle in `android/` wöchentlich.
AndroidX, Kotlin/KSP und Firebase werden jeweils gruppiert. Capacitor samt
Zusatzplugins sowie Kotlin/KSP/AGP/Gradle erhalten keine automatischen Major-Updates.
Gradle-Abhängigkeiten in lokalen Plugins bei Upgrades zusätzlich manuell prüfen.

Capacitor-Majors manuell mit `npx cap migrate` migrieren, anschließend
`npx cap sync android` ausführen und den Diff prüfen: `android/` ist versioniert.
Kotlin, KSP, AGP und Gradle gemeinsam auf Kompatibilität prüfen und anheben.
Danach Unit-Tests und `assembleDebug` ausführen. Robolectric verwendet `sdk=35`
in `plugins/sms-gateway/android/src/test/resources/robolectric.properties`.
Vor Freigabe die Gerätetest-Checkliste in [android-16-t4.md](android-16-t4.md) abarbeiten.

# Gerätebericht (Schema 1)

Native Diagnose liegt unter `plugins/sms-gateway/android`: `DeviceInfoCollector`
liest ausschließlich Systemzustand, `DiagnoseReportLogic` begrenzt Felder und
Einstellungen per Whitelist und serialisiert ohne Android-Abhängigkeit.
Keine Hardware-IDs, Tokens, SMS-Inhalte oder Standortdaten aufnehmen. Unbekannte
Werte auslassen bzw. die unbekannte Uhrabweichung als `null` melden; SIM-Anzahl
nur bei bereits gewährtem Telefonstatuszugriff, Foldable nur bei erkennbarer
Hinge-Funktion. Herstelleroberflächen sind eine unverbindliche Zuordnung.

`ReportSender` verwendet persistente Unique Work mit Netzbedingung und
exponentiellem Backoff. App-Starts werden auf sechs Stunden, Fehlerauslöser auf
zehn Minuten begrenzt; Einstellungen und manueller Versand lösen direkt aus.
`last_sent` wird ausschließlich nach erfolgreicher Serverbestätigung gesetzt.
Die UI bestätigt daher zunächst nur die Vormerkung zum Versand.
`NativeLog.error` speichert maximal 50 maskierte, gezählte Fehler zusätzlich zum
kompatiblen Textlog. Neue Fehlerstellen mit stabiler Komponente und Code sowie
kurzem technischem Text instrumentieren; keine Nutzdaten übergeben.
Tests: reine JUnit-Tests für Schema/Drosselung, Robolectric mit SDK 35 für
Systemabfragen und persistente Fehler. Versand/Auth und Gerätebesonderheiten
zusätzlich auf einem echten Gerät gegen die Zentrale prüfen.
