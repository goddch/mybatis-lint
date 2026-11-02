/**
 * The HTML of the SQL preview panel. The page only draws what the extension
 * sends and reports the user's choices back; all SQL building happens in
 * `sqlPreview.ts`. Everything from the document is inserted with
 * `textContent`, never as HTML, so mapper contents cannot run as script.
 *
 * Layout, top to bottom: a header with the statement and the copy button, the
 * SQL (the result, so it comes first), the branches as a tree like the XML,
 * and the parameters as a table.
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
${STYLE}
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

const STYLE = String.raw`
:root {
  --border: var(--vscode-widget-border, var(--vscode-panel-border, rgba(128, 128, 128, 0.35)));
  --muted: var(--vscode-descriptionForeground);
  --mono: var(--vscode-editor-font-family);
  --accent: var(--vscode-focusBorder, #0078d4);
}
* { box-sizing: border-box; }
body { font-family: var(--vscode-font-family); font-size: var(--vscode-font-size); color: var(--vscode-foreground);
  margin: 0; padding: 0 0 24px; }
.empty { color: var(--muted); padding: 16px; }

/* Header: what is shown, and the copy button. */
header { position: sticky; top: 0; z-index: 1; display: flex; align-items: center; gap: 10px; padding: 10px 16px;
  background: var(--vscode-editor-background); border-bottom: 1px solid var(--border); }
.heading { flex: 1; min-width: 0; }
.statement { display: flex; align-items: center; gap: 8px; font-size: 1.15em; font-weight: 600; }
.statement code { font-family: var(--mono); }
.file { color: var(--muted); font-size: 0.9em; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 2px; }
.tag { font-family: var(--mono); font-size: 0.75em; font-weight: normal; padding: 1px 6px; border-radius: 3px;
  background: var(--vscode-badge-background); color: var(--vscode-badge-foreground); }
button { font: inherit; border: none; border-radius: 3px; padding: 5px 12px; cursor: pointer;
  background: var(--vscode-button-background); color: var(--vscode-button-foreground); white-space: nowrap; }
button:hover { background: var(--vscode-button-hoverBackground); }
button.secondary { background: var(--vscode-button-secondaryBackground, rgba(128, 128, 128, 0.2));
  color: var(--vscode-button-secondaryForeground, inherit); }
button:focus-visible, input:focus-visible { outline: 1px solid var(--accent); outline-offset: 1px; }

main { padding: 0 16px; }
section { margin-top: 18px; }
h3 { display: flex; align-items: center; gap: 6px; margin: 0 0 8px; font-size: 0.8em; font-weight: 600;
  letter-spacing: 0.06em; text-transform: uppercase; color: var(--muted); }
h3 .count { text-transform: none; letter-spacing: 0; font-weight: normal; }

/* The SQL, highlighted. */
pre.sql { margin: 0; padding: 12px 14px; border: 1px solid var(--border); border-radius: 4px;
  background: var(--vscode-textCodeBlock-background, rgba(128, 128, 128, 0.1)); white-space: pre-wrap; word-break: break-word;
  font-family: var(--mono); font-size: var(--vscode-editor-font-size); line-height: 1.6; }
