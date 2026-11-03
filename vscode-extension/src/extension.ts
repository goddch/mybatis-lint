import * as fs from 'fs';
import * as vscode from 'vscode';

import { runAnalyzer } from './analyzerRunner';
import type { AnalyzerRunOptions } from './analyzerRunner';
import { AnalyzerServer } from './analyzerServer';
import { CheckScheduler, isRelevantForCheck } from './checkScheduler';
import { groupDiagnosticsByUri, PlainDiagnosticSeverity } from './diagnosticsMapper';
import { insertColumnHints, lineEndHints } from './insertColumnHints';
import { parseJavaType } from './javaSource';
import type { JavaType } from './javaSource';
import { completionsAt, hoverAt } from './mapperAssist';
import { childElements, parseXml } from './mapperXml';
import type { XmlElement } from './mapperXml';
import { previewStatement } from './sqlPreview';
import type { ParameterStyle, PreviewChoices } from './sqlPreview';
import { sqlPreviewHtml } from './sqlPreviewHtml';
import { previewStrings } from './sqlPreviewStrings';
import type { PreviewStrings } from './sqlPreviewStrings';
import type { JavaLookup } from './mapperAssist';
import {
  findJavaMethod,
  findXmlIds,
  javaMethodAt,
  javaResultMapOccurrences,
  mapperNamespace,
  occurrencesBySymbol,
  symbolAt,
  symbolKey,
  symbolOccurrences,
  TARGET_TAGS,
  xmlReferenceAt,
} from './navigation';
import type { MapperSymbol } from './navigation';
import { bundledJavaPath, resolveJavaPath } from './javaPathResolver';
import { parseAnalyzerOutput } from './parseAnalyzerOutput';
import { SuggestionStore } from './quickFixes';
import { resolveAnalyzerJarPath } from './jarPathResolver';
import { buildSummaryMessage } from './summaryMessage';

const COMMAND_ID = 'mybatisLint.checkWorkspace';
const SHOW_LOG_COMMAND_ID = 'mybatisLint.showLog';
const SHOW_LOG_ACTION = 'Show Log';
// Long enough that "Save All" or a formatter's follow-up save starts one run, not several.
const SAVE_DEBOUNCE_MS = 500;

/** Why a check started: from the command palette, or because a file was saved. */
type Trigger = 'command' | 'save';

interface CheckContext {
  extensionPath: string;
  /** The installed extension version, logged with every check so it is easy to tell which build is running. */
  version: string;
  diagnosticCollection: vscode.DiagnosticCollection;
  output: vscode.OutputChannel;
  suggestions: SuggestionStore;
  server: AnalyzerServer;
  /** Server failures in a row; after a few, checks run the analyzer once per check for the rest of the session. */
  serverFailures: number;
  /** Last error shown for a save-triggered run, so the same failure is not shown on every save. */
  lastSaveError?: string;
}

