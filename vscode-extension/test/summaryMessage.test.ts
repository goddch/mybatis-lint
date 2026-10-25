import assert from 'node:assert/strict';
import { test } from 'node:test';

import { buildSummaryMessage } from '../src/summaryMessage';

test('reports how many mapper files were checked', () => {
  const message = buildSummaryMessage({ mapperFileCount: 12, fileCount: 2, errorCount: 1, warningCount: 3 });
  assert.equal(message.level, 'info');
  assert.equal(message.text, 'MyBatis Lint: checked 12 mapper file(s), found 1 error(s) and 3 warning(s).');
});

test('reports a clean workspace with mapper files as info', () => {
  const message = buildSummaryMessage({ mapperFileCount: 5, fileCount: 0, errorCount: 0, warningCount: 0 });
  assert.equal(message.level, 'info');
  assert.equal(message.text, 'MyBatis Lint: checked 5 mapper file(s), found 0 error(s) and 0 warning(s).');
});

test('warns when no mapper files were found', () => {
  const message = buildSummaryMessage({ mapperFileCount: 0, fileCount: 0, errorCount: 0, warningCount: 0 });
  assert.equal(message.level, 'warning');
  assert.match(message.text, /no MyBatis mapper XML files/);
  assert.doesNotMatch(message.text, /Other XML files/);
});

test('mentions diagnostics from non-mapper XML files when no mapper files were found', () => {
  const message = buildSummaryMessage({ mapperFileCount: 0, fileCount: 1, errorCount: 1, warningCount: 0 });
  assert.equal(message.level, 'warning');
  assert.match(message.text, /Other XML files: found 1 error\(s\) and 0 warning\(s\)\./);
});

test('falls back to the old message when the analyzer does not report mapperFileCount', () => {
  const message = buildSummaryMessage({ fileCount: 2, errorCount: 1, warningCount: 1 });
  assert.equal(message.level, 'info');
  assert.equal(message.text, 'MyBatis Lint: checked workspace, found 1 error(s) and 1 warning(s) across 2 file(s).');
});
