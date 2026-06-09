import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'cloud.einsatzleiter.app',
  appName: 'einsatzleiter.cloud',
  // www/index.html dient als Launcher: entscheidet beim Start ob Gateway-Modus
  // oder Einheit-Gerät-Modus (Weiterleitung zur Remote-PWA).
  webDir: 'www',
  server: {
    // Kein server.url – App startet immer von index.html (lokal).
    // allowNavigation erlaubt WebView-Navigation zur Remote-PWA ohne externen Browser.
    cleartext: false,
    allowNavigation: [
      'einsatzleiter.cloud',
      '*.einsatzleiter.cloud',
    ],
  },
  android: {
    buildOptions: {
      releaseType: 'APK', // Für Sideload – kein AAB/Play Store
    },
  },
  plugins: {
    PushNotifications: {
      presentationOptions: ['badge', 'sound', 'alert'],
    },
  },
};

export default config;