export function activate(context: vscode.ExtensionContext): void {
  const diagnosticCollection = vscode.languages.createDiagnosticCollection('mybatisLint');
  const output = vscode.window.createOutputChannel('MyBatis Lint');
  context.subscriptions.push(diagnosticCollection, output);

  const suggestions = new SuggestionStore();
  const checkContext: CheckContext = {
    extensionPath: context.extensionPath,
    version: String((context.extension.packageJSON as { version?: unknown }).version ?? 'unknown'),
    diagnosticCollection,
    output,
    suggestions,
    server: new AnalyzerServer(),
    serverFailures: 0,
  };
  context.subscriptions.push({ dispose: () => checkContext.server.dispose() });
  context.subscriptions.push(
    vscode.languages.registerCodeActionsProvider(
      { scheme: 'file', pattern: '**/*.{xml,java}' },
      { provideCodeActions: (document, _range, actionContext) => suggestionFixes(document, actionContext, suggestions) },
      { providedCodeActionKinds: [vscode.CodeActionKind.QuickFix] },
    ),
  );

  context.subscriptions.push(
    vscode.languages.registerDefinitionProvider(
      [
        { scheme: 'file', pattern: '**/*.xml' },
        { scheme: 'file', pattern: '**/*.java' },
      ],
      { provideDefinition: (document, position) => mapperDefinitions(document, position) },
    ),
  );

  const mapperXml: vscode.DocumentSelector = { scheme: 'file', pattern: '**/*.xml' };
  const sqlPreview = new SqlPreview();
  context.subscriptions.push(
    sqlPreview,
    vscode.commands.registerCommand('mybatisLint.showSqlPreview', () => sqlPreview.show()),
  );
  const codeLensesChanged = new vscode.EventEmitter<void>();
  context.subscriptions.push(
    codeLensesChanged,
    vscode.languages.registerCodeLensProvider(
      [mapperXml, { scheme: 'file', pattern: '**/*.java' }],
      { onDidChangeCodeLenses: codeLensesChanged.event, provideCodeLenses: (document) => mapperCodeLenses(document) },
    ),
    // References in other files change when those files are saved.
    vscode.workspace.onDidSaveTextDocument((document) => {
      if (isRelevantForCheck(document.fileName)) {
        codeLensesChanged.fire();
      }
    }),
    vscode.workspace.onDidChangeConfiguration((event) => {
      if (event.affectsConfiguration('mybatisLint.codeLens')) {
        codeLensesChanged.fire();
      }
    }),
  );
  context.subscriptions.push(
    vscode.languages.registerReferenceProvider(mapperXml, {
      provideReferences: (document, position, referenceContext) =>
        symbolReferences(document, position, referenceContext.includeDeclaration),
    }),
    vscode.languages.registerRenameProvider(mapperXml, {
      prepareRename: (document, position) => {
        const symbol = symbolAt(document.getText(), document.offsetAt(position));
        if (!symbol) {
          throw new Error('MyBatis Lint can rename <sql>, <resultMap> and statement ids, and references to them.');
        }
        return new vscode.Range(document.positionAt(symbol.start), document.positionAt(symbol.end));
      },
      provideRenameEdits: (document, position, newName) => renameSymbol(document, position, newName),
    }),
  );
  context.subscriptions.push(
    vscode.languages.registerHoverProvider(mapperXml, {
      provideHover: async (document, position) => {
        const text = document.getText();
        const hover = text.includes('<mapper') ? await hoverAt(text, document.offsetAt(position), javaLookup()) : undefined;
        return hover
          ? new vscode.Hover(
              new vscode.MarkdownString(hover.markdown),
              new vscode.Range(document.positionAt(hover.start), document.positionAt(hover.end)),
            )
          : undefined;
      },
    }),
    vscode.languages.registerCompletionItemProvider(
      mapperXml,
      {
        provideCompletionItems: async (document, position) => {
          const text = document.getText();
          const offset = document.offsetAt(position);
          const result = text.includes('<mapper') ? await completionsAt(text, offset, javaLookup()) : undefined;
          if (!result) {
            return undefined;
          }
          const range = new vscode.Range(document.positionAt(result.start), position);
          return result.items.map((item) => {
            const completion = new vscode.CompletionItem(
              item.label,
              item.kind === 'id' ? vscode.CompletionItemKind.Reference : vscode.CompletionItemKind.Field,
            );
            completion.detail = item.detail;
            completion.range = range;
            return completion;
          });
        },
      },
      '{',
      '.',
      '"',
      ',',
    ),
  );

  const columnHints = new ColumnHintDecorator();
  context.subscriptions.push(
    columnHints,
    vscode.window.onDidChangeVisibleTextEditors((editors) => editors.forEach((editor) => columnHints.update(editor))),
    vscode.window.onDidChangeTextEditorOptions((event) => columnHints.update(event.textEditor)),
    vscode.workspace.onDidChangeTextDocument((event) => columnHints.scheduleUpdate(event.document)),
    vscode.workspace.onDidChangeConfiguration((event) => {
      if (event.affectsConfiguration('mybatisLint.insertColumnHints')) {
        vscode.window.visibleTextEditors.forEach((editor) => columnHints.update(editor));
      }
    }),
  );
  vscode.window.visibleTextEditors.forEach((editor) => columnHints.update(editor));
  const scheduler = new CheckScheduler(() => checkWorkspace(checkContext, 'save'), SAVE_DEBOUNCE_MS);
  context.subscriptions.push({ dispose: () => scheduler.dispose() });

  context.subscriptions.push(
    vscode.commands.registerCommand(COMMAND_ID, () => checkWorkspace(checkContext, 'command')),
    vscode.commands.registerCommand(SHOW_LOG_COMMAND_ID, () => output.show(true)),
    vscode.workspace.onDidSaveTextDocument((document) => {
      const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
      if (
        !workspaceFolder ||
        !vscode.workspace.getConfiguration('mybatisLint').get<boolean>('runOnSave', true) ||
        !isRelevantForCheck(document.fileName) ||
        vscode.workspace.getWorkspaceFolder(document.uri) !== workspaceFolder
      ) {
        return;
      }
      scheduler.request();
    }),
  );
}

/**
 * Shows the INSERT column names faintly at the end of each line that has values, lined up
 * within a statement. A decoration (not an inlay hint) is used because an inlay hint draws
 * its leading spaces with the hint background, which many themes color; a decoration's
 * margin is measured in `ch`, so it lines up without a background.
 */
class ColumnHintDecorator implements vscode.Disposable {
  private readonly decoration = vscode.window.createTextEditorDecorationType({
    after: { color: new vscode.ThemeColor('editorInlayHint.foreground') },
    rangeBehavior: vscode.DecorationRangeBehavior.ClosedClosed,
  });
  private readonly timers = new Map<string, ReturnType<typeof setTimeout>>();

  /** Recomputes shortly after typing stops, rather than on every keystroke. */
  scheduleUpdate(document: vscode.TextDocument): void {
    const key = document.uri.toString();
    clearTimeout(this.timers.get(key));
    this.timers.set(
      key,
      setTimeout(() => {
        this.timers.delete(key);
        vscode.window.visibleTextEditors
          .filter((editor) => editor.document === document)
          .forEach((editor) => this.update(editor));
      }, 150),
    );
  }

