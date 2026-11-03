import { spawn as defaultSpawn } from 'child_process';
import type { ChildProcess } from 'child_process';

import { buildRequestArgs } from './analyzerRunner';
import type { AnalyzerRunOptions } from './analyzerRunner';

export interface AnalyzerServerResult {
  stdout: string;
  /** What the analyzer wrote to stderr while answering, such as folders it skipped. */
  stderr: string;
}

type SpawnFn = typeof defaultSpawn;

const HEADER = /^Content-Length: (\d+)$/;

/**
 * Keeps one `java -jar analyzer.jar --server` process running and sends it a
 * request per check, so later checks skip JVM start-up and reuse what the
 * analyzer already parsed (it re-reads only changed files).
 *
 * A request is the CLI arguments joined with tabs on one line; the answer is
 * a `Content-Length: <bytes>` line followed by the same JSON the CLI prints.
 * Any failure (the process cannot start, exits, or answers with length 0)
 * rejects, so the caller can fall back to a one-shot run.
 */
export class AnalyzerServer {
  private child: ChildProcess | undefined;
  private processKey: string | undefined;
  private buffer = Buffer.alloc(0);
  private stderr = '';
  private pending: { resolve: (result: AnalyzerServerResult) => void; reject: (error: Error) => void } | undefined;
  private queue: Promise<unknown> = Promise.resolve();

  constructor(private readonly spawnFn: SpawnFn = defaultSpawn) {}

  /**
   * Analyzes a workspace. Requests run one at a time, in order.
   *
   * @param processKey identifies the Java runtime and jar; the process is restarted when it changes
   */
  request(options: AnalyzerRunOptions, processKey: string): Promise<AnalyzerServerResult> {
    const result = this.queue.then(() => this.send(options, processKey));
    this.queue = result.catch(() => undefined);
    return result;
  }

  dispose(): void {
    this.stop(new Error('MyBatis Lint: analyzer server stopped.'));
  }

  private send(options: AnalyzerRunOptions, processKey: string): Promise<AnalyzerServerResult> {
    const args = buildRequestArgs(
      options.workspacePath,
      options.excludes,
      options.disabledRules,
      options.sqlDialect,
      options.language,
    );
    if (args.some((arg) => /[\t\r\n]/.test(arg))) {
      return Promise.reject(new Error('MyBatis Lint: a path or setting contains a tab or line break.'));
    }
    if (this.child === undefined || this.processKey !== processKey) {
      this.stop(new Error('MyBatis Lint: analyzer server restarted.'));
      this.start(options, processKey);
    }
    const child = this.child;
    return new Promise((resolve, reject) => {
      this.pending = { resolve, reject };
      child?.stdin?.write(`${args.join('\t')}\n`, 'utf8');
    });
  }

  private start(options: AnalyzerRunOptions, processKey: string): void {
    // No -XX:TieredStopAtLevel=1 here: a long-running process benefits from the optimizing JIT.
    const child = this.spawnFn(options.javaPath, ['-jar', options.jarPath, '--server']);
    this.child = child;
    this.processKey = processKey;
    this.buffer = Buffer.alloc(0);
    this.stderr = '';
    child.stdout?.on('data', (chunk: Buffer) => this.onStdout(chunk));
    child.stderr?.on('data', (chunk: Buffer) => {
      this.stderr += chunk.toString('utf8');
    });
    child.on('error', (error: Error) => this.onExit(child, `could not start (${error.message})`));
    child.on('exit', (code: number | null) => this.onExit(child, `exited (code ${code})`));
    child.stdin?.on('error', () => this.onExit(child, 'stopped reading requests'));
  }

  private onStdout(chunk: Buffer): void {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    for (;;) {
      const newline = this.buffer.indexOf('\n');
      if (newline < 0) {
        return;
      }
      const header = HEADER.exec(this.buffer.subarray(0, newline).toString('ascii').trim());
      if (!header) {
        this.stop(new Error('MyBatis Lint: unexpected output from the analyzer server.'));
        return;
      }
      const length = Number(header[1]);
      if (this.buffer.length < newline + 1 + length) {
        return;
      }
      const body = this.buffer.subarray(newline + 1, newline + 1 + length).toString('utf8');
      this.buffer = this.buffer.subarray(newline + 1 + length);
      const stderr = this.stderr;
      this.stderr = '';
      const pending = this.pending;
      this.pending = undefined;
      if (length === 0) {
        pending?.reject(new Error(`MyBatis Lint: the analyzer server could not analyze the workspace. ${stderr.trim()}`));
      } else {
        pending?.resolve({ stdout: body, stderr });
      }
    }
  }

  private onExit(child: ChildProcess, reason: string): void {
    if (child !== this.child) {
      return;
    }
    const stderr = this.stderr.trim();
    this.child = undefined;
    this.processKey = undefined;
    const pending = this.pending;
    this.pending = undefined;
    pending?.reject(new Error(`MyBatis Lint: the analyzer server ${reason}.${stderr ? ` ${stderr}` : ''}`));
  }

  private stop(error: Error): void {
    const child = this.child;
    this.child = undefined;
    this.processKey = undefined;
    const pending = this.pending;
    this.pending = undefined;
    pending?.reject(error);
    if (child) {
      // Closing stdin asks the server to exit; kill in case it is busy.
      child.stdin?.end();
      child.kill();
    }
  }
}
