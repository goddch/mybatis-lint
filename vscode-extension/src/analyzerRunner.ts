import { spawn as defaultSpawn } from 'child_process';

export interface AnalyzerRunOptions {
  javaPath: string;
  jarPath: string;
  workspacePath: string;
}

export interface AnalyzerRunResult {
  stdout: string;
  exitCode: number | null;
}

/** Builds the `java` CLI arguments used to invoke the analyzer jar. */
export function buildAnalyzerArgs(jarPath: string, workspacePath: string): string[] {
  return ['-jar', jarPath, workspacePath];
}

type SpawnFn = typeof defaultSpawn;

/**
 * Runs the analyzer CLI and collects its stdout.
 *
 * The analyzer intentionally exits with a non-zero status code when it
 * finds error-severity diagnostics, so a non-zero exit code alone is *not*
 * treated as a failure here: the JSON on stdout is still parsed by the
 * caller. Only a failure to launch the process (e.g. `java` not found) or
 * empty/missing stdout is treated as an error.
 */
export function runAnalyzer(
  options: AnalyzerRunOptions,
  spawnFn: SpawnFn = defaultSpawn,
): Promise<AnalyzerRunResult> {
  const { javaPath, jarPath, workspacePath } = options;
  const args = buildAnalyzerArgs(jarPath, workspacePath);

  return new Promise((resolve, reject) => {
    const child = spawnFn(javaPath, args);
    let stdout = '';
    let stderr = '';

    child.stdout?.on('data', (chunk: Buffer) => {
      stdout += chunk.toString('utf8');
    });
    child.stderr?.on('data', (chunk: Buffer) => {
      stderr += chunk.toString('utf8');
    });

    child.on('error', (error: NodeJS.ErrnoException) => {
      reject(
        new Error(
          `MyBatis Lint: failed to launch '${javaPath}' (${error.message}). ` +
            'Check the "mybatisLint.javaPath" and "mybatisLint.analyzerJarPath" settings.',
        ),
      );
    });

    child.on('close', (exitCode) => {
      if (stdout.trim().length === 0) {
        reject(
          new Error(
            `MyBatis Lint: analyzer produced no output (exit code ${exitCode}).` +
              (stderr.trim().length > 0 ? ` stderr: ${stderr.trim()}` : ''),
          ),
        );
        return;
      }
      resolve({ stdout, exitCode });
    });
  });
}