  update(editor: vscode.TextEditor): void {
    const document = editor.document;
    const mode = vscode.workspace.getConfiguration('mybatisLint').get<string>('insertColumnHints', 'all');
    const text = document.uri.scheme === 'file' && document.fileName.endsWith('.xml') ? document.getText() : '';
    if (mode === 'off' || !text.includes('<insert')) {
      editor.setDecorations(this.decoration, []);
      return;
    }
    const tabSize = typeof editor.options.tabSize === 'number' ? editor.options.tabSize : 4;
    const hints = insertColumnHints(text).filter((hint) => mode !== 'differentNames' || !hint.sameName);
    editor.setDecorations(
      this.decoration,
      lineEndHints(text, hints, tabSize).map((hint) => {
        const position = document.positionAt(hint.offset);
        return {
          range: new vscode.Range(position, position),
          renderOptions: { after: { contentText: hint.text, margin: `0 0 0 ${hint.padding}ch` } },
        };
      }),
    );
  }

  dispose(): void {
    this.timers.forEach((timer) => clearTimeout(timer));
    this.decoration.dispose();
  }
}

/**
 * Finds Java types by simple name in the workspace for hover and completion. One lookup serves one
 * request, so a type asked for twice (a mapper and its domain's superclass) is read once.
 */
function javaLookup(): JavaLookup {
  const cache = new Map<string, Promise<JavaType[]>>();
  return {
    findTypes: (simpleName) => {
      if (!/^[\w$]+$/.test(simpleName)) {
        return Promise.resolve([]);
      }
      const cached = cache.get(simpleName) ?? readJavaTypes(simpleName);
      cache.set(simpleName, cached);
      return cached;
    },
  };
}

async function readJavaTypes(simpleName: string): Promise<JavaType[]> {
  const types: JavaType[] = [];
  for (const file of await vscode.workspace.findFiles(`**/${simpleName}.java`, NAVIGATION_EXCLUDE, 20)) {
    // Open documents may have unsaved edits; read those from the editor, the rest from disk.
    const open = vscode.workspace.textDocuments.find((candidate) => candidate.uri.toString() === file.toString());
    const text = open ? open.getText() : Buffer.from(await vscode.workspace.fs.readFile(file)).toString('utf8');
    const type = parseJavaType(text);
    if (type) {
      types.push(type);
    }
  }
  return types;
}

/** Mapper XML files in the workspace with their text; open documents are read from the editor. */
async function workspaceXmlFiles(): Promise<Array<{ uri: vscode.Uri; text: string }>> {
  const files: Array<{ uri: vscode.Uri; text: string }> = [];
  for (const uri of await vscode.workspace.findFiles('**/*.xml', NAVIGATION_EXCLUDE)) {
    const open = vscode.workspace.textDocuments.find((candidate) => candidate.uri.toString() === uri.toString());
    const text = open ? open.getText() : Buffer.from(await vscode.workspace.fs.readFile(uri)).toString('utf8');
    if (text.includes('<mapper')) {
      files.push({ uri, text });
    }
  }
  return files;
}

/** The Java mapper interface of a namespace, if it is in the workspace. */
async function mapperInterfaceDocument(namespace: string): Promise<vscode.TextDocument | undefined> {
  const [file] = await vscode.workspace.findFiles(`**/${namespace.replace(/\./g, '/')}.java`, NAVIGATION_EXCLUDE, 1);
  return file ? vscode.workspace.openTextDocument(file) : undefined;
}

/** Every place a symbol is written: XML definitions and references, @ResultMap values, the Java method. */
async function symbolLocations(symbol: MapperSymbol, includeDeclaration: boolean): Promise<vscode.Location[]> {
  const locations: vscode.Location[] = [];
  for (const file of await workspaceXmlFiles()) {
    if (!file.text.includes(symbol.id)) {
      continue;
    }
    const occurrences = symbolOccurrences(file.text, symbol).filter((occurrence) => includeDeclaration || !occurrence.definition);
    if (occurrences.length > 0) {
      const document = await vscode.workspace.openTextDocument(file.uri);
      for (const occurrence of occurrences) {
        locations.push(new vscode.Location(file.uri, new vscode.Range(document.positionAt(occurrence.start), document.positionAt(occurrence.end))));
      }
    }
  }
  const java = await mapperInterfaceDocument(symbol.namespace);
  if (java && symbol.kind === 'resultMap') {
    for (const occurrence of javaResultMapOccurrences(java.getText(), symbol)) {
      locations.push(new vscode.Location(java.uri, new vscode.Range(java.positionAt(occurrence.start), java.positionAt(occurrence.end))));
    }
  }
  if (java && symbol.kind === 'statement' && includeDeclaration) {
    const offset = findJavaMethod(java.getText(), symbol.id);
    if (offset >= 0) {
      locations.push(new vscode.Location(java.uri, java.positionAt(offset)));
    }
  }
  return locations;
}

async function symbolReferences(
  document: vscode.TextDocument,
  position: vscode.Position,
  includeDeclaration: boolean,
): Promise<vscode.Location[]> {
  const symbol = symbolAt(document.getText(), document.offsetAt(position));
  return symbol ? symbolLocations(symbol, includeDeclaration) : [];
}

/**
 * Renames a <sql>, <resultMap> or statement id with every reference to it. For a statement the Java
 * method is renamed too, through the Java extension when one is installed so that its callers follow.
 */
