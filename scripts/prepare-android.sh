#!/usr/bin/env bash
# Nach npm ci: generierte Capacitor-Dateien synchronisieren und Firebase konfigurieren.
# Android-Quellen und Build-Konfiguration liegen versioniert unter android/.
# Optional: GOOGLE_SERVICES_JSON (Inhalt); ohne gültiges JSON wird ein Platzhalter geschrieben.
set -euo pipefail
cd "$(dirname "$0")/.."

npx cap sync android

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
