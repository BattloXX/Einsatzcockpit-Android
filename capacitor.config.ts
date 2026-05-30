import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'cloud.einsatzleiter.app',
  appName: 'einsatzleiter.cloud',
  // Remote-Modus: App lädt die produktive PWA direkt vom Server.
  // Kein lokales Bundle nötig – Updates kommen automatisch über den Server.
  server: {
    url: 'https://einsatzleiter.cloud',
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