async function renameSymbol(
  document: vscode.TextDocument,
  position: vscode.Position,
  newName: string,
): Promise<vscode.WorkspaceEdit | undefined> {
  const symbol = symbolAt(document.getText(), document.offsetAt(position));
  if (!symbol) {
    return undefined;
  }
  if (!/^[A-Za-z_$][\w$]*$/.test(newName)) {
    throw new Error(`'${newName}' cannot be used as an id; use letters, digits, _ and $.`);
  }
  const edit = new vscode.WorkspaceEdit();
  const target: MapperSymbol = { kind: symbol.kind, namespace: symbol.namespace, id: symbol.id };
  for (const location of await symbolLocations(target, true)) {
    const isJavaMethod = location.uri.fsPath.endsWith('.java') && symbol.kind === 'statement';
    if (!isJavaMethod) {
      edit.replace(location.uri, location.range, newName);
    }
  }
  if (symbol.kind === 'statement') {
    await renameJavaMethod(target, newName, edit);
  }
  return edit;
}

async function renameJavaMethod(symbol: MapperSymbol, newName: string, edit: vscode.WorkspaceEdit): Promise<void> {
  const java = await mapperInterfaceDocument(symbol.namespace);
  const offset = java ? findJavaMethod(java.getText(), symbol.id) : -1;
  if (!java || offset < 0) {
    return;
  }
  const position = java.positionAt(offset);
  let javaEdit: vscode.WorkspaceEdit | undefined;
  try {
    javaEdit = await vscode.commands.executeCommand<vscode.WorkspaceEdit>(
      'vscode.executeDocumentRenameProvider',
      java.uri,
      position,
      newName,
    );
  } catch {
    javaEdit = undefined;
  }
  if (javaEdit && javaEdit.size > 0) {
    for (const [uri, edits] of javaEdit.entries()) {
      for (const textEdit of edits) {
        edit.replace(uri, textEdit.range, textEdit.newText);
      }
    }
    return;
  }
  // Without a Java language extension, only the declaration can be renamed.
  edit.replace(java.uri, new vscode.Range(position, position.translate(0, symbol.id.length)), newName);
  void vscode.window.showWarningMessage(
    `MyBatis Lint renamed ${symbol.id} in the mapper interface only; callers in Java were not updated (no Java extension answered the rename).`,
  );
}

/**
 * Small links above definitions: how many places use a <sql> / <resultMap>, which Java method a
 * statement belongs to, and which statement a Java mapper method runs.
 */
async function mapperCodeLenses(document: vscode.TextDocument): Promise<vscode.CodeLens[]> {
  if (!vscode.workspace.getConfiguration('mybatisLint').get<boolean>('codeLens', true)) {
    return [];
  }
  const text = document.getText();
  const isJava = document.fileName.endsWith('.java');
  if (isJava ? !/\binterface\s/.test(text) : !text.includes('<mapper')) {
    return [];
  }
  const files = await workspaceXmlFiles();
  const grouped = occurrencesBySymbol(files.map(({ uri, text: fileText }) => ({ file: uri, text: fileText })));
  const toLocation = async (occurrence: { file: vscode.Uri; start: number; end: number }) => {
    const target = await vscode.workspace.openTextDocument(occurrence.file);
    return new vscode.Location(occurrence.file, new vscode.Range(target.positionAt(occurrence.start), target.positionAt(occurrence.end)));
  };
  const lenses: vscode.CodeLens[] = [];
  if (isJava) {
    const type = parseJavaType(text);
    if (!type?.isInterface) {
      return [];
    }
    for (const method of new Set(type.methods.map((candidate) => candidate.name))) {
      const statements = (grouped.get(symbolKey({ kind: 'statement', namespace: type.qualifiedName, id: method })) ?? [])
        .filter((occurrence) => occurrence.definition);
      const offset = findJavaMethod(text, method);
      if (statements.length === 0 || offset < 0) {
        continue;
      }
      const range = new vscode.Range(document.positionAt(offset), document.positionAt(offset + method.length));
      const locations = await Promise.all(statements.map(toLocation));
      const title = statements.length === 1 ? `XML: ${basename(statements[0].file)}` : `XML: ${statements.length} statements`;
      lenses.push(new vscode.CodeLens(range, showLocations(title, document.uri, range.start, locations)));
    }
    return lenses;
  }
  const java = mapperNamespace(text);
  const javaDocument = java ? await mapperInterfaceDocument(java) : undefined;
  for (const definition of symbolOccurrences(text, undefined).filter((occurrence) => occurrence.definition)) {
    const range = new vscode.Range(document.positionAt(definition.start), document.positionAt(definition.end));
    if (definition.kind === 'statement') {
      const offset = javaDocument ? findJavaMethod(javaDocument.getText(), definition.id) : -1;
      if (javaDocument && offset >= 0) {
        const location = new vscode.Location(javaDocument.uri, javaDocument.positionAt(offset));
        const title = `Java: ${basename(javaDocument.uri).replace(/\.java$/, '')}.${definition.id}`;
        lenses.push(new vscode.CodeLens(range, showLocations(title, document.uri, range.start, [location])));
      }
      continue;
    }
    const references = (grouped.get(symbolKey(definition)) ?? []).filter((occurrence) => !occurrence.definition);
    const locations = await Promise.all(references.map(toLocation));
    if (definition.kind === 'resultMap' && javaDocument) {
      for (const occurrence of javaResultMapOccurrences(javaDocument.getText(), definition)) {
        locations.push(new vscode.Location(javaDocument.uri, javaDocument.positionAt(occurrence.start)));
      }
    }
    const title = locations.length === 0 ? 'no references' : locations.length === 1 ? '1 reference' : `${locations.length} references`;
    lenses.push(new vscode.CodeLens(range, showLocations(title, document.uri, range.start, locations)));
  }
  return lenses;
}

