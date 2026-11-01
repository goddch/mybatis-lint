import * as fs from 'fs';
import * as vscode from 'vscode';

import { runAnalyzer } from './analyzerRunner';
import type { AnalyzerRunOptions } from './analyzerRunner';
import { AnalyzerServer } from './analyzerServer';
import { CheckScheduler, isRelevantForCheck } from './checkScheduler';
import { groupDiagnosticsByUri, PlainDiagnosticSeverity } from './diagnosticsMapper';
import { insertColumnHints, lineEndHints } from './insertColumnHints';
import { findJavaMethod, findXmlIds, javaMethodAt, mapperNamespace, TARGET_TAGS, xmlReferenceAt } from './navigation';
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

  log(output, `Checking ${workspaceFolder.uri.fsPath} (${trigger === 'save' ? 'on save' : 'command'})`);
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
      () => analyze(checkContext, { javaPath, jarPath, workspacePath: workspaceFolder.uri.fsPath, excludes, disabledRules }),
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
