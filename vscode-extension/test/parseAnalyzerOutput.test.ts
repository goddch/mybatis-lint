import assert from 'node:assert/strict';
import { test } from 'node:test';

import { parseAnalyzerOutput } from '../src/parseAnalyzerOutput';

test('parses a well-formed analyzer result', () => {
  const json = JSON.stringify({
    schemaVersion: 1,
    summary: { fileCount: 1, errorCount: 1, warningCount: 0 },
    diagnostics: [
      {
        file: 'a.xml',
        uri: 'file:///a.xml',
        ruleId: 'mybatis-lint/malformed-xml',
        severity: 'error',
        message: 'boom',
        range: { start: { line: 1, character: 1 }, end: { line: 1, character: 1 } },
      },
    ],
  });

  const result = parseAnalyzerOutput(json);
  assert.equal(result.summary.errorCount, 1);
  assert.equal(result.diagnostics.length, 1);
  assert.equal(result.diagnostics[0].ruleId, 'mybatis-lint/malformed-xml');
});

test('throws a descriptive error for invalid JSON', () => {
  assert.throws(() => parseAnalyzerOutput('not json'), /not valid JSON/);
});

test('throws a descriptive error when diagnostics field is missing', () => {
  assert.throws(() => parseAnalyzerOutput('{"schemaVersion": 1}'), /expected schema/);
});