/** A CodeLens command that opens the peek view with the given locations. */
function showLocations(title: string, uri: vscode.Uri, position: vscode.Position, locations: vscode.Location[]): vscode.Command {
  return locations.length === 0
    ? { title, command: '' }
    : { title, command: 'editor.action.showReferences', arguments: [uri, position, locations] };
}

function basename(uri: vscode.Uri): string {
  return uri.path.slice(uri.path.lastIndexOf('/') + 1);
}

const PREVIEW_STATEMENTS = new Set(['select', 'insert', 'update', 'delete']);

/** What the user chose for one statement, kept while the preview is open. */
interface PreviewState {
  choices: Required<PreviewChoices>;
  style: ParameterStyle;
  values: Record<string, string>;
  /** Decide branches from the typed values where the tests can be evaluated. */
  auto: boolean;
}

/**
 * A panel beside the editor showing the plain SQL of the statement under the cursor, like the
 * Markdown preview. It follows the cursor and edits; the user switches branches in the panel.
 */
class SqlPreview implements vscode.Disposable {
  private panel: vscode.WebviewPanel | undefined;
  private readonly disposables: vscode.Disposable[] = [];
  private readonly states = new Map<string, PreviewState>();
  /** The statement shown: its document and id. */
  private current: { document: vscode.TextDocument; id: string } | undefined;
  /** Other mapper files, for <include> across files; refreshed when the panel opens and on save. */
  private mappers: XmlElement[] = [];
  private lastSql = '';
  private timer: ReturnType<typeof setTimeout> | undefined;

  /** Opens or reveals the panel; returns the SQL it shows (the end-to-end tests read it). */
  async show(): Promise<string> {
    if (this.panel) {
      this.panel.reveal(vscode.ViewColumn.Beside, true);
    } else {
      this.panel = vscode.window.createWebviewPanel(
        'mybatisLint.sqlPreview',
        this.strings().panelTitle,
        { viewColumn: vscode.ViewColumn.Beside, preserveFocus: true },
        { enableScripts: true, retainContextWhenHidden: true },
      );
      this.setHtml();
      this.panel.onDidDispose(() => this.close(), undefined, this.disposables);
      this.panel.webview.onDidReceiveMessage((message) => this.onMessage(message), undefined, this.disposables);
      this.disposables.push(
        vscode.window.onDidChangeTextEditorSelection((event) => this.follow(event.textEditor)),
        vscode.window.onDidChangeActiveTextEditor((editor) => editor && this.follow(editor)),
        vscode.workspace.onDidChangeTextDocument((event) => {
          if (event.document === this.current?.document) {
            this.schedule();
          }
        }),
        vscode.workspace.onDidChangeConfiguration((event) => {
          if (event.affectsConfiguration('mybatisLint.previewLanguage')) {
            // The page is rebuilt in the new language; it asks for the statement again when ready.
            this.setHtml();
          }
        }),
        vscode.workspace.onDidSaveTextDocument(async (document) => {
          if (document.fileName.endsWith('.xml')) {
            await this.loadMappers();
            this.render();
          }
        }),
      );
      await this.loadMappers();
    }
    const editor = vscode.window.activeTextEditor;
    if (editor) {
      this.follow(editor);
    }
    return this.lastSql;
  }

  dispose(): void {
    this.panel?.dispose();
    this.close();
  }

  private strings(): PreviewStrings {
    return previewStrings(vscode.workspace.getConfiguration('mybatisLint').get<string>('previewLanguage'), vscode.env.language);
  }

