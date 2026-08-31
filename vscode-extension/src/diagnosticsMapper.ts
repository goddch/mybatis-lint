import type { AnalyzerDiagnostic } from './analyzerTypes';

/** Mirrors vscode.DiagnosticSeverity's numeric values without importing 'vscode'. */
export enum PlainDiagnosticSeverity {
  Error = 0,
  Warning = 1,
}

/** A zero-based range, matching vscode.Range's constructor argument shape. */
export interface PlainRange {
  startLine: number;
  startCharacter: number;
  endLine: number;
  endCharacter: number;
}

export interface PlainDiagnostic {
  range: PlainRange;
  message: string;
  severity: PlainDiagnosticSeverity;
  code: string;
}

/** One URI's worth of diagnostics, ready to hand to a DiagnosticCollection. */
export interface UriDiagnostics {
  uri: string;
  diagnostics: PlainDiagnostic[];
}

function toPlainSeverity(severity: AnalyzerDiagnostic['severity']): PlainDiagnosticSeverity {
  return severity === 'error' ? PlainDiagnosticSeverity.Error : PlainDiagnosticSeverity.Warning;
}

function toPlainDiagnostic(diagnostic: AnalyzerDiagnostic): PlainDiagnostic {
  // The analyzer reports 1-based line/character; vscode.Position is 0-based.
  return {
    range: {
      startLine: Math.max(diagnostic.range.start.line - 1, 0),
      startCharacter: Math.max(diagnostic.range.start.character - 1, 0),
      endLine: Math.max(diagnostic.range.end.line - 1, 0),
      endCharacter: Math.max(diagnostic.range.end.character - 1, 0),
    },
    message: diagnostic.message,
    severity: toPlainSeverity(diagnostic.severity),
    code: diagnostic.ruleId,
  };
}

/**
 * Groups analyzer diagnostics by file URI and converts each one into a
 * plain, vscode-API-shaped object. Kept free of any `vscode` import so it
 * can be unit tested without an Extension Host.
 */
export function groupDiagnosticsByUri(diagnostics: AnalyzerDiagnostic[]): UriDiagnostics[] {
  const byUri = new Map<string, PlainDiagnostic[]>();
  for (const diagnostic of diagnostics) {
    const list = byUri.get(diagnostic.uri) ?? [];
    list.push(toPlainDiagnostic(diagnostic));
    byUri.set(diagnostic.uri, list);
  }
  return [...byUri.entries()].map(([uri, list]) => ({ uri, diagnostics: list }));
}
