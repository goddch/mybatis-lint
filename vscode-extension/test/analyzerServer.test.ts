import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { test } from 'node:test';

import { AnalyzerServer } from '../src/analyzerServer';

/** A fake `java --server` process that records the request lines written to it. */
function fakeServerProcess() {
  const child = new EventEmitter() as EventEmitter & {
    stdout: EventEmitter;
    stderr: EventEmitter;
    stdin: EventEmitter & { write: (line: string) => void; end: () => void };
    kill: () => void;
    requests: string[];
    killed: boolean;
  };
  child.stdout = new EventEmitter();
  child.stderr = new EventEmitter();
  child.requests = [];
  child.killed = false;
  child.stdin = Object.assign(new EventEmitter(), {
    write: (line: string) => {
      child.requests.push(line);
    },
    end: () => undefined,
  });
  child.kill = () => {
    child.killed = true;
  };
  return child;
}

function answer(child: ReturnType<typeof fakeServerProcess>, body: string, split = false): void {
  const bytes = Buffer.from(body, 'utf8');
  const message = Buffer.concat([Buffer.from(`Content-Length: ${bytes.length}\n`), bytes]);
  if (split) {
    // Output can arrive in pieces, even in the middle of a multi-byte character.
    for (let i = 0; i < message.length; i += 3) {
      child.stdout.emit('data', message.subarray(i, i + 3));
    }
  } else {
    child.stdout.emit('data', message);
  }
}

const OPTIONS = { javaPath: 'java', jarPath: 'analyzer.jar', workspacePath: '/w', excludes: ['docker'], disabledRules: [] };

test('starts the server once and sends one tab-separated line per request', async () => {
  const children: Array<ReturnType<typeof fakeServerProcess>> = [];
  const spawnArgs: string[][] = [];
  const server = new AnalyzerServer(((_java: string, args: string[]) => {
    spawnArgs.push(args);
    const child = fakeServerProcess();
    children.push(child);
    return child;
  }) as unknown as typeof import('child_process').spawn);

  const first = server.request(OPTIONS, 'key');
  await new Promise((resolve) => setImmediate(resolve));
  children[0].stderr.emit('data', Buffer.from('Skipped unreadable path: docker/x\n'));
  answer(children[0], '{"diagnostics":[],"note":"金額"}', true);
  assert.deepEqual(await first, {
    stdout: '{"diagnostics":[],"note":"金額"}',
    stderr: 'Skipped unreadable path: docker/x\n',
  });

  const second = server.request(OPTIONS, 'key');
  await new Promise((resolve) => setImmediate(resolve));
  answer(children[0], '{"diagnostics":[]}');
  assert.equal((await second).stdout, '{"diagnostics":[]}');

  assert.equal(children.length, 1);
  assert.deepEqual(spawnArgs[0], ['-jar', 'analyzer.jar', '--server']);
  assert.deepEqual(children[0].requests, ['--exclude\tdocker\t/w\n', '--exclude\tdocker\t/w\n']);
});

test('rejects when the server answers with nothing or exits, and restarts on the next request', async () => {
  const children: Array<ReturnType<typeof fakeServerProcess>> = [];
  const server = new AnalyzerServer((() => {
    const child = fakeServerProcess();
    children.push(child);
    return child;
  }) as unknown as typeof import('child_process').spawn);

  const empty = server.request(OPTIONS, 'key');
  await new Promise((resolve) => setImmediate(resolve));
  children[0].stderr.emit('data', Buffer.from('Analysis failed: boom\n'));
  answer(children[0], '');
  await assert.rejects(empty, /could not analyze the workspace\. Analysis failed: boom/);

  const exited = server.request(OPTIONS, 'key');
  await new Promise((resolve) => setImmediate(resolve));
  children[0].emit('exit', 1);
  await assert.rejects(exited, /exited \(code 1\)/);

  const restarted = server.request(OPTIONS, 'key');
  await new Promise((resolve) => setImmediate(resolve));
  answer(children[1], '{"diagnostics":[]}');
  assert.equal((await restarted).stdout, '{"diagnostics":[]}');
  assert.equal(children.length, 2);
});

test('restarts when the Java runtime or jar changes, and refuses arguments it cannot frame', async () => {
  const children: Array<ReturnType<typeof fakeServerProcess>> = [];
  const server = new AnalyzerServer((() => {
    const child = fakeServerProcess();
    children.push(child);
    return child;
  }) as unknown as typeof import('child_process').spawn);

  const first = server.request(OPTIONS, 'old jar');
  await new Promise((resolve) => setImmediate(resolve));
  answer(children[0], '{}');
  await first;

  const second = server.request(OPTIONS, 'rebuilt jar');
  await new Promise((resolve) => setImmediate(resolve));
  answer(children[1], '{}');
  await second;
  assert.equal(children[0].killed, true);

  await assert.rejects(server.request({ ...OPTIONS, workspacePath: '/w\nx' }, 'rebuilt jar'), /tab or line break/);
  server.dispose();
  assert.equal(children[1].killed, true);
});
