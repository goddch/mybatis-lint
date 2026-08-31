/**
 * TypeScript mirror of the analyzer's JSON output schema.
 * See `docs/diagnostics-schema.md` for the authoritative documented schema.
 */

export type AnalyzerSeverity = 'error' | 'warning';

export interface AnalyzerPosition {
  /** 1-based line number. */
  line: number;
  /** 1-based character offset. */
  character: number;
}

export interface AnalyzerRange {
  start: AnalyzerPosition;
  end: AnalyzerPosition;
}

export interface AnalyzerDiagnostic {
  file: string;
  uri: string;
  ruleId: string;
  severity: AnalyzerSeverity;
  message: string;
  range: AnalyzerRange;
}

export interface AnalyzerSummary {
  fileCount: number;
  errorCount: number;
  warningCount: number;
}

export interface AnalyzerResult {
  schemaVersion: number;
  summary: AnalyzerSummary;
  diagnostics: AnalyzerDiagnostic[];
}
