// Gemeinsamer GitHub-Release-Check für about.html und gateway.html.
const GITHUB_REPO = 'BattloXX/Einsatzcockpit-Android';
const UPDATE_CHANNEL_KEY = 'ec_update_channel';
let latestApkUrl = null;
let autoUpdateVariant = false;
const automaticallyRequestedVersions = new Set();

async function initializeUpdateChannelToggle() {
  const select = document.getElementById('updateChannel');
  if (!select || !Preferences) return;
  try {
    const { value } = await Preferences.get({ key: UPDATE_CHANNEL_KEY });
    select.value = value === 'prerelease' ? 'prerelease' : 'stable';
  } catch (_) {
    select.value = 'stable';
  }
}

async function setUpdateChannel(channel) {
  const value = channel === 'prerelease' ? 'prerelease' : 'stable';
  const select = document.getElementById('updateChannel');
  if (!Preferences) return;
  try {
    await Preferences.set({ key: UPDATE_CHANNEL_KEY, value });
    if (select) select.value = value;
    await manualUpdateCheck();
  } catch (_) {
    if (select) select.value = 'stable';
    toast('Update-Kanal konnte nicht gespeichert werden.');
  }
}

async function manualUpdateCheck() {
  const btn = document.getElementById('btnCheckUpdate');
  if (btn.disabled) return;
  btn.disabled = true;
  const label = btn.querySelector('.btn-text') || btn;
  const prev = label.textContent;
  label.textContent = 'Prüfe…';
  document.getElementById('availableVersion').textContent = 'Prüfe…';
  document.getElementById('availableVersion').className = 'version-value';
  document.getElementById('updateBtnRow').style.display = 'none';
  try {
    const autoUpdateEnabled = await checkForUpdate(false);
    if (autoUpdateEnabled) {
      try {
        await window.Capacitor?.Plugins?.DeviceKeepalive?.triggerAutoUpdateCheck?.();
      } catch (_) {
        // The informational release check remains useful when the native trigger fails.
      }
      setTimeout(() => {
        if (typeof updateAutoUpdateStatus === 'function') updateAutoUpdateStatus();
      }, 2000);
    }
    const av = document.getElementById('availableVersion').textContent;
    toast(document.getElementById('availableVersion').classList.contains('up-to-date') ? 'App ist aktuell' : 'Update verfügbar: ' + av);
  } finally {
    btn.disabled = false;
    label.textContent = prev;
  }
}

async function checkForUpdate(triggerAutomatically = true) {
  let autoUpdateEnabled = false;
  if (SmsGateway && SmsGateway.getAppVersion) {
    try {
      const version = await SmsGateway.getAppVersion();
      const versionName = version.versionName;
      autoUpdateEnabled = version.autoUpdateEnabled === true;
      document.getElementById('installedVersion').textContent = 'v' + versionName;
      const pill = document.getElementById('verPill');
      if (pill) pill.innerHTML = 'ECPG <b style="color:var(--yellow)">v' + versionName + '</b>';
      autoUpdateVariant = autoUpdateEnabled;
      document.getElementById('autoUpdateCard').style.display = autoUpdateEnabled ? '' : 'none';
      if (autoUpdateEnabled) await updateAutoUpdateStatus();
    } catch (_) {
      document.getElementById('installedVersion').textContent = 'unbekannt';
    }
  }

  try {
    const { value } = await Preferences.get({ key: UPDATE_CHANNEL_KEY });
    const channel = value === 'prerelease' ? 'prerelease' : 'stable';
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 8000);
    let res;
    try {
      res = await fetch(
        'https://api.github.com/repos/' + GITHUB_REPO + '/releases',
        { headers: { Accept: 'application/vnd.github+json' }, signal: controller.signal }
      );
    } finally {
      clearTimeout(timeoutId);
    }
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const releases = await res.json();
    const rel = (releases || []).find((release) =>
      release.draft === false && (channel === 'prerelease' || release.prerelease === false)
    );
    if (!rel) throw new Error('Keine passende Veröffentlichung');
    const latestTag = rel.tag_name || '–';
    const asset = (rel.assets || []).find((candidate) => {
      const name = candidate.name || '';
      return autoUpdateEnabled
        ? name.startsWith('einsatzcockpit-autoupdate-v') && name.endsWith('.apk')
        : name.startsWith('einsatzcockpit-v') && name.endsWith('.apk') && !name.includes('-autoupdate-');
    });
    latestApkUrl = asset ? asset.browser_download_url : null;

    const installedEl = document.getElementById('installedVersion');
    const availableEl = document.getElementById('availableVersion');
    const installedVer = installedEl.textContent.replace(/^v/, '');
    const latestVer = latestTag.replace(/^v/, '');
    if (latestVer && installedVer !== '–' && installedVer !== 'unbekannt' && compareUpdateVersions(latestVer, installedVer) > 0) {
      availableEl.textContent = latestTag;
      availableEl.className = 'version-value update-available';
      document.getElementById('updateBtnRow').style.display = asset ? '' : 'none';
      if (typeof addLog === 'function') addLog('Update verfügbar: ' + latestTag + ' (installiert: v' + installedVer + ')');
      const plugin = window.Capacitor?.Plugins?.DeviceKeepalive;
      if (triggerAutomatically && autoUpdateEnabled && plugin?.triggerAutoUpdateCheck
          && !automaticallyRequestedVersions.has(latestVer)) {
        automaticallyRequestedVersions.add(latestVer);
        document.getElementById('autoUpdateDownload').textContent = 'Update wird geladen...';
        try {
          await plugin.triggerAutoUpdateCheck({ automaticVersion: latestVer });
        } catch (_) {
          document.getElementById('autoUpdateDownload').textContent = 'Update konnte nicht gestartet werden';
        }
        setTimeout(updateAutoUpdateStatus, 2000);
      }
    } else {
      availableEl.textContent = latestTag + ' (aktuell)';
      availableEl.className = 'version-value up-to-date';
    }
  } catch (e) {
    const grund = e && e.name === 'AbortError' ? 'Zeitüberschreitung' : (e && e.message) || 'unbekannter Fehler';
    document.getElementById('availableVersion').textContent = 'Nicht abrufbar (' + grund + ')';
    document.getElementById('availableVersion').className = 'version-value';
  }

  return autoUpdateEnabled;
}

