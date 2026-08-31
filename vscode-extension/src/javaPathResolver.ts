import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

/**
 * Resolves the Java executable path to use for launching the analyzer.
 *
 * If the user configured `mybatisLint.javaPath`, that value wins. Otherwise,
 * prefer common non-shell-discovered locations so the extension still works
 * when VS Code does not inherit the user's interactive PATH.
 */
export function resolveJavaPath(
  configuredPath: string | undefined,
  existsFn: (candidatePath: string) => boolean = fs.existsSync,
  env: NodeJS.ProcessEnv = process.env,
  homedir: string = os.homedir(),
): string {
  if (configuredPath && configuredPath.trim().length > 0) {
    return configuredPath;
  }

  const candidates = [
    env.JAVA_HOME ? path.join(env.JAVA_HOME, 'bin', 'java') : undefined,
    path.join(homedir, '.sdkman', 'candidates', 'java', 'current', 'bin', 'java'),
  ];

  for (const candidatePath of candidates) {
    if (candidatePath && existsFn(candidatePath)) {
      return candidatePath;
    }
  }

  return 'java';
}
