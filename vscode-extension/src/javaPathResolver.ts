import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

/** Directory (relative to the extension root) that holds the bundled jlink runtime. */
export const BUNDLED_JRE_DIR = 'jre';

/** Returns the path to `java` inside the runtime bundled with the extension. */
export function bundledJavaPath(extensionPath: string, platform: NodeJS.Platform = process.platform): string {
  return path.join(extensionPath, BUNDLED_JRE_DIR, 'bin', javaExecutableName(platform));
}

/**
 * Resolves the Java executable path to use for launching the analyzer.
 *
 * If the user configured `mybatisLint.javaPath`, that value wins. Otherwise
 * the runtime bundled with the extension is preferred, so users do not need
 * any particular Java version installed. The remaining candidates only matter
 * when running from a development checkout without a bundled runtime; they
 * cover locations VS Code may not see when it does not inherit the user's
 * interactive PATH.
 */
export function resolveJavaPath(
  configuredPath: string | undefined,
  extensionPath: string,
  existsFn: (candidatePath: string) => boolean = fs.existsSync,
  env: NodeJS.ProcessEnv = process.env,
  homedir: string = os.homedir(),
  platform: NodeJS.Platform = process.platform,
): string {
  if (configuredPath && configuredPath.trim().length > 0) {
    return configuredPath;
  }

  const executable = javaExecutableName(platform);
  const candidates = [
    bundledJavaPath(extensionPath, platform),
    env.JAVA_HOME ? path.join(env.JAVA_HOME, 'bin', executable) : undefined,
    path.join(homedir, '.sdkman', 'candidates', 'java', 'current', 'bin', executable),
  ];

  for (const candidatePath of candidates) {
    if (candidatePath && existsFn(candidatePath)) {
      return candidatePath;
    }
  }

  return 'java';
}

function javaExecutableName(platform: NodeJS.Platform): string {
  return platform === 'win32' ? 'java.exe' : 'java';
}
