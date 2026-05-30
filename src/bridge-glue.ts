/**
 * bridge-glue.ts – Capacitor Plugin-Initialisierung für einsatzleiter.cloud
 *
 * Diese Datei wird in das WebView injiziert und bindet die Capacitor-Plugins
 * an das window.ELNative-Interface, das native-bridge.js in der Web-App erwartet.
 *
 * Da die App die Web-App per server.url lädt, werden native-bridge.js und
 * dieses Glue-Script über den Capacitor-eigenen Injektions-Mechanismus geladen
 * (capacitor.config.ts → cordovaInlineScripts oder über ein Plugin).
 *
 * Im einfachsten Ansatz: Dieses Script im MainActivity-onCreate mittels
 * webView.evaluateJavascript() oder über eine custom WebViewPlugin injizieren.
 */

import { App } from '@capacitor/app';
import { PushNotifications } from '@capacitor/push-notifications';
import { Preferences } from '@capacitor/preferences';

// ── Dauerhafter Login ────────────────────────────────────────────────────────
// Token-Schlüssel in Secure Preferences
const DEVICE_TOKEN_KEY = 'el_device_token';

/**
 * Transparentes Re-Login: Beim App-Start gespeicherten Device-Token verwenden
 * und /geraet-login?token=... aufrufen, falls die Session abgelaufen ist.
 */
export async function ensureLoggedIn(webView: any): Promise<void> {
  const { value: token } = await Preferences.get({ key: DEVICE_TOKEN_KEY });
  if (!token) return; // Kein Token gespeichert → normaler Login-Flow

  // Prüfen ob bereits eine Session vorhanden ist (Cookie gesetzt)
  // Die Web-App leitet /login auf /geraet-login weiter wenn session fehlt,
  // daher reicht es die App-URL zu laden – der /geraet-login-Handler setzt den Cookie.
  const loginUrl = `https://einsatzleiter.cloud/geraet-login?token=${encodeURIComponent(token)}`;
  webView.loadUrl(loginUrl);
}

/**
 * Device-Token nach QR-Scan speichern für dauerhaften Login.
 * Wird von native-bridge.js nach erfolgreichem /geraet-login aufgerufen.
 */
export async function saveDeviceToken(token: string): Promise<void> {
  await Preferences.set({ key: DEVICE_TOKEN_KEY, value: token });
}

/**
 * Token beim Logout löschen.
 */
export async function clearDeviceToken(): Promise<void> {
  await Preferences.remove({ key: DEVICE_TOKEN_KEY });
}

// ── App-Lifecycle ────────────────────────────────────────────────────────────
App.addListener('appStateChange', ({ isActive }) => {
  if (isActive) {
    // App in Vordergrund → Duty-State prüfen (native-bridge.js macht das auch,
    // hier als zusätzlicher Trigger nach App-Resume)
    (window as any).ELNative?.startLocation?.();
  }
});

// ── Push-Notification Tap-Handler ────────────────────────────────────────────
PushNotifications.addListener('pushNotificationActionPerformed', (action) => {
  const url = action?.notification?.data?.url;
  if (url) {
    window.location.href = url;
  }
});
