import assert from 'node:assert/strict';
import path from 'node:path';
import { test } from 'node:test';

import { resolveAnalyzerJarPath } from '../src/jarPathResolver';

test('uses the configured jar path when set', () => {
  const resolved = resolveAnalyzerJarPath('/custom/analyzer.jar', '/ext');
  assert.equal(resolved, '/custom/analyzer.jar');
});

test('ignores a blank configured path and falls back to the bundled jar', () => {
  const resolved = resolveAnalyzerJarPath('   ', '/ext');
  assert.equal(resolved, path.join('/ext', 'analyzer.jar'));
});

test('falls back to a bundled jar next to the extension when unset', () => {
  const resolved = resolveAnalyzerJarPath(undefined, '/ext');
  assert.equal(resolved, path.join('/ext', 'analyzer.jar'));
});
