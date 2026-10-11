# Sicherheitsrichtlinie / Security Policy

## Sicherheitslücken melden

Bitte melde Sicherheitslücken **nicht** über öffentliche Issues.

- E-Mail: **oss-scanner@get.einsatzcockpit.com**
- oder über GitHub: *Security → Report a vulnerability* (Private Vulnerability Reporting)

Hilfreich sind: betroffene Version, Beschreibung, Reproduktionsschritte und Auswirkung.

## Unterstützte Versionen

Sicherheitsfixes gibt es für das jeweils neueste Release.

## Automatisierte Prüfung

Die App nimmt am [Anthropic OSS Scanner](https://github.com/anthropics/oss-scanner) teil.
Build-Anleitung und Bedrohungsmodell liegen unter `.oss-scanner/`; die Android-Vorbereitung
der CI steckt in `scripts/prepare-android.sh`.
