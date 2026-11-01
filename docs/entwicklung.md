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
