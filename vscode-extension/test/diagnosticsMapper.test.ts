import assert from 'node:assert/strict';
import { test } from 'node:test';

import { groupDiagnosticsByUri, PlainDiagnosticSeverity } from '../src/diagnosticsMapper';
import type { AnalyzerDiagnostic } from '../src/analyzerTypes';

function diagnostic(overrides: Partial<AnalyzerDiagnostic> = {}): AnalyzerDiagnostic {
  return {
    file: 'invalid/DuplicateIdMapper.xml',
    uri: 'file:///workspace/invalid/DuplicateIdMapper.xml',
    ruleId: 'mybatis-lint/duplicate-statement-id',
    severity: 'error',
    message: "Duplicate statement id 'findById' in this mapper file.",
    range: { start: { line: 9, character: 63 }, end: { line: 9, character: 63 } },
    ...overrides,
  };
}

test('converts 1-based analyzer positions to 0-based vscode positions', () => {
  const [group] = groupDiagnosticsByUri([diagnostic()]);
  assert.equal(group.uri, 'file:///workspace/invalid/DuplicateIdMapper.xml');
  assert.equal(group.diagnostics.length, 1);
  assert.deepEqual(group.diagnostics[0].range, {
    startLine: 8,
    startCharacter: 62,
    endLine: 8,
    endCharacter: 62,
  });
});

test('maps severities to vscode-compatible numeric values', () => {
  const [group] = groupDiagnosticsByUri([diagnostic({ severity: 'warning' })]);
  assert.equal(group.diagnostics[0].severity, PlainDiagnosticSeverity.Warning);
});

test('carries the rule id through as the diagnostic code', () => {
  const [group] = groupDiagnosticsByUri([diagnostic()]);
  assert.equal(group.diagnostics[0].code, 'mybatis-lint/duplicate-statement-id');
});

test('groups diagnostics from multiple files by uri', () => {
  const grouped = groupDiagnosticsByUri([
    diagnostic({ uri: 'file:///workspace/a.xml' }),
    diagnostic({ uri: 'file:///workspace/b.xml' }),
    diagnostic({ uri: 'file:///workspace/a.xml' }),
  ]);
  assert.equal(grouped.length, 2);
  const a = grouped.find((g) => g.uri === 'file:///workspace/a.xml');
  assert.equal(a?.diagnostics.length, 2);
});

test('never produces a negative line/character', () => {
  const [group] = groupDiagnosticsByUri([
    diagnostic({ range: { start: { line: 1, character: 1 }, end: { line: 1, character: 1 } } }),
  ]);
  assert.equal(group.diagnostics[0].range.startLine, 0);
  assert.equal(group.diagnostics[0].range.startCharacter, 0);
});

test('carries a suggestion through only when the analyzer gives one', () => {
  const [withSuggestion] = groupDiagnosticsByUri([diagnostic({ suggestion: 'userName' })]);
  assert.equal(withSuggestion.diagnostics[0].suggestion, 'userName');
  const [withoutSuggestion] = groupDiagnosticsByUri([diagnostic()]);
  assert.equal('suggestion' in withoutSuggestion.diagnostics[0], false);
});
