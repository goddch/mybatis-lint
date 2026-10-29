import assert from 'node:assert/strict';
import { test } from 'node:test';

import { SuggestionStore } from '../src/quickFixes';

test('finds a suggestion by file, rule and message', () => {
  const store = new SuggestionStore();
  store.set('file:///a.xml', 'mybatis-lint/unknown-select-alias', "alias 'userNmae'", 'userName');

  assert.equal(store.find('file:///a.xml', 'mybatis-lint/unknown-select-alias', "alias 'userNmae'", 'userNmae'), 'userName');
  assert.equal(store.find('file:///b.xml', 'mybatis-lint/unknown-select-alias', "alias 'userNmae'", 'userNmae'), undefined);
  assert.equal(store.find('file:///a.xml', 'mybatis-lint/undefined-parameter', "alias 'userNmae'", 'userNmae'), undefined);
});

test('offers nothing once the text already reads as the suggestion, or after clear', () => {
  const store = new SuggestionStore();
  store.set('file:///a.xml', 'rule', 'message', 'userName');

  assert.equal(store.find('file:///a.xml', 'rule', 'message', 'userName'), undefined);
  store.clear();
  assert.equal(store.find('file:///a.xml', 'rule', 'message', 'userNmae'), undefined);
});
