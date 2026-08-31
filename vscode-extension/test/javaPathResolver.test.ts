import assert from 'node:assert/strict';
import path from 'node:path';
import { test } from 'node:test';

import { resolveJavaPath } from '../src/javaPathResolver';

test('uses the configured java path when set', () => {
  const resolved = resolveJavaPath('/custom/bin/java');
  assert.equal(resolved, '/custom/bin/java');
});

test('uses JAVA_HOME when set and no explicit setting is provided', () => {
  const javaHome = '/jdk';
  const javaPath = path.join(javaHome, 'bin', 'java');
  const resolved = resolveJavaPath(
    undefined,
    (candidatePath) => candidatePath === javaPath,
    { JAVA_HOME: javaHome },
    '/home/tester',
  );
  assert.equal(resolved, javaPath);
});

test('uses SDKMAN current java when JAVA_HOME is unavailable', () => {
  const sdkmanJavaPath = path.join('/home/tester', '.sdkman', 'candidates', 'java', 'current', 'bin', 'java');
  const resolved = resolveJavaPath(
    undefined,
    (candidatePath) => candidatePath === sdkmanJavaPath,
    {},
    '/home/tester',
  );
  assert.equal(resolved, sdkmanJavaPath);
});

test('falls back to plain java when no configured or detected path exists', () => {
  const resolved = resolveJavaPath(undefined, () => false, {}, '/home/tester');
  assert.equal(resolved, 'java');
});