  private setHtml(): void {
    if (!this.panel) {
      return;
    }
    const nonce = Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16)).join('');
    this.panel.webview.html = sqlPreviewHtml(nonce, this.panel.webview.cspSource, this.strings());
    this.panel.title = this.current ? `${this.strings().panelTitle}: ${this.current.id}` : this.strings().panelTitle;
  }

  private close(): void {
    this.panel = undefined;
    this.current = undefined;
    clearTimeout(this.timer);
    this.disposables.splice(0).forEach((disposable) => disposable.dispose());
  }

  private async loadMappers(): Promise<void> {
    this.mappers = (await workspaceXmlFiles())
      .map((file) => parseXml(file.text))
      .filter((root): root is XmlElement => root?.name === 'mapper');
  }

  /** Shows the statement under the cursor; outside statements, the last one stays. */
  private follow(editor: vscode.TextEditor): void {
    if (!this.panel || !editor.document.fileName.endsWith('.xml')) {
      return;
    }
    const root = parseXml(editor.document.getText());
    const offset = editor.document.offsetAt(editor.selection.active);
    const statement = root?.name === 'mapper'
      ? childElements(root).find((element) => PREVIEW_STATEMENTS.has(element.name) && element.start <= offset && offset <= element.end)
      : undefined;
    const id = statement?.attributes.get('id');
    if (id && (this.current?.document !== editor.document || this.current.id !== id)) {
      this.current = { document: editor.document, id };
      this.render();
    } else if (!this.current) {
      void this.panel.webview.postMessage({ type: 'render', reason: this.strings().empty });
    }
  }

  private schedule(): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.render(), 200);
  }

  private state(): PreviewState {
    const key = `${this.current?.document.uri.toString()}#${this.current?.id}`;
    let state = this.states.get(key);
    if (!state) {
      state = { choices: { ifs: {}, chooses: {}, foreachCounts: {} }, style: 'named', values: {}, auto: false };
      this.states.set(key, state);
    }
    return state;
  }

  private render(): void {
    if (!this.panel || !this.current) {
      return;
    }
    const { document, id } = this.current;
    const root = parseXml(document.getText());
    const namespace = root?.attributes.get('namespace') ?? '';
    const statement = root ? childElements(root).find((element) => PREVIEW_STATEMENTS.has(element.name) && element.attributes.get('id') === id) : undefined;
    if (!root || !statement) {
      void this.panel.webview.postMessage({ type: 'render', reason: this.strings().missingStatement.replace('{0}', id) });
      return;
    }
    // The open document may have unsaved fragments, so it is searched before the files on disk.
    const mappers = [root, ...this.mappers.filter((mapper) => mapper.attributes.get('namespace') !== namespace)];
    const fragments = (fragmentNamespace: string, fragmentId: string) =>
      mappers
        .filter((mapper) => mapper.attributes.get('namespace') === fragmentNamespace)
        .flatMap(childElements)
        .find((element) => element.name === 'sql' && element.attributes.get('id') === fragmentId);
    const state = this.state();
    const result = previewStatement(statement, namespace, fragments, state.choices, state.style, state.values, state.auto);
    this.lastSql = result.sql;
    this.panel.title = `${this.strings().panelTitle}: ${id}`;
    void this.panel.webview.postMessage({
      type: 'render',
      statement: {
        tag: statement.name,
        id,
        file: vscode.workspace.asRelativePath(document.uri),
        controls: result.controls,
        parameters: result.parameters,
        sql: result.sql,
        choices: state.choices,
        style: state.style,
        values: state.values,
        auto: state.auto,
      },
    });
  }

  private onMessage(message: {
    type?: string;
    choices?: PreviewChoices;
    style?: ParameterStyle;
    values?: Record<string, string>;
    auto?: boolean;
  }): void {
    if (message.type === 'ready') {
      this.render();
    } else if (message.type === 'copy') {
      void vscode.env.clipboard.writeText(this.lastSql);
    } else if (message.type === 'update' && this.current) {
      const state = this.state();
      state.choices = {
        ifs: { ...message.choices?.ifs },
        chooses: { ...message.choices?.chooses },
        foreachCounts: { ...message.choices?.foreachCounts },
      };
      state.style = message.style === 'question' || message.style === 'values' ? message.style : 'named';
      state.values = { ...message.values };
      state.auto = message.auto === true;
      this.render();
    }
  }
}

// After this many server failures in a row, stop using it until VS Code restarts.
const MAX_SERVER_FAILURES = 2;

/**
 * Runs a check on the long-running analyzer (fast after the first check), or starts the
 * analyzer once for this check when that is turned off or the server fails.
 */
async function analyze(checkContext: CheckContext, options: AnalyzerRunOptions): Promise<{ stdout: string; stderr: string }> {
  const keepRunning = vscode.workspace.getConfiguration('mybatisLint').get<boolean>('keepAnalyzerRunning', true);
  if (!keepRunning || checkContext.serverFailures >= MAX_SERVER_FAILURES) {
    checkContext.server.dispose();
    return runAnalyzer(options);
  }
  try {
    const result = await checkContext.server.request(options, serverProcessKey(options));
    checkContext.serverFailures = 0;
    return result;
  } catch (error) {
    checkContext.serverFailures++;
    log(checkContext.output, `  ${error instanceof Error ? error.message : String(error)} Running the analyzer once instead.`);
    return runAnalyzer(options);
  }
}

/** Restart the server when the Java runtime or the jar changes, including a rebuilt jar. */
function serverProcessKey(options: AnalyzerRunOptions): string {
  let jarModified = 0;
  try {
    jarModified = fs.statSync(options.jarPath).mtimeMs;
  } catch {
    // A missing jar is reported by the check itself.
  }
  return `${options.javaPath}\u0000${options.jarPath}\u0000${jarModified}`;
}

// Folders that never contain the mapper sources a jump should land in.
const NAVIGATION_EXCLUDE = '{**/node_modules/**,**/target/**,**/build/**,**/.git/**,**/.gradle/**}';

/**
 * "Go to Definition" between mapper XML and Java: a statement id to its Java mapper method and
 * back, `<include refid>` to its `<sql>`, and `resultMap` / `extends` to the `<resultMap>`.
 */
