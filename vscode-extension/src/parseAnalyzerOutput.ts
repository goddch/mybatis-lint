import type { AnalyzerResult } from './analyzerTypes';

/**
 * Parses the analyzer CLI's stdout JSON payload.
 *
 * Throws a descriptive Error if the text is not valid JSON or does not look
 * like an analyzer result (missing the fields we rely on).
 */
export function parseAnalyzerOutput(stdout: string): AnalyzerResult {
  let parsed: unknown;
  try {
    parsed = JSON.parse(stdout);
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    throw new Error(`MyBatis Lint: analyzer output was not valid JSON (${reason}).`);
  }

  if (
    typeof parsed !== 'object' ||
    parsed === null ||
    !Array.isArray((parsed as Record<string, unknown>).diagnostics)
  ) {
    throw new Error('MyBatis Lint: analyzer output did not match the expected schema.');
  }

  return parsed as AnalyzerResult;
}
