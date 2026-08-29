import * as fs from 'fs';
import * as vscode from 'vscode';

import { runAnalyzer } from './analyzerRunner';
import { groupDiagnosticsByUri, PlainDiagnosticSeverity } from './diagnosticsMapper';
import { resolveJavaPath } from './javaPathResolver';
import { parseAnalyzerOutput } from './parseAnalyzerOutput';
import { resolveAnalyzerJarPath } from './jarPathResolver';

const COMMAND_ID = 'mybatisLint.checkWorkspace';

export function activate(context: vscode.ExtensionContext): void {
  const diagnosticCollection = vscode.languages.createDiagnosticCollection('mybatisLint');
  context.subscriptions.push(diagnosticCollection);

  const disposable = vscode.commands.registerCommand(COMMAND_ID, () =>
    checkWorkspace(context, diagnosticCollection),
  );
  context.subscriptions.push(disposable);
}

export function deactivate(): void {
  // No background resources to clean up beyond what's registered via
  // context.subscriptions (disposed automatically by VS Code).
}

async function checkWorkspace(
  context: vscode.ExtensionContext,
  diagnosticCollection: vscode.DiagnosticCollection,
): Promise<void> {
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
  );
  const jarPath = resolveAnalyzerJarPath(config.get<string>('analyzerJarPath'), context.extensionPath);

  if (!fs.existsSync(jarPath)) {
    void vscode.window.showErrorMessage(
      `MyBatis Lint: analyzer jar not found at "${jarPath}". ` +
        'Build it with "./gradlew jar" under analyzer/, then set "mybatisLint.analyzerJarPath".',
    );
    return;
  }

  // Clear stale diagnostics up front so a failed/aborted run never leaves
  // outdated squiggles behind.
  diagnosticCollection.clear();

  try {
    const { stdout } = await runAnalyzer({ javaPath, jarPath, workspacePath: workspaceFolder.uri.fsPath });
    const result = parseAnalyzerOutput(stdout);
    const grouped = groupDiagnosticsByUri(result.diagnostics);

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

    void vscode.window.showInformationMessage(
      `MyBatis Lint: checked workspace, found ${result.summary.errorCount} error(s) and ` +
        `${result.summary.warningCount} warning(s) across ${result.summary.fileCount} file(s).`,
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    void vscode.window.showErrorMessage(message);
  }
}