.k { color: var(--vscode-symbolIcon-keywordForeground, #569cd6); font-weight: 600; }
.s { color: var(--vscode-debugTokenExpression-string, #ce9178); }
.n { color: var(--vscode-debugTokenExpression-number, #b5cea8); }
.p { color: var(--vscode-debugTokenExpression-name, #9cdcfe); background: rgba(128, 128, 128, 0.18); border-radius: 3px; padding: 0 2px; }
.d { color: var(--vscode-editorWarning-foreground, #cca700); background: rgba(204, 167, 0, 0.14); border-radius: 3px; padding: 0 2px; }
.c { color: var(--muted); font-style: italic; }

/* Branches: one row per branch, indented like the XML. */
.tree { border: 1px solid var(--border); border-radius: 4px; overflow: hidden; }
.branch { display: flex; align-items: flex-start; gap: 10px; padding: 8px 10px; border-top: 1px solid var(--border); }
.branch:first-child { border-top: none; }
.branch:hover { background: var(--vscode-list-hoverBackground); }
.branch .guide { flex: none; align-self: stretch; border-left: 2px solid var(--border); margin-left: 6px; }
.branch .body { flex: 1; min-width: 0; }
.condition { font-family: var(--mono); word-break: break-word; line-height: 1.5; }
.branch.off .condition { color: var(--muted); text-decoration: line-through; }
.kind { flex: none; font-family: var(--mono); font-size: 0.8em; color: var(--muted); padding-top: 3px; width: 5.6em; }
.auto { display: inline-block; margin-left: 8px; font-family: var(--vscode-font-family); font-size: 0.75em; line-height: 1.5;
  padding: 0 6px; border-radius: 8px; border: 1px solid var(--accent); color: var(--accent); white-space: nowrap; vertical-align: 1px; }

/* A switch for <if>. */
.switch { position: relative; flex: none; width: 30px; height: 16px; margin-top: 2px; }
.switch input { position: absolute; opacity: 0; width: 100%; height: 100%; margin: 0; cursor: pointer; }
.switch span { position: absolute; inset: 0; border-radius: 8px; background: rgba(128, 128, 128, 0.45); transition: background 0.1s; pointer-events: none; }
.switch span::after { content: ''; position: absolute; top: 2px; left: 2px; width: 12px; height: 12px; border-radius: 50%;
  background: #fff; transition: left 0.1s; }
.switch input:checked + span { background: var(--vscode-button-background); }
.switch input:checked + span::after { left: 16px; }
.switch input:disabled { cursor: default; }
.switch input:disabled + span { opacity: 0.6; }
.switch input:focus-visible + span { outline: 1px solid var(--accent); outline-offset: 2px; }

/* The branches of <choose>, one per line. */
.options { display: flex; flex-direction: column; gap: 4px; }
.option { display: flex; align-items: flex-start; gap: 6px; font-family: var(--mono); word-break: break-word; cursor: pointer; line-height: 1.5; }
.option input { margin: 3px 0 0; flex: none; }
.option { padding: 1px 6px; margin-left: -6px; border-radius: 3px; }
.option.chosen { background: rgba(128, 128, 128, 0.18); }
.option:not(.chosen) { color: var(--muted); }
.option .which { color: var(--muted); }

/* A stepper for <foreach>. */
.stepper { display: inline-flex; align-items: center; margin-left: 10px; vertical-align: middle; }
.stepper button { padding: 0 9px; height: 22px; border-radius: 0; }
.stepper button:first-child { border-radius: 3px 0 0 3px; }
.stepper button:last-child { border-radius: 0 3px 3px 0; }
.stepper output { min-width: 2.4em; height: 22px; line-height: 22px; text-align: center; font-family: var(--mono);
  background: var(--vscode-input-background); }
.times { color: var(--muted); margin-left: 6px; }

/* Parameters. */
.settings { display: flex; flex-direction: column; gap: 10px; margin-bottom: 12px; }
.setting { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.setting .label { color: var(--muted); }
.segmented { display: inline-flex; border: 1px solid var(--border); border-radius: 4px; overflow: hidden; }
.segmented button { border-radius: 0; background: transparent; color: var(--vscode-foreground); padding: 3px 12px; }
.segmented button + button { border-left: 1px solid var(--border); }
.segmented button:hover { background: var(--vscode-list-hoverBackground); }
.segmented button.selected { background: var(--vscode-button-background); color: var(--vscode-button-foreground); }
.hint { color: var(--muted); font-size: 0.9em; }
.check { display: flex; align-items: flex-start; gap: 8px; cursor: pointer; }
.check input { margin: 3px 0 0; }
table { width: 100%; border-collapse: separate; border-spacing: 0; border: 1px solid var(--border); border-radius: 4px; }
td { padding: 6px 8px; border-top: 1px solid var(--border); vertical-align: middle; }
tr:first-child td { border-top: none; }
td.name { width: 1%; white-space: nowrap; font-family: var(--mono); }
.badge { display: block; width: fit-content; margin-top: 2px; font-family: var(--vscode-font-family); font-size: 0.75em;
  padding: 0 5px; border-radius: 3px; color: var(--muted); border: 1px solid var(--border); }
input[type=text] { width: 100%; padding: 3px 6px; border-radius: 2px; font-family: var(--mono);
  background: var(--vscode-input-background); color: var(--vscode-input-foreground); border: 1px solid var(--vscode-input-border, var(--border)); }
.note { color: var(--muted); font-size: 0.85em; margin-top: 18px; line-height: 1.5; }
.copied { color: var(--muted); font-size: 0.9em; }
`;

const SCRIPT = String.raw`
const vscode = acquireVsCodeApi();
const root = document.getElementById('root');
let state = undefined;
let signature = '';

const KEYWORDS = new Set(('SELECT FROM WHERE AND OR NOT IN IS NULL AS ON JOIN LEFT RIGHT INNER OUTER FULL CROSS UNION ALL ' +
  'ORDER GROUP BY HAVING INSERT INTO VALUES UPDATE SET DELETE MERGE USING MATCHED WHEN THEN ELSE END CASE LIKE BETWEEN EXISTS ' +
  'DISTINCT ASC DESC WITH FETCH FIRST NEXT ROWS ONLY OFFSET LIMIT FOR NVL COALESCE COUNT SUM MAX MIN AVG TRUE FALSE').split(' '));

function element(tag, properties, children) {
  const node = document.createElement(tag);
  Object.assign(node, properties || {});
  for (const child of children || []) {
    if (child !== undefined && child !== null) {
      node.append(child);
    }
  }
  return node;
}

function send() {
  vscode.postMessage({ type: 'update', choices: state.choices, style: state.style, values: state.values, auto: state.auto });
}

/** The SQL as spans: keywords, strings, numbers, parameters and unresolved \${...} each get a class. */
function highlighted(sql) {
  const pre = element('pre', { className: 'sql', id: 'sql' });
  const pattern = /('(?:[^']|'')*')|(\$\{[^}]*\})|(:[A-Za-z_$][\w$]*|\?)|(\b\d+(?:\.\d+)?\b)|(--[^\n]*|\/\*[\s\S]*?\*\/)|([A-Za-z_][\w$]*)/g;
  let last = 0;
  for (const match of sql.matchAll(pattern)) {
    if (match.index > last) {
      pre.append(sql.slice(last, match.index));
    }
    const text = match[0];
    const className = match[1] ? 's' : match[2] ? 'd' : match[3] ? 'p' : match[4] ? 'n' : match[5] ? 'c'
      : KEYWORDS.has(text.toUpperCase()) ? 'k' : '';
    pre.append(className ? element('span', { className, textContent: text }) : text);
    last = match.index + text.length;
  }
  pre.append(sql.slice(last));
  return pre;
}

function autoBadge() {
  return element('span', { className: 'auto', textContent: 'auto', title: 'Decided from the values in Parameters' });
}

function ifRow(control) {
  const decided = control.decided !== undefined;
  const on = decided ? control.decided : state.choices.ifs[control.key] !== false;
  const input = element('input', { type: 'checkbox', checked: on, disabled: decided, title: on ? 'Included' : 'Left out' });
  input.dataset.focusKey = 'if:' + control.key;
  input.addEventListener('change', () => { state.choices.ifs[control.key] = input.checked; send(); });
  return { off: !on, children: [
    element('label', { className: 'switch' }, [input, element('span')]),
    element('div', { className: 'body' }, [element('div', { className: 'condition' }, [control.label, decided ? autoBadge() : undefined])]),
  ] };
}

function chooseRow(control) {
  const decided = control.decidedIndex !== undefined;
  const chosen = decided ? control.decidedIndex : (state.choices.chooses[control.key] || 0);
  const options = element('div', { className: 'options' });
  control.options.forEach((option, index) => {
    const otherwise = index === control.options.length - 1 && option === 'otherwise';
    const radio = element('input', { type: 'radio', name: 'choose-' + control.key, checked: index === chosen, disabled: decided });
    radio.dataset.focusKey = 'choose:' + control.key + ':' + index;
    radio.addEventListener('change', () => { state.choices.chooses[control.key] = index; send(); });
    options.append(element('label', { className: 'option' + (index === chosen ? ' chosen' : '') }, [
      radio, element('span', {}, [element('span', { className: 'which', textContent: otherwise ? 'otherwise' : 'when ' }), otherwise ? '' : option]),
      index === chosen && decided ? autoBadge() : undefined,
    ]));
  });
  if (control.decidedIndex === -1) {
    options.append(element('div', { className: 'hint' }, ['No branch matches, so nothing is added.', autoBadge()]));
  }
  return { off: false, children: [element('div', { className: 'body' }, [options])] };
}

function foreachRow(control) {
  const count = state.choices.foreachCounts[control.key] ?? 2;
  const set = (value) => { state.choices.foreachCounts[control.key] = Math.max(0, Math.min(20, value)); send(); };
  const minus = element('button', { className: 'secondary', textContent: '−', title: 'Fewer items', disabled: count <= 0 });
  const plus = element('button', { className: 'secondary', textContent: '+', title: 'More items', disabled: count >= 20 });
  minus.addEventListener('click', () => set(count - 1));
  plus.addEventListener('click', () => set(count + 1));
  minus.dataset.focusKey = 'foreach-minus:' + control.key;
  plus.dataset.focusKey = 'foreach-plus:' + control.key;
  return { off: false, children: [element('div', { className: 'body' }, [
    element('span', { className: 'condition', textContent: control.label }),
    element('span', { className: 'stepper' }, [minus, element('output', { textContent: String(count) }), plus]),
    element('span', { className: 'times', textContent: count === 1 ? 'item' : 'items' }),
  ])] };
}

function branches() {
  const tree = element('div', { className: 'tree' });
  for (const control of state.controls) {
    const row = control.kind === 'if' ? ifRow(control) : control.kind === 'choose' ? chooseRow(control) : foreachRow(control);
    // A guide line per level of nesting, like indentation guides in the editor.
    const guides = Array.from({ length: control.depth }, () => element('span', { className: 'guide' }));
    tree.append(element('div', { className: 'branch' + (row.off ? ' off' : '') },
      [...guides, element('span', { className: 'kind', textContent: '<' + control.kind + '>' }), ...row.children]));
  }
  return element('section', {}, [element('h3', {}, ['Branches', element('span', { className: 'count', textContent: String(state.controls.length) })]), tree]);
}

function showsInput(parameter) {
  return state.style === 'values' || state.auto || parameter.dollar;
}

function parameters() {
  const segmented = element('div', { className: 'segmented' });
  for (const [value, label, title] of [['named', ':name', 'Named parameters; DBeaver and similar clients ask for the values'],
    ['question', '?', 'JDBC placeholders'], ['values', 'Values', 'The values typed below']]) {
    const button = element('button', { textContent: label, title, className: state.style === value ? 'selected' : '' });
    button.dataset.focusKey = 'style:' + value;
    button.addEventListener('click', () => { state.style = value; send(); });
    segmented.append(button);
  }
  const auto = element('input', { type: 'checkbox', checked: state.auto });
  auto.dataset.focusKey = 'auto';
  auto.addEventListener('change', () => { state.auto = auto.checked; send(); });
  const settings = element('div', { className: 'settings' }, [
    element('div', { className: 'setting' }, [element('span', { className: 'label', textContent: 'Write parameters as' }), segmented]),
    element('label', { className: 'check' }, [auto, element('span', {}, ['Pick branches from values',
      element('div', { className: 'hint', textContent: 'Evaluates each test with the values below, as OGNL does.' })])]),
  ]);
  const rows = state.parameters.filter(showsInput).map((parameter) => {
    const input = element('input', { type: 'text', value: state.values[parameter.key] || '',
      placeholder: parameter.dollar ? 'e.g. o.ID DESC' : "'abc'   123   NULL   true" });
    input.dataset.focusKey = 'parameter:' + parameter.key;
    input.addEventListener('input', () => { state.values[parameter.key] = input.value; send(); });
    const badge = parameter.dollar ? 'inserted as is' : parameter.testOnly ? 'test only' : undefined;
    return element('tr', {}, [
      element('td', { className: 'name' }, [(parameter.dollar ? '$' : ':') + parameter.key, badge ? element('span', { className: 'badge', textContent: badge }) : undefined]),
      element('td', {}, [input]),
    ]);
  });
  const table = rows.length > 0 ? element('table', {}, [element('tbody', {}, rows)])
    : element('div', { className: 'hint', textContent: 'Choose Values, or Pick branches from values, to type values.' });
  return element('section', {}, [element('h3', {}, ['Parameters']), settings, table]);
}

function draw() {
  const copied = element('span', { className: 'copied' });
  const copy = element('button', { textContent: 'Copy SQL' });
  copy.addEventListener('click', () => { vscode.postMessage({ type: 'copy' }); copied.textContent = 'Copied'; setTimeout(() => { copied.textContent = ''; }, 1500); });
  const header = element('header', {}, [
    element('div', { className: 'heading' }, [
      element('div', { className: 'statement' }, [element('span', { className: 'tag', textContent: state.tag }), element('code', { textContent: state.id })]),
      element('div', { className: 'file', textContent: state.file, title: state.file }),
    ]),
    copied, copy,
  ]);
  const main = element('main', {}, [
    element('section', {}, [highlighted(state.sql)]),
    state.controls.length > 0 ? branches() : undefined,
    parameters(),
    element('p', { className: 'note', textContent: 'Assembled as MyBatis would. Choose the branches, or let the typed values decide them; <bind> variables stay as parameters.' }),
  ]);
  root.replaceChildren(header, main);
}

/** Redraws, keeping the focus and caret in the input the user is using. */
function redraw() {
  const active = document.activeElement;
  const focusKey = active && active.dataset ? active.dataset.focusKey : undefined;
  const selection = focusKey && active.type === 'text' ? [active.selectionStart, active.selectionEnd] : undefined;
  draw();
  if (focusKey) {
    const input = root.querySelector('[data-focus-key="' + CSS.escape(focusKey) + '"]');
    if (input) {
      input.focus();
      if (selection) {
        input.setSelectionRange(selection[0], selection[1]);
      }
    }
  }
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
  // While the user types a value, only the SQL changes; redrawing everything is not needed.
  const inputs = state.parameters.filter(showsInput);
  const next = JSON.stringify([state.tag, state.id, state.file, state.style, state.auto, state.controls, state.choices, inputs]);
  if (next === signature && document.getElementById('sql')) {
    document.getElementById('sql').replaceWith(highlighted(state.sql));
  } else {
    signature = next;
    redraw();
  }
});

vscode.postMessage({ type: 'ready' });
`;
