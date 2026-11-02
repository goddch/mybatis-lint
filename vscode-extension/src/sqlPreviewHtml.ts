/**
 * The HTML of the SQL preview panel. The page only draws what the extension
 * sends and reports the user's choices back; all SQL building happens in
 * `sqlPreview.ts`. Everything from the document is inserted with
 * `textContent`, never as HTML, so mapper contents cannot run as script.
 */

/** @param nonce a random value allowing only this page's own style and script to run */
export function sqlPreviewHtml(nonce: string, cspSource: string): string {
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy"
  content="default-src 'none'; style-src ${cspSource} 'nonce-${nonce}'; script-src 'nonce-${nonce}';">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style nonce="${nonce}">
  body { font-family: var(--vscode-font-family); font-size: var(--vscode-font-size); color: var(--vscode-foreground); padding: 0 12px 16px; }
  h2 { font-size: 1.1em; margin: 12px 0 4px; }
  .file { color: var(--vscode-descriptionForeground); margin-bottom: 8px; }
  .empty { color: var(--vscode-descriptionForeground); margin-top: 16px; }
  fieldset { border: 1px solid var(--vscode-panel-border); margin: 8px 0; padding: 6px 10px; }
  legend { color: var(--vscode-descriptionForeground); }
  .control { display: flex; align-items: center; gap: 6px; margin: 3px 0; font-family: var(--vscode-editor-font-family); }
  .control code { white-space: pre-wrap; word-break: break-all; }
  .kind { color: var(--vscode-descriptionForeground); min-width: 4.5em; }
  select, input[type=text], input[type=number] { background: var(--vscode-input-background); color: var(--vscode-input-foreground);
    border: 1px solid var(--vscode-input-border, transparent); font-family: var(--vscode-editor-font-family); }
  input[type=number] { width: 4em; }
  input[type=text] { flex: 1; min-width: 8em; }
  .parameter { display: flex; align-items: center; gap: 6px; margin: 3px 0; }
  .parameter label { min-width: 10em; font-family: var(--vscode-editor-font-family); }
  .toolbar { display: flex; align-items: center; gap: 12px; margin: 10px 0 6px; }
  button { background: var(--vscode-button-background); color: var(--vscode-button-foreground); border: none; padding: 4px 12px; cursor: pointer; }
  button:hover { background: var(--vscode-button-hoverBackground); }
  .copied { color: var(--vscode-descriptionForeground); }
  pre { background: var(--vscode-textCodeBlock-background); padding: 10px; white-space: pre-wrap; word-break: break-word;
    font-family: var(--vscode-editor-font-family); font-size: var(--vscode-editor-font-size); }
  .note { color: var(--vscode-descriptionForeground); font-size: 0.9em; }
</style>
</head>
<body>
<div id="root"><p class="empty">Put the cursor in a &lt;select&gt;, &lt;insert&gt;, &lt;update&gt; or &lt;delete&gt; of a mapper XML file.</p></div>
<script nonce="${nonce}">
${SCRIPT}
</script>
</body>
</html>`;
}

const SCRIPT = String.raw`
const vscode = acquireVsCodeApi();
const root = document.getElementById('root');
let state = undefined;
let signature = '';

function element(tag, properties, children) {
  const node = document.createElement(tag);
  Object.assign(node, properties || {});
  for (const child of children || []) {
    node.append(child);
  }
  return node;
}

function send() {
  vscode.postMessage({ type: 'update', choices: state.choices, style: state.style, values: state.values });
}

function controlRow(control) {
  const row = element('div', { className: 'control' });
  row.style.paddingLeft = (control.depth * 16) + 'px';
  if (control.kind === 'if') {
    const box = element('input', { type: 'checkbox', checked: state.choices.ifs[control.key] !== false });
    box.addEventListener('change', () => { state.choices.ifs[control.key] = box.checked; send(); });
    row.append(element('span', { className: 'kind', textContent: '<if>' }), box, element('code', { textContent: control.label }));
  } else if (control.kind === 'choose') {
    const select = element('select');
    control.options.forEach((option, index) => select.append(element('option', { value: String(index), textContent: index === control.options.length - 1 && option === 'otherwise' ? '<otherwise>' : '<when> ' + option })));
    select.value = String(state.choices.chooses[control.key] || 0);
    select.addEventListener('change', () => { state.choices.chooses[control.key] = Number(select.value); send(); });
    row.append(element('span', { className: 'kind', textContent: '<choose>' }), select);
  } else {
    const count = element('input', { type: 'number', min: '0', max: '20', value: String(state.choices.foreachCounts[control.key] ?? 2) });
    count.addEventListener('input', () => { state.choices.foreachCounts[control.key] = Math.max(0, Math.min(20, Number(count.value) || 0)); send(); });
    row.append(element('span', { className: 'kind', textContent: '<foreach>' }), element('code', { textContent: control.label }), count, element('span', { textContent: 'times' }));
  }
  return row;
}

function styleRow() {
  const row = element('div', { className: 'control' });
  row.append(element('span', { className: 'kind', textContent: 'Values' }));
  for (const [value, label] of [['named', ':name (DBeaver asks for values)'], ['question', '?'], ['values', 'typed values']]) {
    const radio = element('input', { type: 'radio', name: 'style', value, checked: state.style === value });
    radio.addEventListener('change', () => { state.style = value; send(); });
    row.append(element('label', {}, [radio, ' ' + label]));
  }
  return row;
}

function parameterRows() {
  const rows = [];
  for (const parameter of state.parameters) {
    if (state.style !== 'values' && !parameter.dollar) {
      continue;
    }
    const input = element('input', { type: 'text', value: state.values[parameter.key] || '',
      placeholder: parameter.dollar ? 'pasted into the SQL as is, e.g. u.ID DESC' : "e.g. 'abc', 123, DATE '2026-01-31'" });
    input.addEventListener('input', () => { state.values[parameter.key] = input.value; send(); });
    rows.push(element('div', { className: 'parameter' }, [element('label', { textContent: (parameter.dollar ? '$' : ':') + parameter.key }), input]));
  }
  return rows;
}

function draw() {
  root.replaceChildren(
    element('h2', { textContent: state.title }),
    element('div', { className: 'file', textContent: state.file }),
  );
  if (state.controls.length > 0) {
    root.append(element('fieldset', {}, [element('legend', { textContent: 'Branches' }), ...state.controls.map(controlRow)]));
  }
  const parameters = parameterRows();
  root.append(element('fieldset', {}, [element('legend', { textContent: 'Parameters' }), styleRow(), ...parameters]));
  const copied = element('span', { className: 'copied' });
  const copy = element('button', { textContent: 'Copy SQL' });
  copy.addEventListener('click', () => { vscode.postMessage({ type: 'copy' }); copied.textContent = 'Copied'; setTimeout(() => { copied.textContent = ''; }, 1500); });
  root.append(element('div', { className: 'toolbar' }, [copy, copied]));
  root.append(element('pre', { id: 'sql', textContent: state.sql }));
  root.append(element('p', { className: 'note', textContent: 'Conditions are not evaluated: choose the branches above. Generated as MyBatis would assemble it; <bind> variables are left as parameters.' }));
}

window.addEventListener('message', (event) => {
  const message = event.data;
  if (message.type !== 'render') {
    return;
  }
  if (message.statement === undefined) {
    state = undefined;
    signature = '';
    root.replaceChildren(element('p', { className: 'empty', textContent: message.reason }));
    return;
  }
  state = message.statement;
  // While the user types a value or a count, only the SQL changes; redrawing the controls
  // would take the focus away from the input.
  const inputs = state.parameters.filter((parameter) => state.style === 'values' || parameter.dollar);
  const next = JSON.stringify([state.title, state.file, state.style, state.controls, inputs]);
  if (next === signature && document.getElementById('sql')) {
    document.getElementById('sql').textContent = state.sql;
  } else {
    signature = next;
    draw();
  }
});

vscode.postMessage({ type: 'ready' });
`;
