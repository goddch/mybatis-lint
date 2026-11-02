import assert from 'node:assert/strict';
import { test } from 'node:test';

import { evaluateTest, valueFromSql } from '../src/ognlLite';
import type { OgnlValue } from '../src/ognlLite';

function scope(values: Record<string, OgnlValue>) {
  return (path: string) => (path in values ? values[path] : undefined);
}

test('reads values typed as SQL', () => {
  assert.equal(valueFromSql("'it''s'"), "it's");
  assert.equal(valueFromSql('123'), 123);
  assert.equal(valueFromSql('NULL'), null);
  assert.equal(valueFromSql('true'), true);
  assert.equal(valueFromSql(''), undefined);
  assert.equal(valueFromSql('SYSDATE'), undefined);
});

test('evaluates the usual null and empty checks', () => {
  const values = scope({ name: 'yama', empty: '', missing: null, status: 0, ids: { list: 2 }, none: { list: 0 } });
  assert.equal(evaluateTest('name != null', values), true);
  assert.equal(evaluateTest("name != null and name != ''", values), true);
  assert.equal(evaluateTest("empty != null and empty != ''", values), false);
  assert.equal(evaluateTest('missing != null', values), false);
  assert.equal(evaluateTest('ids != null and ids.size() > 0', values), true);
  assert.equal(evaluateTest('none.size() > 0 || none.isEmpty()', values), true);
  assert.equal(evaluateTest('name.length() gt 3', values), true);
  assert.equal(evaluateTest('!(missing != null)', values), true);
  assert.equal(evaluateTest('not missing', values), true);
});

test('reproduces the OGNL pitfalls MyBatis users run into', () => {
  const values = scope({ type: 'A', status: 0, amount: 10 });
  // 'A' is a char and never equals the String "A"; "A" in double quotes is a String.
  assert.equal(evaluateTest("type == 'A'", values), false);
  assert.equal(evaluateTest('type == "A"', values), true);
  assert.equal(evaluateTest("type == 'AB'", values), false);
  // '' compared with a number counts as 0.
  assert.equal(evaluateTest("status != null and status != ''", values), false);
  assert.equal(evaluateTest("amount != ''", values), true);
});

test('leaves what it cannot decide to the user', () => {
  const values = scope({ name: 'yama', count: 1 });
  assert.equal(evaluateTest('unknown != null', values), undefined);
  assert.equal(evaluateTest('name != null and unknown != null', values), undefined);
  // ...but a side that decides on its own is enough.
  assert.equal(evaluateTest('name == null and unknown != null', values), false);
  assert.equal(evaluateTest('name != null or unknown != null', values), true);
  assert.equal(evaluateTest('name.matches("y.*")', values), undefined);
  assert.equal(evaluateTest("count == 'abc'", values), undefined);
  assert.equal(evaluateTest('name !=', values), undefined);
});
