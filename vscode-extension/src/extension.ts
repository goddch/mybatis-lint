import * as fs from 'fs';
import * as vscode from 'vscode';

import { runAnalyzer } from './analyzerRunner';
import { CheckScheduler, isRelevantForCheck } from './checkScheduler';
import { groupDiagnosticsByUri, PlainDiagnosticSeverity } from './diagnosticsMapper';
import { bundledJavaPath, resolveJavaPath } from './javaPathResolver';
import { parseAnalyzerOutput } from './parseAnalyzerOutput';
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
  /** Last error shown for a save-triggered run, so the same failure is not shown on every save. */
  lastSaveError?: string;
}

export function activate(context: vscode.ExtensionContext): void {
  const diagnosticCollection = vscode.languages.createDiagnosticCollection('mybatisLint');
  const output = vscode.window.createOutputChannel('MyBatis Lint');
  context.subscriptions.push(diagnosticCollection, output);

  const checkContext: CheckContext = { extensionPath: context.extensionPath, diagnosticCollection, output };
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
  const configuredExcludes = config.get<unknown>('exclude');
  const excludes = Array.isArray(configuredExcludes)
    ? configuredExcludes.filter((value): value is string => typeof value === 'string')
    : [];

  log(output, `Checking ${workspaceFolder.uri.fsPath} (${trigger === 'save' ? 'on save' : 'command'})`);
  log(output, `  Java: ${javaPath}`);
  log(output, `  Analyzer: ${jarPath}`);
  if (excludes.length > 0) {
    log(output, `  Excluded: ${excludes.join(', ')}`);
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
      () => runAnalyzer({ javaPath, jarPath, workspacePath: workspaceFolder.uri.fsPath, excludes }),
    );
    for (const line of stderr.split(/\r?\n/).filter((text) => text.trim().length > 0)) {
      log(output, `  ${line}`);
    }
    const result = parseAnalyzerOutput(stdout);
    const grouped = groupDiagnosticsByUri(result.diagnostics);

    // Replace everything at once, after the new results are ready, so the
    // Problems panel does not flicker empty on every save.
    diagnosticCollection.clear();
    for (const { uri, diagnostics } of grouped) {
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
        return vscodeDiagnostic;
      });
      diagnosticCollection.set(vscode.Uri.parse(uri), vscodeDiagnostics);
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
    reportFailure(checkContext, trigger, error instanceof Error ? error.message : String(error));
  }
}
