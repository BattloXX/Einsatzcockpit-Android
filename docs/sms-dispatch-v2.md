# SMS-Dispatch v2 – Multi-Worker, Phase 3

Cloud-Basis: Einsatzcockpit Phase 2 plus Migration 0313. Kein produktiver Rollout im Rahmen dieser Änderung.

Das Gateway meldet `sms-transaction-v2`. `sms.prepare` wird zuerst im SQLite-Journal gespeichert und mit `sms.ack` beantwortet. Erst `sms.commit` erlaubt den Modemaufruf. Der Versandbeginn wird davor atomar gespeichert. Doppelte Freigaben und reconnectbedingte Wiederholungen lösen keinen zweiten Modemaufruf aus.

`received` bestätigt nur den lokalen Auftragsempfang. `attempting` bezeichnet einen möglichen Modemversuch. Erst erfolgreiche Callbacks aller eindeutigen SMS-Teile ergeben `sent` / `provider_accepted=true`. Eine tatsächliche Empfängerzustellung wird nicht behauptet (`delivery_verified=false`). Negative oder fehlende Teilrückmeldungen nach einem Versuch und ein Neustart nach gespeichertem Versandbeginn führen zu `uncertain`; es gibt keinen automatischen Neuversand.

Das Journal ist nach Cloud-/Kopplungstoken getrennt. Hello enthält offene Job-IDs und Generationen. Gespeicherte Ergebnisse werden nach Reconnect und alle fünf Sekunden erneut gemeldet, bis `sms.result.ack` eintrifft. Text und Ziel werden nach lokalem Abschluss entfernt; ID-/Inhaltsfingerabdruck-Tombstones bleiben zur Deduplizierung erhalten. Journal oder App-Daten bei unklaren Jobs nicht löschen.

Eine neue Cloud kann offene vorbereitete Jobs auf derselben Generation freigeben. Ein altes `sms.send` wird weiterhin direkt, aber durch dieselbe lokale Intent-Grenze verarbeitet. Alte Cloud-Versionen bestätigen persistierte Ergebnisse nicht; der verbesserte workerübergreifende Ergebnisabgleich benötigt die neue Cloud. Eine neue Gateway-Version verhindert keine Doppelaufträge, die eine alte Cloud mit verschiedenen IDs neu erzeugt.

Socket-Callbacks sind an eine lokale Verbindungsgeneration und die ursprüngliche Kopplung gebunden. Ein alter Disconnect setzt die neue Verbindung nicht offline. Die bestehenden UI-Versandzähler und `smsQueued`/`smsSent`-Ereignisse sowie die Empfangs-Outbox bleiben erhalten.

## Prüfung

Sieben Robolectric-Tests prüfen Receipt/Freigabe, mehrfache Commits, Neustart nach Intent, Reconnect vor Intent, Vorab-Ablehnung ohne Modemaufruf, Multipart-Deduplizierung, Kopplungstrennung, Ergebnis-Replay und alte Generationen/Inhaltskonflikte:

```sh
ANDROID_HOME=/path/to/sdk ./gradlew :einsatzleiter-sms-gateway-plugin:testDebugUnitTest
```

Der Test liegt unter `plugins/sms-gateway/android/src/test`. Zusätzlich wurde der Kotlin-Debug-Build ausgeführt. Ein erfolgreiches automatisiertes Protokoll ersetzt keine Alarm-SMS-Abnahme am realen Gerät. Reale Annahme- und Zustellprüfung sowie Unterbrechungen mit 2/4 Cloud-Workern stehen vor produktiver Freigabe noch aus.
