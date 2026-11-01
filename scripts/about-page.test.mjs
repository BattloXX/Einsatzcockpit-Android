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
