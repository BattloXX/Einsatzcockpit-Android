# Build-Image für den Anthropic OSS Scanner (https://github.com/anthropics/oss-scanner).
# Baut die Capacitor-App wie .github/workflows/build-apk.yml (Debug-APK, ohne Keystore,
# Platzhalter-google-services.json). Danach läuft die Analyse OHNE Netz – SDK, npm- und
# Gradle-Abhängigkeiten werden hier vollständig geholt.
# Capacitor 8 benötigt weiterhin JDK 21.
FROM eclipse-temurin:21-jdk-noble

ENV DEBIAN_FRONTEND=noninteractive \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    NODE_VERSION=22.22.0 \
    GRADLE_USER_HOME=/opt/gradle-home
ENV PATH=/opt/node/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$PATH

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl unzip git python3 xz-utils \
    && rm -rf /var/lib/apt/lists/*

# Node.js 22 (Capacitor 8: Node.js 22+)
RUN curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" -o /tmp/node.tar.xz \
    && mkdir -p /opt/node && tar -xJf /tmp/node.tar.xz -C /opt/node --strip-components=1 && rm /tmp/node.tar.xz

# Android SDK (cmdline-tools, Plattform 36, Build-Tools)
RUN mkdir -p /opt/android-sdk/cmdline-tools \
    && curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdline.zip \
    && unzip -q /tmp/cmdline.zip -d /opt/android-sdk/cmdline-tools && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && rm /tmp/cmdline.zip \
    && yes | sdkmanager --licenses > /dev/null \
    && sdkmanager "platform-tools" "platforms;android-36" "platforms;android-35" "build-tools;35.0.0" "build-tools;36.0.0" > /dev/null

# Scanner-Vertrag: der Checkout liegt im Image unter /src
COPY . /src
WORKDIR /src

# Gleiche Vorbereitung wie die CI (versioniertes Android-Projekt, cap sync, Platzhalter-Firebase)
RUN npm ci && bash scripts/prepare-android.sh \
    && cp assets/Logo-rot.png assets/icon.png \
    && cp assets/Logo-rot.png assets/icon-foreground.png \
    && npx capacitor-assets generate --android

# Debug-APK bauen – lädt alle Gradle-Abhängigkeiten in den Cache, damit Analyse/Build offline funktionieren.
# Standard-Variante (ohne Auto-Update), wie im CI-Schritt "Build Standard-APK"
RUN cd android && ./gradlew --no-daemon assembleDebug
