import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { test } from 'node:test';

import { buildAnalyzerArgs, runAnalyzer } from '../src/analyzerRunner';

test('passes each non-blank exclude pattern before the workspace path', () => {
  assert.deepEqual(buildAnalyzerArgs('/path/analyzer.jar', '/workspace', ['docker', ' ', ' modules/*/generated ']), [
    '-jar',
    '/path/analyzer.jar',
    '--exclude',
    'docker',
    '--exclude',
    'modules/*/generated',
    '/workspace',
  ]);
});

test('passes disabled rules after excludes', () => {
  assert.deepEqual(buildAnalyzerArgs('/a.jar', '/w', ['docker'], ['unknown-select-alias', ' ']), [
    '-jar',
    '/a.jar',
    '--exclude',
    'docker',
    '--disable',
    'unknown-select-alias',
    '/w',
  ]);
});

test('builds the expected java CLI arguments', () => {
  assert.deepEqual(buildAnalyzerArgs('/path/analyzer.jar', '/workspace'), [
    '-jar',
    '/path/analyzer.jar',
    '/workspace',
  ]);
});

function fakeChildProcess() {
  const child = new EventEmitter() as EventEmitter & { stdout: EventEmitter; stderr: EventEmitter };
  child.stdout = new EventEmitter();
  child.stderr = new EventEmitter();
  return child;
}

test('resolves with stdout and exit code even when the exit code is non-zero', async () => {
  const child = fakeChildProcess();
  const spawnFn = (() => child) as unknown as typeof import('child_process').spawn;

  const resultPromise = runAnalyzer(
    { javaPath: 'java', jarPath: 'analyzer.jar', workspacePath: '/workspace' },
    spawnFn,
  );

  child.stdout.emit('data', Buffer.from('{"diagnostics":[]}'));
  child.emit('close', 1);

  const result = await resultPromise;
  assert.equal(result.exitCode, 1);
  assert.equal(result.stdout, '{"diagnostics":[]}');
});

test('passes the analyzer stderr through for logging', async () => {
  const child = fakeChildProcess();
  const spawnFn = (() => child) as unknown as typeof import('child_process').spawn;

  const resultPromise = runAnalyzer(
    { javaPath: 'java', jarPath: 'analyzer.jar', workspacePath: '/workspace' },
    spawnFn,
  );

  child.stderr.emit('data', Buffer.from('Skipped unreadable path: docker/volume\n'));
  child.stdout.emit('data', Buffer.from('{"diagnostics":[]}'));
  child.emit('close', 0);

  const result = await resultPromise;
  assert.equal(result.stderr, 'Skipped unreadable path: docker/volume\n');
});

test('rejects with a descriptive error when the process fails to launch', async () => {
  const child = fakeChildProcess();
  const spawnFn = (() => child) as unknown as typeof import('child_process').spawn;

  const resultPromise = runAnalyzer(
    { javaPath: 'no-such-java', jarPath: 'analyzer.jar', workspacePath: '/workspace' },
    spawnFn,
  );

  const error: NodeJS.ErrnoException = Object.assign(new Error('spawn no-such-java ENOENT'), {
    code: 'ENOENT',
  });
  child.emit('error', error);

  await assert.rejects(resultPromise, /failed to launch 'no-such-java'/);
});

test('rejects when the analyzer produces no stdout', async () => {
  const child = fakeChildProcess();
  const spawnFn = (() => child) as unknown as typeof import('child_process').spawn;

  const resultPromise = runAnalyzer(
    { javaPath: 'java', jarPath: 'analyzer.jar', workspacePath: '/workspace' },
    spawnFn,
  );

  child.emit('close', 1);

  await assert.rejects(resultPromise, /produced no output/);
});
