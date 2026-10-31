#!/usr/bin/env bash
# Bereitet das Android-Projekt vor (cap add/sync, SDK-36/AGP-Patch, google-services.json,
# Manifest-Berechtigungen). Genutzt von .github/workflows/build-apk.yml und .oss-scanner/Dockerfile.
# Optional: GOOGLE_SERVICES_JSON (Inhalt); ohne gültiges JSON wird ein Platzhalter geschrieben.
set -euo pipefail
cd "$(dirname "$0")/.."

npx cap add android
mkdir -p android/app/src/main/assets
npx cap sync android

# Eigene Activity verarbeitet App Links auch bei bereits laufender App.
cp scripts/MainActivity.java android/app/src/main/java/cloud/einsatzleiter/app/MainActivity.java

# App-local Java plugin: no npm package; registered explicitly in MainActivity.
grep -q "include ':native-files'" android/settings.gradle || cat >> android/settings.gradle <<'GRADLE'

include ':native-files'
project(':native-files').projectDir = new File('../plugins/native-files/android')
GRADLE
grep -q "project(':native-files')" android/app/build.gradle || \
  sed -i "s|^dependencies {|dependencies {\n    implementation project(':native-files')|" android/app/build.gradle

# Pull-to-Refresh (MainActivity nutzt SwipeRefreshLayout)
grep -q "androidx.swiperefreshlayout" android/app/build.gradle || \
  sed -i "s|^dependencies {|dependencies {\n    implementation 'androidx.swiperefreshlayout:swiperefreshlayout:1.1.0'|" android/app/build.gradle
grep -q "androidx.swiperefreshlayout" android/app/build.gradle || { echo "SwipeRefresh-Abhaengigkeit fehlgeschlagen"; exit 1; }

# Android SDK 36 und AGP
sed -i "s|com.android.tools.build:gradle:8.7.2|com.android.tools.build:gradle:8.9.1|" android/build.gradle
sed -i "s|compileSdkVersion = 35|compileSdkVersion = 36|" android/variables.gradle
echo "android.suppressUnsupportedCompileSdk=36" >> android/gradle.properties
cat >> android/build.gradle <<'GRADLE'
subprojects { afterEvaluate { p -> if (p.hasProperty('android')) { p.android.compileSdk = 36 } } }
GRADLE
grep -q "compileSdkVersion = 36" android/variables.gradle || { echo "SDK-36-Patch fehlgeschlagen"; exit 1; }
grep -q "gradle:8.9.1" android/build.gradle || { echo "AGP-Patch fehlgeschlagen"; exit 1; }

# google-services.json (Secret oder Platzhalter – FCM funktioniert erst mit echtem Secret)
SECRET="${GOOGLE_SERVICES_JSON:-}"
if [ -n "$SECRET" ] && printf '%s' "$SECRET" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
  printf '%s' "$SECRET" > android/app/google-services.json
else
  cat > android/app/google-services.json <<'PLACEHOLDER'
{
  "project_info": { "project_number": "000000000000", "project_id": "placeholder", "storage_bucket": "placeholder.appspot.com" },
  "client": [{
    "client_info": { "mobilesdk_app_id": "1:000000000000:android:0000000000000000", "android_client_info": { "package_name": "cloud.einsatzleiter.app" } },
    "oauth_client": [], "api_key": [{ "current_key": "placeholder" }], "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
  }],
  "configuration_version": "1"
}
PLACEHOLDER
  echo "GOOGLE_SERVICES_JSON nicht gesetzt - Platzhalter verwendet (FCM inaktiv)"
fi

# AndroidManifest: Standort- und SMS-Gateway-Berechtigungen
python3 - <<'PYEOF'
path = "android/app/src/main/AndroidManifest.xml"
with open(path, encoding="utf-8") as f:
    content = f.read()

PERMISSIONS = [
    # GPS / Standort
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_LOCATION",
    # SMS-Gateway
    "android.permission.SEND_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    # specialUse statt dataSync: dataSync hat unter Android 15 ein
    # ~6h/24h-Laufzeitlimit, das den 24/7-Gateway-Betrieb beendet.
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    # Native Sirene und Vollbildalarm
    "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
    "android.permission.USE_FULL_SCREEN_INTENT",
    "android.permission.VIBRATE",
    "android.permission.MODIFY_AUDIO_SETTINGS",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    # Benachrichtigungen für Foreground-Service (Android 13+)
    "android.permission.POST_NOTIFICATIONS",
    # Foto-Upload (Einsatz-/Aufgaben-/Stellen-Medien): das WebView-Datei-
    # Feld (capture="environment") bietet "Kamera" sonst nicht zuverlässig
    # an, weil Capacitors Datei-Chooser die Kamera-Option nur zeigt, wenn
    # die App die Permission bereits (deklarativ) besitzt - bisher kam sie
    # nur transitiv ueber den Barcode-Scanner-Plugin mit, was fragil war.
    "android.permission.CAMERA",
]

added = []
for perm in PERMISSIONS:
    tag = f'<uses-permission android:name="{perm}" />'
    if perm not in content:
        content = content.replace("</manifest>", f"    {tag}\n</manifest>")
        added.append(perm)

gps_feature = '<uses-feature android:name="android.hardware.location.gps" android:required="false" />'
if "android.hardware.location.gps" not in content:
    content = content.replace("</manifest>", f"    {gps_feature}\n</manifest>")
    added.append("hardware.location.gps")

camera_feature = '<uses-feature android:name="android.hardware.camera" android:required="false" />'
if "android.hardware.camera" not in content:
    content = content.replace("</manifest>", f"    {camera_feature}\n</manifest>")
    added.append("hardware.camera")

# Nur die auf dem Server per assetlinks.json verifizierte Domain registrieren.
app_link = '''
            <intent-filter android:autoVerify="true">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="https" android:host="einsatzcockpit.com" />
            </intent-filter>
'''
if 'android:autoVerify="true"' not in content:
    if content.count("</activity>") != 1:
        raise SystemExit("MainActivity fuer App Links nicht eindeutig gefunden")
    content = content.replace("</activity>", app_link + "        </activity>", 1)

# Pro FCM-Intent darf nur EIN Service registriert sein, sonst stellt Firebase an den
# zuerst gelisteten zu. Capacitors MessagingService stand im gemergten Manifest vor
# EinsatzFirebaseMessagingService -> Data-only-Nachrichten (nativer Alarm, Wake,
# Zustellbestaetigung) kamen nie bei uns an. tools:node="remove" wirkt nur im
# App-Manifest zuverlaessig, nicht im Manifest einer Bibliothek.
if "xmlns:tools=" not in content:
    content = content.replace(
        'xmlns:android="http://schemas.android.com/apk/res/android"',
        'xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:tools="http://schemas.android.com/tools"',
        1,
    )
capacitor_fcm = (
    '        <service\n'
    '            android:name="com.capacitorjs.plugins.pushnotifications.MessagingService"\n'
    '            tools:node="remove" />\n'
)
if "com.capacitorjs.plugins.pushnotifications.MessagingService" not in content:
    if content.count("</application>") != 1:
        raise SystemExit("application-Element fuer FCM-Service-Entfernung nicht eindeutig gefunden")
    content = content.replace("</application>", capacitor_fcm + "    </application>", 1)
    added.append("remove Capacitor MessagingService")

with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print("Hinzugefuegt:" if added else "Alle Berechtigungen vorhanden.", added)
PYEOF
