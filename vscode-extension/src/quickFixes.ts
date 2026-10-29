/**
 * Remembers the analyzer's suggested replacement ("did you mean") for each
 * diagnostic, so the code action provider can offer it as a quick fix.
 *
 * VS Code hands code action providers copies of the diagnostics, and moves
 * their ranges as the user edits, so suggestions are looked up by file, rule
 * and message rather than by object identity or range.
 */
export class SuggestionStore {
  private readonly suggestions = new Map<string, string>();

  clear(): void {
    this.suggestions.clear();
  }

  set(uri: string, code: string, message: string, suggestion: string): void {
    this.suggestions.set(key(uri, code, message), suggestion);
  }

  /**
   * The replacement for a diagnostic, or undefined when there is none or when
   * the text under the diagnostic already reads as the suggestion.
   */
  find(uri: string, code: string, message: string, currentText: string): string | undefined {
    const suggestion = this.suggestions.get(key(uri, code, message));
    return suggestion !== undefined && suggestion !== currentText ? suggestion : undefined;
  }
}

function key(uri: string, code: string, message: string): string {
  return `${uri}\u0000${code}\u0000${message}`;
}
