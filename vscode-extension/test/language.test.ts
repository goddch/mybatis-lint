import assert from 'node:assert/strict';
import { test } from 'node:test';

import { format, resolveLanguage, uiStrings } from '../src/language';

test('follows the VS Code display language unless a setting chooses one', () => {
  assert.equal(resolveLanguage('auto', 'ja'), 'ja');
  assert.equal(resolveLanguage(undefined, 'en-us'), 'en');
  assert.equal(resolveLanguage('en', 'ja'), 'en');
  assert.equal(resolveLanguage('ja', 'en'), 'ja');
});

test('still honors the older previewLanguage setting while language is auto', () => {
  assert.equal(resolveLanguage('auto', 'en', 'ja'), 'ja');
  assert.equal(resolveLanguage('en', 'en', 'ja'), 'en');
  assert.equal(resolveLanguage('auto', 'ja', 'auto'), 'ja');
});

test('gives every word in both languages', () => {
  assert.deepEqual(Object.keys(uiStrings('ja')).sort(), Object.keys(uiStrings('en')).sort());
  assert.equal(format(uiStrings('ja').changeTo, 'userName'), "'userName' に変更");
  assert.equal(format(uiStrings('en').references, 3), '3 references');
});
