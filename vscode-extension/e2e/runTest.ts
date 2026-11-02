/**
 * Starts a real VS Code with the extension and runs the tests in `suite/` inside it, so
 * editor features (definitions, hover, completion, rename, CodeLens, SQL preview) are
 * checked the way users get them. On Linux without a display, run it under xvfb-run.
 */
import * as path from 'path';

import { runTests } from '@vscode/test-electron';

async function main(): Promise<void> {
  // out/e2e/runTest.js → the extension root and the source workspace.
  const extensionDevelopmentPath = path.resolve(__dirname, '../..');
  const workspace = path.resolve(extensionDevelopmentPath, 'e2e/workspace');
  await runTests({
    version: process.env.VSCODE_TEST_VERSION ?? 'stable',
    extensionDevelopmentPath,
    extensionTestsPath: path.resolve(__dirname, 'suite/index'),
    // Other installed extensions could answer the same requests and change the results.
    launchArgs: [workspace, '--disable-extensions', '--disable-workspace-trust'],
  });
}

main().catch((error: unknown) => {
  console.error(error);
  process.exit(1);
});
