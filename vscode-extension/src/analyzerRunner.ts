import { spawn as defaultSpawn } from 'child_process';

export interface AnalyzerRunOptions {
  javaPath: string;
  jarPath: string;
  workspacePath: string;
  /** Globs relative to the workspace that the analyzer should skip. */
  excludes?: string[];
  /** Rule IDs (with or without the `mybatis-lint/` prefix) whose diagnostics are not reported. */
  disabledRules?: string[];
}

export interface AnalyzerRunResult {
  stdout: string;
  /** Notes from the analyzer, such as folders it skipped because they could not be read. */
  stderr: string;
  exitCode: number | null;
}

/** Builds the `java` CLI arguments used to invoke the analyzer jar. */
export function buildAnalyzerArgs(
  jarPath: string,
  workspacePath: string,
  excludes: string[] = [],
  disabledRules: string[] = [],
): string[] {
  return [
    '-jar',
    jarPath,
    ...repeatedOption('--exclude', excludes),
    ...repeatedOption('--disable', disabledRules),
    workspacePath,
  ];
}

function repeatedOption(option: string, values: string[]): string[] {
  return values
    .map((value) => value.trim())
    .filter((value) => value.length > 0)
    .flatMap((value) => [option, value]);
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
  const { javaPath, jarPath, workspacePath, excludes, disabledRules } = options;
  const args = buildAnalyzerArgs(jarPath, workspacePath, excludes, disabledRules);

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
      resolve({ stdout, stderr, exitCode });
    });
  });
}
