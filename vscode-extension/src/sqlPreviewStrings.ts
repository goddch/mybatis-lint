/**
 * The words of the SQL preview panel, in English and Japanese. The panel is
 * drawn by a web page that cannot use `vscode.l10n`, so the extension picks the
 * language and sends the whole set to the page.
 */

export interface PreviewStrings {
  /** The page's language, for `<html lang>`. */
  lang: 'en' | 'ja';
  panelTitle: string;
  empty: string;
  /** `{0}` is the statement id. */
  missingStatement: string;
  copy: string;
  copied: string;
  branches: string;
  parameters: string;
  writeAs: string;
  named: string;
  namedTitle: string;
  question: string;
  questionTitle: string;
  values: string;
  valuesTitle: string;
  pickFromValues: string;
  pickFromValuesHint: string;
  auto: string;
  autoTitle: string;
  included: string;
  leftOut: string;
  fewer: string;
  more: string;
  item: string;
  items: string;
  otherwise: string;
  noBranch: string;
  testOnly: string;
  testOnlyTitle: string;
  insertedAsIs: string;
  insertedAsIsTitle: string;
  valuePlaceholder: string;
  dollarPlaceholder: string;
  noInputs: string;
  adds: string;
  note: string;
}

const EN: PreviewStrings = {
  lang: 'en',
  panelTitle: 'SQL Preview',
  empty: 'Put the cursor in a <select>, <insert>, <update> or <delete> of a mapper XML file.',
  missingStatement: "Statement '{0}' is no longer in the file.",
  copy: 'Copy SQL',
  copied: 'Copied',
  branches: 'Branches',
  parameters: 'Parameters',
  writeAs: 'Write parameters as',
  named: ':name',
  namedTitle: 'Named parameters; DBeaver and similar clients ask for the values when you run the SQL',
  question: '?',
  questionTitle: 'JDBC placeholders',
  values: 'Values',
  valuesTitle: 'The values typed below',
  pickFromValues: 'Pick branches from values',
  pickFromValuesHint: 'Evaluates each test with the values below, as OGNL does.',
  auto: 'auto',
  autoTitle: 'Decided from the values in Parameters',
  included: 'Included',
  leftOut: 'Left out',
  fewer: 'Fewer items',
  more: 'More items',
  item: 'item',
  items: 'items',
  otherwise: 'otherwise',
  noBranch: 'No branch matches, so nothing is added.',
  testOnly: 'test only',
  testOnlyTitle: 'Only read by test conditions, not written into the SQL',
  insertedAsIs: 'inserted as is',
  insertedAsIsTitle: '${...} puts the value into the SQL as it is',
  valuePlaceholder: "'abc'   123   NULL   true",
  dollarPlaceholder: 'e.g. o.ID DESC',
  noInputs: 'Choose Values, or Pick branches from values, to type values.',
  adds: 'adds',
  note: 'Assembled as MyBatis would. Choose the branches, or let the typed values decide them; <bind> variables stay as parameters.',
};

const JA: PreviewStrings = {
  lang: 'ja',
  panelTitle: 'SQL プレビュー',
  empty: 'mapper XML の <select> / <insert> / <update> / <delete> の中にカーソルを置いてください。',
  missingStatement: "statement '{0}' がファイルにありません。",
  copy: 'SQL をコピー',
  copied: 'コピーしました',
  branches: '分岐',
  parameters: 'パラメータ',
  writeAs: 'パラメータの書き方',
  named: ':name',
  namedTitle: '名前付きパラメータ。DBeaver などは実行するときに値を聞いてきます',
  question: '?',
  questionTitle: 'JDBC のプレースホルダ',
  values: '入力した値',
  valuesTitle: '下の欄に入力した値をそのまま入れます',
  pickFromValues: '入力した値から分岐を選ぶ',
  pickFromValuesHint: '下の欄の値で各条件(test)を評価します。評価のしかたは MyBatis(OGNL)と同じです。',
  auto: '自動',
  autoTitle: 'パラメータの値から決まりました',
  included: '含める',
  leftOut: '含めない',
  fewer: '減らす',
  more: '増やす',
  item: '件',
  items: '件',
  otherwise: 'otherwise',
  noBranch: 'どの分岐にも当てはまらないので、何も追加されません。',
  testOnly: '条件だけで使用',
  testOnlyTitle: '条件(test)で読むだけで、SQL には書かれません',
  insertedAsIs: 'そのまま埋め込み',
  insertedAsIsTitle: '${...} は値を SQL にそのまま埋め込みます',
  valuePlaceholder: "例: 'abc'   123   NULL   true",
  dollarPlaceholder: '例: o.ID DESC',
  noInputs: '値を入力するには、「入力した値」を選ぶか、「入力した値から分岐を選ぶ」をオンにしてください。',
  adds: '追加',
  note: 'MyBatis と同じ規則で組み立てています。分岐は手で選ぶか、入力した値から自動で選べます。<bind> の変数はパラメータのまま残ります。',
};

/**
 * @param setting the `mybatisLint.previewLanguage` setting: `auto`, `en` or `ja`
 * @param displayLanguage VS Code's display language, such as `ja` or `en-us`, used for `auto`
 */
export function previewStrings(setting: string | undefined, displayLanguage: string): PreviewStrings {
  const language = setting === 'en' || setting === 'ja' ? setting : displayLanguage.toLowerCase().startsWith('ja') ? 'ja' : 'en';
  return language === 'ja' ? JA : EN;
}
