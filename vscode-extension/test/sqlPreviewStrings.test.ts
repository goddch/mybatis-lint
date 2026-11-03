import assert from 'node:assert/strict';
import { test } from 'node:test';

import { sqlPreviewHtml } from '../src/sqlPreviewHtml';
import { previewStrings } from '../src/sqlPreviewStrings';

test('gives the words of the chosen language', () => {
  assert.equal(previewStrings('ja').lang, 'ja');
  assert.equal(previewStrings('en').lang, 'en');
  assert.equal(previewStrings('ja').copy, 'SQL をコピー');
});

test('gives every word in both languages', () => {
  const english = previewStrings('en');
  const japanese = previewStrings('ja');
  assert.deepEqual(Object.keys(japanese).sort(), Object.keys(english).sort());
  for (const [key, value] of Object.entries(japanese)) {
    assert.ok(String(value).length > 0, key);
  }
});

test('embeds the words without letting them close the script', () => {
  const html = sqlPreviewHtml('n', 'x', previewStrings('ja'));
  const script = html.slice(html.indexOf('<script nonce="n">'), html.lastIndexOf('</script>'));
  // The empty message mentions <select>, which must not appear as a raw tag inside the script.
  assert.ok(!script.slice(1).includes('<select>'), 'no raw < from the words');
  assert.ok(script.includes('\\u003cselect>'));
  assert.ok(html.includes('<html lang="ja">'));
});
