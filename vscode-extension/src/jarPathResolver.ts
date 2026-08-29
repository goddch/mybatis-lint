import * as path from 'path';

/**
 * Resolves the analyzer jar path to use.
 *
 * If the user configured `mybatisLint.analyzerJarPath`, that value wins
 * (this is what allows development against a locally built jar before one
 * is bundled with the extension). Otherwise falls back to a jar bundled
 * alongside the extension itself.
 */
export function resolveAnalyzerJarPath(configuredPath: string | undefined, extensionPath: string): string {
  if (configuredPath && configuredPath.trim().length > 0) {
    return configuredPath;
  }
  return path.join(extensionPath, 'analyzer.jar');
}
