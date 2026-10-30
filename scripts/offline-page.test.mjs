import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

function page(retry, plugin) {
  const listeners = {};
  const navigations = [];
  const location = { hash: '#retry=' + encodeURIComponent(retry),
    replace: url => navigations.push(url) };
  const html = readFileSync(new URL('../www/offline.html', import.meta.url), 'utf8');
  vm.runInNewContext(html.match(/<script>([\s\S]*?)<\/script>/)[1], {
    URL, URLSearchParams, location,
    window: { Capacitor: { Plugins: { DeviceKeepalive: plugin } } },
    document: { getElementById: id => ({ addEventListener: (_, callback) => { listeners[id] = callback; } }) },
  });
  return { listeners, navigations, location, html };
}

test('Retry laedt die letzte URL einschliesslich Query erneut', () => {
  const url = 'https://einsatzcockpit.com/objekte/12?tab=gefahren';
  const p = page(url);
  p.listeners.retry();
  assert.deepEqual(p.navigations, [url]);
  assert.match(p.html, /href="https:\/\/einsatzcockpit.com\/objekte\/"/);
});

test('Retry ignoriert fremde oder aktive URLs', () => {
  for (const url of ['https://example.com/', 'javascript:alert(1)', 'https://einsatzcockpit.com.example.com/']) {
    const p = page(url);
    p.listeners.retry();
    assert.deepEqual(p.navigations, ['https://einsatzcockpit.com/']);
  }
});

test('Kontakte bevorzugen das native Plugin; ohne Plugin bleibt Direktlink aktiv', async () => {
  let opened = 0;
  let prevented = 0;
  const event = { preventDefault: () => { prevented++; } };
  const p = page('', { openOfflineKontakte: async () => { opened++; } });
  await p.listeners.kontakte(event);
  assert.equal(opened, 1);
  assert.equal(prevented, 1);
  await page('').listeners.kontakte(event);
  assert.equal(prevented, 1);
  const failed = page('', { openOfflineKontakte: async () => { throw new Error('unavailable'); } });
  await failed.listeners.kontakte(event);
  assert.equal(failed.location.href, 'https://einsatzcockpit.com/kontakte');
});
