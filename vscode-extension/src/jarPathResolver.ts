import * as fs from 'fs';
import * as path from 'path';

/**
 * Resolves the analyzer jar path to use.
 *
 * If the user configured `mybatisLint.analyzerJarPath`, that value wins.
 * Otherwise, prefer an existing bundled jar and then a locally built analyzer
 * jar in the sibling `analyzer/` project when running from this repository.
 */
export function resolveAnalyzerJarPath(
  configuredPath: string | undefined,
  extensionPath: string,
  existsFn: (candidatePath: string) => boolean = fs.existsSync,
): string {
  if (configuredPath && configuredPath.trim().length > 0) {
    return configuredPath;
  }

  const bundledJarPath = path.join(extensionPath, 'analyzer.jar');
  const developmentJarPath = path.resolve(extensionPath, '..', 'analyzer', 'build', 'libs', 'analyzer.jar');

  for (const candidatePath of [bundledJarPath, developmentJarPath]) {
    if (existsFn(candidatePath)) {
      return candidatePath;
    }
  }

  return bundledJarPath;
}
