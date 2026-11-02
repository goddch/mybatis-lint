/**
 * Tests that run inside VS Code's extension host (started by `runTest.ts`). A small runner is
 * used instead of a test framework, to avoid another dependency: each test is a function, and
 * the run fails if any of them throws.
 */
import assert from 'node:assert/strict';

import * as vscode from 'vscode';

const XML = 'src/main/resources/com/example/UserMapper.xml';
const JAVA = 'src/main/java/com/example/UserMapper.java';

async function open(relativePath: string): Promise<vscode.TextDocument> {
  const folder = vscode.workspace.workspaceFolders?.[0];
  assert.ok(folder, 'the test workspace is open');
  return vscode.workspace.openTextDocument(vscode.Uri.joinPath(folder.uri, relativePath));
}

/** The position `delta` characters into the first occurrence of `needle`. */
function positionOf(document: vscode.TextDocument, needle: string, delta = 1): vscode.Position {
  const index = document.getText().indexOf(needle);
  assert.ok(index >= 0, `${needle} is in ${document.fileName}`);
  return document.positionAt(index + delta);
}

function hoverText(hovers: vscode.Hover[]): string {
  return hovers
    .flatMap((hover) => hover.contents)
    .map((content) => (typeof content === 'string' ? content : 'value' in content ? content.value : ''))
    .join('\n');
}

const tests: Array<[string, () => Promise<void>]> = [
  [
    'jumps from a statement id to the Java mapper method and back',
    async () => {
      const xml = await open(XML);
      const toJava = await vscode.commands.executeCommand<vscode.Location[]>(
        'vscode.executeDefinitionProvider',
        xml.uri,
        positionOf(xml, 'id="findById"', 5),
      );
      assert.ok(toJava.some((location) => location.uri.fsPath.endsWith('UserMapper.java')), JSON.stringify(toJava));

      const java = await open(JAVA);
      const toXml = await vscode.commands.executeCommand<vscode.Location[]>(
        'vscode.executeDefinitionProvider',
        java.uri,
        positionOf(java, 'findById(', 2),
      );
      assert.ok(toXml.some((location) => location.uri.fsPath.endsWith('UserMapper.xml')), JSON.stringify(toXml));
    },
  ],
  [
    'describes a parameter on hover',
    async () => {
      const xml = await open(XML);
      const hovers = await vscode.commands.executeCommand<vscode.Hover[]>(
        'vscode.executeHoverProvider',
        xml.uri,
        positionOf(xml, '#{userId}', 4),
      );
      assert.match(hoverText(hovers), /Long userId/);
    },
  ],
  [
    'completes parameter names inside #{',
    async () => {
      const xml = await open(XML);
      // Right after "#{" of #{name}, every name the statement can use is offered.
      const list = await vscode.commands.executeCommand<vscode.CompletionList>(
        'vscode.executeCompletionItemProvider',
        xml.uri,
        positionOf(xml, '#{name}', 2),
      );
      const labels = list.items.map((item) => (typeof item.label === 'string' ? item.label : item.label.label));
      assert.ok(labels.includes('name') && labels.includes('ids'), labels.join(', '));
    },
  ],
  [
    'finds references to a <sql> fragment',
    async () => {
      const xml = await open(XML);
      const references = await vscode.commands.executeCommand<vscode.Location[]>(
        'vscode.executeReferenceProvider',
        xml.uri,
        positionOf(xml, 'id="columns"', 5),
      );
      // The definition and the two <include refid="columns">.
      assert.equal(references.length, 3, JSON.stringify(references));
    },
  ],
  [
    'renames a resultMap id together with its references',
    async () => {
      const xml = await open(XML);
      const edit = await vscode.commands.executeCommand<vscode.WorkspaceEdit>(
        'vscode.executeDocumentRenameProvider',
        xml.uri,
        positionOf(xml, 'id="userMap"', 5),
        'userResult',
      );
      const edits = edit.get(xml.uri);
      assert.equal(edits.length, 2);
      assert.ok(edits.every((textEdit) => textEdit.newText === 'userResult'));
      assert.deepEqual(
        edits.map((textEdit) => xml.getText(textEdit.range)),
        ['userMap', 'userMap'],
      );
    },
  ],
  [
    'shows reference counts and Java links in CodeLens',
    async () => {
      const xml = await open(XML);
      const lenses = await vscode.commands.executeCommand<vscode.CodeLens[]>('vscode.executeCodeLensProvider', xml.uri);
      const titles = lenses.map((lens) => lens.command?.title ?? '');
      assert.ok(titles.includes('2 references'), titles.join(' | '));
      assert.ok(titles.includes('1 reference'), titles.join(' | '));
      assert.ok(titles.includes('Java: UserMapper.findById'), titles.join(' | '));
    },
  ],
  [
    'opens the SQL preview for the statement under the cursor',
    async () => {
      const xml = await open(XML);
      const editor = await vscode.window.showTextDocument(xml);
      const position = positionOf(xml, '<where>', 2);
      editor.selection = new vscode.Selection(position, position);
      const sql = await vscode.commands.executeCommand<string>('mybatisLint.showSqlPreview');
      assert.equal(
        sql,
        `SELECT u.USER_ID AS userId, u.USER_NAME AS userName
FROM users u
WHERE u.USER_NAME = :name
  AND u.USER_ID IN
  (:ids_0, :ids_1)`,
      );
      const tabs = vscode.window.tabGroups.all.flatMap((group) => group.tabs).map((tab) => tab.label);
      assert.ok(tabs.includes('SQL Preview: search'), tabs.join(', '));
    },
  ],
];

export async function run(): Promise<void> {
  const extension = vscode.extensions.all.find((candidate) => candidate.packageJSON?.name === 'mybatis-lint');
  assert.ok(extension, 'the extension is installed');
  await extension.activate();
  const failures: string[] = [];
  for (const [name, body] of tests) {
    try {
      await body();
      console.log(`ok - ${name}`);
    } catch (error) {
      console.error(`not ok - ${name}\n${error instanceof Error ? error.stack ?? error.message : String(error)}`);
      failures.push(name);
    }
  }
  if (failures.length > 0) {
    throw new Error(`${failures.length} of ${tests.length} end-to-end tests failed: ${failures.join('; ')}`);
  }
}
