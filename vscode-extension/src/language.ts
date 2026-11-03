/**
 * The language of what the extension shows: diagnostics (written by the analyzer), the SQL
 * preview, quick fixes, CodeLens and notifications.
 */

export type DisplayLanguage = 'en' | 'ja';

/**
 * @param setting the `mybatisLint.language` setting: `auto`, `en` or `ja`
 * @param displayLanguage VS Code's display language, such as `ja` or `en-us`, used for `auto`
 * @param previewSetting the older `mybatisLint.previewLanguage`, still honored when `language` is `auto`
 */
export function resolveLanguage(
  setting: string | undefined,
  displayLanguage: string,
  previewSetting?: string,
): DisplayLanguage {
  for (const value of [setting, previewSetting]) {
    if (value === 'en' || value === 'ja') {
      return value;
    }
  }
  return displayLanguage.toLowerCase().startsWith('ja') ? 'ja' : 'en';
}

/** The words the extension itself shows outside the SQL preview. */
export interface UiStrings {
  /** `{0}` is the suggested name. */
  changeTo: string;
  noReferences: string;
  oneReference: string;
  /** `{0}` is the number of references. */
  references: string;
}

const EN: UiStrings = {
  changeTo: "Change to '{0}'",
  noReferences: 'no references',
  oneReference: '1 reference',
  references: '{0} references',
};

const JA: UiStrings = {
  changeTo: "'{0}' に変更",
  noReferences: '参照なし',
  oneReference: '1 件の参照',
  references: '{0} 件の参照',
};

export function uiStrings(language: DisplayLanguage): UiStrings {
  return language === 'ja' ? JA : EN;
}

/** Puts values into `{0}`, `{1}`, ... */
export function format(text: string, ...values: (string | number)[]): string {
  return text.replace(/\{(\d+)\}/g, (placeholder, index: string) => String(values[Number(index)] ?? placeholder));
}