async function mapperDefinitions(document: vscode.TextDocument, position: vscode.Position): Promise<vscode.Location[]> {
  const text = document.getText();
  const offset = document.offsetAt(position);
  if (document.fileName.endsWith('.java')) {
    const method = javaMethodAt(text, offset);
    return method ? xmlLocations(method.namespace, TARGET_TAGS.statement, method.id, undefined) : [];
  }
  const reference = text.includes('<mapper') ? xmlReferenceAt(text, offset) : undefined;
  if (!reference || reference.namespace.length === 0) {
    return [];
  }
  if (reference.kind === 'statement') {
    const files = await vscode.workspace.findFiles(`**/${reference.namespace.replace(/\./g, '/')}.java`, NAVIGATION_EXCLUDE);
    const locations: vscode.Location[] = [];
    for (const file of files) {
      const javaDocument = await vscode.workspace.openTextDocument(file);
      const methodOffset = findJavaMethod(javaDocument.getText(), reference.id);
      if (methodOffset >= 0) {
        locations.push(new vscode.Location(file, javaDocument.positionAt(methodOffset)));
      }
    }
    return locations;
  }
  // References within the same file are the common case and need no search.
  if (reference.namespace === mapperNamespace(text)) {
    const local = findXmlIds(text, TARGET_TAGS[reference.kind], reference.id)
      .map((idOffset) => new vscode.Location(document.uri, document.positionAt(idOffset)));
    if (local.length > 0) {
      return local;
    }
  }
  return xmlLocations(reference.namespace, TARGET_TAGS[reference.kind], reference.id, document.uri);
}

/** `<tag id="id">` elements in the workspace's mapper XML files with this namespace. */
async function xmlLocations(
  namespace: string,
  tags: string[],
  id: string,
  skip: vscode.Uri | undefined,
): Promise<vscode.Location[]> {
  const locations: vscode.Location[] = [];
  for (const file of await vscode.workspace.findFiles('**/*.xml', NAVIGATION_EXCLUDE)) {
    if (skip && file.toString() === skip.toString()) {
      continue;
    }
    // Open documents may have unsaved edits; read those from the editor, the rest from disk.
    const open = vscode.workspace.textDocuments.find((candidate) => candidate.uri.toString() === file.toString());
    const text = open ? open.getText() : Buffer.from(await vscode.workspace.fs.readFile(file)).toString('utf8');
    if (!text.includes(namespace) || mapperNamespace(text) !== namespace) {
      continue;
    }
    for (const idOffset of findXmlIds(text, tags, id)) {
      const document = open ?? (await vscode.workspace.openTextDocument(file));
      locations.push(new vscode.Location(file, document.positionAt(idOffset)));
    }
  }
  return locations;
}

/** "Change to 'userName'" quick fixes for diagnostics that carry a suggested name. */
function suggestionFixes(
  document: vscode.TextDocument,
  actionContext: vscode.CodeActionContext,
  suggestions: SuggestionStore,
): vscode.CodeAction[] {
  const actions: vscode.CodeAction[] = [];
  for (const diagnostic of actionContext.diagnostics) {
    if (diagnostic.source !== 'mybatis-lint' || typeof diagnostic.code !== 'string' || diagnostic.range.isEmpty) {
      continue;
    }
    const suggestion = suggestions.find(
      document.uri.toString(),
      diagnostic.code,
      diagnostic.message,
      document.getText(diagnostic.range),
    );
    if (suggestion === undefined) {
      continue;
    }
    const action = new vscode.CodeAction(`Change to '${suggestion}'`, vscode.CodeActionKind.QuickFix);
    action.edit = new vscode.WorkspaceEdit();
    action.edit.replace(document.uri, diagnostic.range, suggestion);
    action.diagnostics = [diagnostic];
    action.isPreferred = true;
    actions.push(action);
  }
  return actions;
}

export function deactivate(): void {
  // No background resources to clean up beyond what's registered via
  // context.subscriptions (disposed automatically by VS Code).
}

/**
 * Restores the executable bit on the bundled `java` if the VSIX install lost
 * it. Best effort: if this fails, launching reports a clear error anyway.
 */
function ensureExecutable(filePath: string): void {
  if (process.platform === 'win32') {
    return;
  }
  try {
    fs.accessSync(filePath, fs.constants.X_OK);
  } catch {
    try {
      fs.chmodSync(filePath, 0o755);
    } catch {
      // Ignored: runAnalyzer surfaces the launch failure to the user.
    }
  }
}

/** Reads a string-array setting, ignoring entries a hand-edited settings.json may have of the wrong type. */
function stringArraySetting(config: vscode.WorkspaceConfiguration, key: string): string[] {
  const value = config.get<unknown>(key);
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : [];
}

function log(output: vscode.OutputChannel, message: string): void {
  output.appendLine(`[${new Date().toLocaleTimeString()}] ${message}`);
}

/**
 * Shows a failure. Command runs always show it; save-triggered runs show a
 * given failure once, so a broken setup does not pop up on every save.
 */
