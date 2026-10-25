import assert from 'node:assert/strict';
import path from 'node:path';
import { test } from 'node:test';

import { bundledJavaPath, resolveJavaPath } from '../src/javaPathResolver';

const extensionPath = '/ext';

test('uses the configured java path when set', () => {
  const resolved = resolveJavaPath('/custom/bin/java', extensionPath, () => true);
  assert.equal(resolved, '/custom/bin/java');
});

test('prefers the bundled runtime over JAVA_HOME when no explicit setting is provided', () => {
  const javaHome = '/jdk';
  const resolved = resolveJavaPath(undefined, extensionPath, () => true, { JAVA_HOME: javaHome }, '/home/tester', 'linux');
  assert.equal(resolved, path.join(extensionPath, 'jre', 'bin', 'java'));
});

test('uses java.exe for the bundled runtime on Windows', () => {
  assert.equal(bundledJavaPath(extensionPath, 'win32'), path.join(extensionPath, 'jre', 'bin', 'java.exe'));
});

test('uses JAVA_HOME when no bundled runtime or explicit setting exists', () => {
  const javaHome = '/jdk';
  const javaPath = path.join(javaHome, 'bin', 'java');
  const resolved = resolveJavaPath(
    undefined,
    extensionPath,
    (candidatePath) => candidatePath === javaPath,
    { JAVA_HOME: javaHome },
    '/home/tester',
    'linux',
  );
  assert.equal(resolved, javaPath);
});

test('uses java.exe under JAVA_HOME on Windows', () => {
  const javaHome = '/jdk';
  const javaPath = path.join(javaHome, 'bin', 'java.exe');
  const resolved = resolveJavaPath(
    undefined,
    extensionPath,
    (candidatePath) => candidatePath === javaPath,
    { JAVA_HOME: javaHome },
    '/home/tester',
    'win32',
  );
  assert.equal(resolved, javaPath);
});

test('uses SDKMAN current java when JAVA_HOME is unavailable', () => {
  const sdkmanJavaPath = path.join('/home/tester', '.sdkman', 'candidates', 'java', 'current', 'bin', 'java');
  const resolved = resolveJavaPath(
    undefined,
    extensionPath,
    (candidatePath) => candidatePath === sdkmanJavaPath,
    {},
    '/home/tester',
    'linux',
  );
  assert.equal(resolved, sdkmanJavaPath);
});

test('falls back to plain java when no configured or detected path exists', () => {
  const resolved = resolveJavaPath(undefined, extensionPath, () => false, {}, '/home/tester', 'linux');
  assert.equal(resolved, 'java');
});
