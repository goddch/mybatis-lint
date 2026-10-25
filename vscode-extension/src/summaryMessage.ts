import type { AnalyzerSummary } from './analyzerTypes';

export interface SummaryMessage {
  level: 'info' | 'warning';
  text: string;
}

/**
 * Builds the notification shown after a workspace check.
 *
 * `fileCount` only counts files that produced diagnostics, so a clean
 * workspace and one with no mapper XML at all would otherwise look the same.
 * `mapperFileCount` tells them apart; it is optional because an older
 * analyzer jar configured via `mybatisLint.analyzerJarPath` may not emit it.
 */
export function buildSummaryMessage(summary: AnalyzerSummary): SummaryMessage {
  const counts = `found ${summary.errorCount} error(s) and ${summary.warningCount} warning(s)`;

  if (summary.mapperFileCount === undefined) {
    return {
      level: 'info',
      text: `MyBatis Lint: checked workspace, ${counts} across ${summary.fileCount} file(s).`,
    };
  }

  if (summary.mapperFileCount === 0) {
    return {
      level: 'warning',
      text:
        'MyBatis Lint: no MyBatis mapper XML files (with a <mapper> root element) were found in this workspace.' +
        (summary.errorCount + summary.warningCount > 0 ? ` Other XML files: ${counts}.` : ''),
    };
  }

  return {
    level: 'info',
    text: `MyBatis Lint: checked ${summary.mapperFileCount} mapper file(s), ${counts}.`,
  };
}