function reportFailure(checkContext: CheckContext, trigger: Trigger, message: string): void {
  log(checkContext.output, `Failed: ${message}`);
  if (trigger === 'save') {
    if (checkContext.lastSaveError === message) {
      return;
    }
    checkContext.lastSaveError = message;
  }
  void vscode.window.showErrorMessage(message, SHOW_LOG_ACTION).then((choice) => {
    if (choice === SHOW_LOG_ACTION) {
      checkContext.output.show(true);
    }
  });
}

async function checkWorkspace(checkContext: CheckContext, trigger: Trigger): Promise<void> {
  const { extensionPath, diagnosticCollection, output } = checkContext;
  const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
  if (!workspaceFolder) {
    void vscode.window.showErrorMessage('MyBatis Lint: open a folder/workspace before running this command.');
    return;
  }

  const config = vscode.workspace.getConfiguration('mybatisLint');
  const javaPath = resolveJavaPath(
    config.inspect<string>('javaPath')?.workspaceFolderValue ??
      config.inspect<string>('javaPath')?.workspaceValue ??
      config.inspect<string>('javaPath')?.globalValue,
    extensionPath,
  );
  if (javaPath === bundledJavaPath(extensionPath)) {
    ensureExecutable(javaPath);
  }
  const jarPath = resolveAnalyzerJarPath(config.get<string>('analyzerJarPath'), extensionPath);
  const excludes = stringArraySetting(config, 'exclude');
  const disabledRules = stringArraySetting(config, 'disabledRules');
  const sqlDialect = config.get<string>('sqlDialect', 'auto');

  log(
    output,
    `Checking ${workspaceFolder.uri.fsPath} (${trigger === 'save' ? 'on save' : 'command'}, MyBatis Lint ${checkContext.version})`,
  );
  log(output, `  Java: ${javaPath}`);
  log(output, `  Analyzer: ${jarPath}`);
  if (excludes.length > 0) {
    log(output, `  Excluded: ${excludes.join(', ')}`);
  }
  if (disabledRules.length > 0) {
    log(output, `  Disabled rules: ${disabledRules.join(', ')}`);
  }

  if (!fs.existsSync(jarPath)) {
    reportFailure(
      checkContext,
      trigger,
      `MyBatis Lint: analyzer jar not found at "${jarPath}". ` +
        'Build it with "./gradlew jar" under analyzer/, then set "mybatisLint.analyzerJarPath".',
    );
    return;
  }

  const startedAt = Date.now();
  try {
    // The status bar spinner tells save-triggered runs apart from an idle
    // extension; the notification is reserved for runs the user asked for.
    const { stdout, stderr } = await vscode.window.withProgress(
      { location: vscode.ProgressLocation.Window, title: 'MyBatis Lint: checking…' },
      () =>
        analyze(checkContext, {
          javaPath,
          jarPath,
          workspacePath: workspaceFolder.uri.fsPath,
          excludes,
          disabledRules,
          sqlDialect,
        }),
    );
    for (const line of stderr.split(/\r?\n/).filter((text) => text.trim().length > 0)) {
      log(output, `  ${line}`);
    }
    const result = parseAnalyzerOutput(stdout);
    const grouped = groupDiagnosticsByUri(result.diagnostics);

    // Replace everything at once, after the new results are ready, so the
    // Problems panel does not flicker empty on every save.
    diagnosticCollection.clear();
    checkContext.suggestions.clear();
    for (const { uri, diagnostics } of grouped) {
      const documentUri = vscode.Uri.parse(uri);
      const vscodeDiagnostics = diagnostics.map((diagnostic) => {
        const vscodeDiagnostic = new vscode.Diagnostic(
          new vscode.Range(
            diagnostic.range.startLine,
            diagnostic.range.startCharacter,
            diagnostic.range.endLine,
            diagnostic.range.endCharacter,
          ),
          diagnostic.message,
          diagnostic.severity === PlainDiagnosticSeverity.Error
            ? vscode.DiagnosticSeverity.Error
            : vscode.DiagnosticSeverity.Warning,
        );
        vscodeDiagnostic.source = 'mybatis-lint';
        vscodeDiagnostic.code = diagnostic.code;
        if (diagnostic.suggestion !== undefined) {
          // Keyed by the URI string VS Code itself uses, which encodes some characters differently.
          checkContext.suggestions.set(documentUri.toString(), diagnostic.code, diagnostic.message, diagnostic.suggestion);
        }
        return vscodeDiagnostic;
      });
      diagnosticCollection.set(documentUri, vscodeDiagnostics);
    }

    const summaryMessage = buildSummaryMessage(result.summary);
    const seconds = ((Date.now() - startedAt) / 1000).toFixed(1);
    log(output, `Done in ${seconds}s. ${summaryMessage.text.replace(/^MyBatis Lint: /, '')}`);
    checkContext.lastSaveError = undefined;

    if (trigger === 'save') {
      return;
    }
    if (summaryMessage.level === 'warning') {
      void vscode.window.showWarningMessage(summaryMessage.text);
    } else {
      void vscode.window.showInformationMessage(summaryMessage.text);
    }
  } catch (error) {
    // Stale diagnostics would look like current results, so drop them.
    diagnosticCollection.clear();
    checkContext.suggestions.clear();
    reportFailure(checkContext, trigger, error instanceof Error ? error.message : String(error));
  }
}
