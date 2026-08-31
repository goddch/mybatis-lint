import assert from 'node:assert/strict';
import path from 'node:path';
import { test } from 'node:test';

import { resolveAnalyzerJarPath } from '../src/jarPathResolver';

test('uses the configured jar path when set', () => {
  const resolved = resolveAnalyzerJarPath('/custom/analyzer.jar', '/ext');
  assert.equal(resolved, '/custom/analyzer.jar');
});

test('ignores a blank configured path and falls back to the bundled jar', () => {
  const resolved = resolveAnalyzerJarPath('   ', '/ext', () => false);
  assert.equal(resolved, path.join('/ext', 'analyzer.jar'));
});

test('uses a bundled jar next to the extension when present', () => {
  const bundledJarPath = path.join('/ext', 'analyzer.jar');
  const resolved = resolveAnalyzerJarPath(undefined, '/ext', (candidatePath) => candidatePath === bundledJarPath);
  assert.equal(resolved, path.join('/ext', 'analyzer.jar'));
});

test('uses the sibling analyzer build output during repository development when bundled jar is absent', () => {
  const bundledJarPath = path.join('/ext', 'analyzer.jar');
  const developmentJarPath = path.resolve('/ext', '..', 'analyzer', 'build', 'libs', 'analyzer.jar');
  const resolved = resolveAnalyzerJarPath(
    undefined,
    '/ext',
    (candidatePath) => candidatePath !== bundledJarPath && candidatePath === developmentJarPath,
  );
  assert.equal(resolved, developmentJarPath);
});
