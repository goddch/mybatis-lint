import type { AnalyzerSummary } from './analyzerTypes';
import type { DisplayLanguage } from './language';

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
export function buildSummaryMessage(summary: AnalyzerSummary, language: DisplayLanguage = 'en'): SummaryMessage {
  if (language === 'ja') {
    return japaneseSummary(summary);
  }
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

function japaneseSummary(summary: AnalyzerSummary): SummaryMessage {
  const counts = `エラー ${summary.errorCount} 件、警告 ${summary.warningCount} 件`;
  if (summary.mapperFileCount === undefined) {
    return { level: 'info', text: `MyBatis Lint: ワークスペースを確認しました。${summary.fileCount} ファイルで${counts}です。` };
  }
  if (summary.mapperFileCount === 0) {
    return {
      level: 'warning',
      text:
        'MyBatis Lint: このワークスペースに MyBatis の mapper XML(ルート要素が <mapper> のファイル)が見つかりません。' +
        (summary.errorCount + summary.warningCount > 0 ? `ほかの XML ファイル: ${counts}。` : ''),
    };
  }
  return { level: 'info', text: `MyBatis Lint: mapper ファイル ${summary.mapperFileCount} 個を確認しました。${counts}です。` };
}
