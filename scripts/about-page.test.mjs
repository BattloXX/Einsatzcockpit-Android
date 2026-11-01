import { readFileSync } from 'node:fs';
import test from 'node:test';
import assert from 'node:assert/strict';

const html = readFileSync(new URL('../www/about.html', import.meta.url), 'utf8');
const markup = html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/g, '').replace(/<!--[\s\S]*?-->/g, '');
const ids = [...markup.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);

test('Markup-IDs sind eindeutig und alle referenzierten IDs existieren', () => {
  const references = [...html.matchAll(/getElementById\(\s*['"]([^'"]+)['"]\s*\)/g)].map(match => match[1]);
  // Inline handlers may also reference IDs through their arguments.
  for (const handler of markup.matchAll(/\bon(?:click|change)="([^"]*)"/g)) {
    for (const argument of handler[1].matchAll(/'([^']+)'/g)) {
      if (ids.includes(argument[1])) references.push(argument[1]);
    }
  }
  // IDs passed through the two existing dynamic getElementById call sites.
  for (const call of html.matchAll(/setAlarmCheck\('([^']+)'/g)) references.push(call[1]);
  for (const list of html.matchAll(/\[([^\]]+)\]\.forEach\(\(id\)/g)) {
    for (const id of list[1].matchAll(/'([^']+)'/g)) references.push(id[1]);
  }
  for (const id of new Set([...ids, ...references])) {
    assert.equal(ids.filter(value => value === id).length, 1, id);
  }
});

test('Alle Icon-Referenzen haben ein inline Symbol', () => {
  const symbols = new Set([...markup.matchAll(/<symbol\b[^>]*\bid="([^"]+)"/g)].map(match => match[1]));
  const uses = [...markup.matchAll(/<use\b[^>]*\bhref="#(i-[^"]+)"/g)];
  assert.ok(uses.length > 0);
  for (const use of uses) assert.ok(symbols.has(use[1]), use[1]);
});

test('Keine Emoji oder bisherigen Unicode-Icons in der Einstellungsseite', () => {
  assert.doesNotMatch(html, /[\p{Extended_Pictographic}\uFE0F\u23F3↻▶⬇←↓✓]/u);
});

test('Gerätebericht verwendet native Bridge und zeigt erst bestätigte Versandzeit', () => {
  assert.match(html, /SmsGateway\.getDeviceInfo\(\)/);
  assert.match(html, /await SmsGateway\.sendDiagnoseReport\(\)/);
  assert.match(html, /info\.last_sent_at/);
  assert.match(markup, /id="btnSendDiagnose"[^>]*onclick="sendDiagnoseReport\(\)"/);
  assert.match(html, /button\.disabled = false/);
  assert.match(html, /row\.textContent =/);
  assert.match(html, /app\.version_code/);
  assert.match(html, /app\.build_variant/);
  assert.match(markup, /Einstellungen werden nur in der App geändert/);
  assert.match(markup, /Fehler werden dorthin gemeldet/);
});

test('Manueller Versand bestätigt Vormerkung und stellt Button auch bei Fehler wieder her', async () => {
  const { runInNewContext } = await import('node:vm');
  const source = html.slice(html.indexOf('async function updateDeviceInfo()'), html.indexOf('// ── Capacitor-Bridge'));
  const elements = new Map(['btnSendDiagnose', 'diagnoseSendStatus', 'deviceVersion', 'deviceProperties', 'diagnoseLastSent']
    .map(id => [id, { textContent:'', disabled:false, rows:[], replaceChildren() { this.rows = []; }, appendChild(row) { this.rows.push(row); } }]));
  let calls = 0;
  const bridge = {
    async sendDiagnoseReport() { calls++; },
    async getDeviceInfo() { return { app:{ version_name:'1.2', version_code:42, build_variant:'standard' }, geraet:{ modell:'<unsafe>' }, last_sent_at:null }; }
  };
  const context = { SmsGateway:bridge, document:{ getElementById:id => elements.get(id), createElement:() => ({ textContent:'' }) } };
  runInNewContext(source, context);
  await context.sendDiagnoseReport();
  assert.equal(calls, 1);
  assert.equal(elements.get('btnSendDiagnose').disabled, false);
  assert.match(elements.get('diagnoseSendStatus').textContent, /vorgemerkt/);
  assert.match(elements.get('diagnoseLastSent').textContent, /noch nie/);
  assert.equal(elements.get('deviceVersion').textContent, '1.2 (42) · standard');
  assert.equal(elements.get('deviceProperties').rows[0].textContent, 'Modell: <unsafe>');
  bridge.sendDiagnoseReport = async () => { throw new Error('unavailable'); };
  await context.sendDiagnoseReport();
  assert.equal(elements.get('btnSendDiagnose').disabled, false);
  assert.match(elements.get('diagnoseSendStatus').textContent, /erneut versuchen/);
});
