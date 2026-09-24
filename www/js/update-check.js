// Gemeinsamer manueller GitHub-Release-Check für about.html und gateway.html.
const GITHUB_REPO = 'BattloXX/Einsatzcockpit-Android';
const UPDATE_CHANNEL_KEY = 'ec_update_channel';
let latestApkUrl = null;

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
  const prev = btn.textContent;
  btn.textContent = '⏳ Prüfe…';
  document.getElementById('availableVersion').textContent = 'Prüfe…';
  document.getElementById('availableVersion').className = 'version-value';
  document.getElementById('updateBtnRow').style.display = 'none';
  try {
    const autoUpdateEnabled = await checkForUpdate();
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
    toast(av.includes('✓') ? '✓ App ist aktuell' : 'Update verfügbar: ' + av);
  } finally {
    btn.disabled = false;
    btn.textContent = prev;
  }
}

async function checkForUpdate() {
  let autoUpdateEnabled = false;
  if (SmsGateway && SmsGateway.getAppVersion) {
    try {
      const version = await SmsGateway.getAppVersion();
      const versionName = version.versionName;
      autoUpdateEnabled = version.autoUpdateEnabled === true;
      document.getElementById('installedVersion').textContent = 'v' + versionName;
      const pill = document.getElementById('verPill');
      if (pill) pill.innerHTML = 'ECPG <b style="color:var(--yellow)">v' + versionName + '</b>';
      if (typeof autoUpdateVariant !== 'undefined') {
        autoUpdateVariant = autoUpdateEnabled;
        if (autoUpdateVariant) {
          document.getElementById('autoUpdateCard').style.display = '';
          if (typeof updateAutoUpdateStatus === 'function') updateAutoUpdateStatus();
        }
      }
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
    if (latestVer && installedVer !== '–' && installedVer !== 'unbekannt' && latestVer !== installedVer) {
      availableEl.textContent = latestTag;
      availableEl.className = 'version-value update-available';
      document.getElementById('updateBtnRow').style.display = asset ? '' : 'none';
      if (typeof addLog === 'function') addLog('Update verfügbar: ' + latestTag + ' (installiert: v' + installedVer + ')');
    } else {
      availableEl.textContent = latestTag + ' ✓';
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