function downloadApk() {
  if (latestApkUrl) window.open(latestApkUrl, '_system');
  else toast('Kein Download-Link verfügbar');
}

// Match the native worker's numeric version comparison (including daily suffixes).
function compareUpdateVersions(first, second) {
  const left = first.split('.').map(part => /^\d+$/.test(part) ? Number(part) : 0);
  const right = second.split('.').map(part => /^\d+$/.test(part) ? Number(part) : 0);
  for (let index = 0; index < Math.max(left.length, right.length); index++) {
    const difference = (left[index] || 0) - (right[index] || 0);
    if (difference) return difference;
  }
  return 0;
}

function autoUpdateText(value, fallback = '–') {
  return value && String(value).trim() ? String(value) : fallback;
}

async function updateAutoUpdateStatus() {
  if (!autoUpdateVariant) return;
  const plugin = window.Capacitor?.Plugins?.DeviceKeepalive;
  if (!plugin?.getAutoUpdateStatus) return;
  try {
    const status = await plugin.getAutoUpdateStatus();
    document.getElementById('autoUpdateChecked').textContent = status.lastCheckedAtMs
      ? new Date(Number(status.lastCheckedAtMs)).toLocaleString('de-AT', { dateStyle: 'short', timeStyle: 'short' })
      : 'Noch nicht geprüft';
    document.getElementById('autoUpdateRelease').textContent = status.releaseVersion
      ? 'v' + status.releaseVersion
      : '–';
    document.getElementById('autoUpdateDownload').textContent = autoUpdateText(status.downloadResult, status.activity || '–');
    document.getElementById('autoUpdateInstall').textContent = autoUpdateText(status.installResult);
    document.getElementById('autoUpdatePermission').style.display = status.canRequestPackageInstalls === false ? '' : 'none';
    const error = document.getElementById('autoUpdateError');
    error.textContent = status.error || '';
    error.style.display = status.error ? '' : 'none';
  } catch (_) {
    document.getElementById('autoUpdateChecked').textContent = 'Status nicht verfügbar';
  }
}

async function openAutoUpdateInstallSettings() {
  try {
    await window.Capacitor.Plugins.DeviceKeepalive.openInstallPermissionSettings();
  } catch (_) {
    toast('Android-Einstellungen konnten nicht geöffnet werden.');
  }
}

// Keep diagnostics and permission state current, including after returning from settings.
setInterval(() => {
  if (document.visibilityState === 'visible') updateAutoUpdateStatus();
}, 5000);
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') updateAutoUpdateStatus();
});
document.addEventListener('resume', updateAutoUpdateStatus);
