/**
 * Builds the plain SQL a mapper statement produces for a chosen set of
 * branches, the way MyBatis assembles it: `<if>` on or off, one branch of
 * `<choose>`, `<foreach>` repeated a chosen number of times, `<where>` /
 * `<set>` / `<trim>` removing leading AND/OR and stray commas, and `<include>`
 * expanded (from other files too). Test conditions are not evaluated; the
 * user picks the branches.
 *
 * Parameters can be written for running the SQL in a SQL client such as
 * DBeaver: as named parameters (`:name`, which DBeaver asks values for), as
 * `?`, or with values the user typed in.
 */

import { childElements } from './mapperXml';
import { evaluateTest, valueFromSql } from './ognlLite';
import type { OgnlValue } from './ognlLite';
import type { XmlElement, XmlNode } from './mapperXml';

/** A branch the user can switch: identified by its position, so choices survive edits elsewhere. */
export interface PreviewControl {
  key: string;
  kind: 'if' | 'choose' | 'foreach';
  /** The `test` of an `<if>`, or the `collection` of a `<foreach>`. */
  label: string;
  /** For `<choose>`: the `test` of each `<when>`, then "otherwise" if there is one. */
  options?: string[];
  /** How deep the branch is nested in other branches, for indenting the controls. */
  depth: number;
  /** When branches are picked from values: the `<if>` result, if the test could be decided. */
  decided?: boolean;
  /** When branches are picked from values: the chosen branch of `<choose>` (-1: none), if decided. */
  decidedIndex?: number;
}

export interface PreviewChoices {
  /** `<if>` on (default) or off. */
  ifs?: Record<string, boolean>;
  /** Index of the chosen `<when>` / `<otherwise>` (default 0). */
  chooses?: Record<string, number>;
  /** Times a `<foreach>` repeats (default 2, enough to show the separator). */
  foreachCounts?: Record<string, number>;
}

/** `named`: `:name`; `question`: `?`; `values`: the typed value, or `:name` when there is none. */
export type ParameterStyle = 'named' | 'question' | 'values';

export interface PreviewParameter {
  /** The name used for `:name` and for typed values, such as `user_name` or `ids_0`. */
  key: string;
  /** `${...}` is pasted into the SQL as is, so it needs a value to run. */
  dollar: boolean;
  /** Only used in `test` conditions, not written into the SQL. */
  testOnly?: boolean;
}

export interface PreviewResult {
  sql: string;
  controls: PreviewControl[];
  parameters: PreviewParameter[];
}

/** Finds a `<sql>` fragment by namespace and id, for `<include>`. */
export type FragmentLookup = (namespace: string, id: string) => XmlElement | undefined;

const WHERE_OVERRIDES = ['AND ', 'OR ', 'AND\n', 'OR\n', 'AND\r', 'OR\r', 'AND\t', 'OR\t'];
const DEFAULT_FOREACH_COUNT = 2;

interface ForeachScope {
  item: string;
  index: string;
  collection: string;
  iteration: number;
}

interface RenderContext {
  namespace: string;
  choices: PreviewChoices;
  style: ParameterStyle;
  values: Record<string, string>;
  fragments: FragmentLookup;
  controls: PreviewControl[];
  parameters: Map<string, PreviewParameter>;
  foreach: ForeachScope[];
  /** `<property>` values of the `<include>`s being expanded, for `${...}` in fragments. */
  properties: Record<string, string>;
  /** Fragments being expanded, to stop an include that includes itself. */
  including: string[];
  /** Decide `<if>` / `<choose>` from the typed values where the test can be evaluated. */
  auto: boolean;
  /** Collection names of the statement's `<foreach>` and how many times each repeats. */
  collectionSizes: Map<string, number>;
}

export function previewStatement(
  statement: XmlElement,
  namespace: string,
  fragments: FragmentLookup,
  choices: PreviewChoices = {},
  style: ParameterStyle = 'named',
  values: Record<string, string> = {},
  auto = false,
): PreviewResult {
  const context: RenderContext = {
    namespace,
    choices,
    style,
    values,
    fragments,
    controls: [],
    parameters: new Map(),
    foreach: [],
    properties: {},
    including: [],
    auto,
    collectionSizes: collectionSizes(statement, '', choices),
  };
  const raw = renderChildren(statement, '', 0, context);
  return { sql: tidy(raw), controls: context.controls, parameters: [...context.parameters.values()] };
}

function renderChildren(element: XmlElement, keyPrefix: string, depth: number, context: RenderContext): string {
  return element.children.map((child, index) => renderNode(child, `${keyPrefix}${index}`, depth, context)).join('');
}

function renderNode(node: XmlNode, key: string, depth: number, context: RenderContext): string {
  if (node.kind === 'text') {
    return renderText(node.text, context);
  }
  const attribute = (name: string) => node.attributes.get(name) ?? '';
  switch (node.name) {
    case 'if': {
      const decided = context.auto ? decide(attribute('test'), context) : undefined;
      context.controls.push({ key, kind: 'if', label: attribute('test'), depth, ...(decided !== undefined ? { decided } : {}) });
      const enabled = decided ?? context.choices.ifs?.[key] !== false;
      return enabled ? renderChildren(node, `${key}.`, depth + 1, context) : '';
    }
    case 'choose': {
      const branches = childElements(node).filter((branch) => branch.name === 'when' || branch.name === 'otherwise');
      const decidedIndex = context.auto ? decideBranch(branches, context) : undefined;
      context.controls.push({
        key,
        kind: 'choose',
        label: 'choose',
        options: branches.map((branch) => (branch.name === 'when' ? branch.attributes.get('test') ?? '' : 'otherwise')),
        depth,
        ...(decidedIndex !== undefined ? { decidedIndex } : {}),
      });
      const index = decidedIndex ?? Math.min(context.choices.chooses?.[key] ?? 0, branches.length - 1);
      const chosen = branches[index];
      return chosen ? renderChildren(chosen, `${key}.${node.children.indexOf(chosen)}.`, depth + 1, context) : '';
    }
    case 'foreach':
      return renderForeach(node, key, depth, context);
    case 'where':
      return trim(renderChildren(node, `${key}.`, depth, context), 'WHERE', WHERE_OVERRIDES, '', []);
    case 'set':
      return trim(renderChildren(node, `${key}.`, depth, context), 'SET', [','], '', [',']);
    case 'trim':
      return trim(
        renderChildren(node, `${key}.`, depth, context),
        attribute('prefix'),
        overrides(attribute('prefixOverrides')),
        attribute('suffix'),
        overrides(attribute('suffixOverrides')),
      );
    case 'include':
      return renderInclude(node, key, depth, context);
    case 'bind':
    case 'selectKey':
      // <bind> only defines a variable; <selectKey> runs as a separate statement.
      return '';
    default:
      return renderChildren(node, `${key}.`, depth, context);
  }
}

/**
 * The `<if>` result from the typed values, or undefined to keep the user's choice. Names used in
 * the test are offered as parameters, so the user can type their values.
 */
function decide(test: string, context: RenderContext): boolean | undefined {
  for (const name of testNames(test)) {
    const key = parameterKey(name);
    if (!context.parameters.has(key) && !context.collectionSizes.has(name)) {
      context.parameters.set(key, { key, dollar: false, testOnly: true });
    }
  }
  return evaluateTest(test, (path) => lookup(path, context));
}

/** The first `<when>` whose test is true, the `<otherwise>` if none is (-1 without one), or undefined. */
function decideBranch(branches: XmlElement[], context: RenderContext): number | undefined {
  for (let i = 0; i < branches.length; i++) {
    if (branches[i].name === 'otherwise') {
      return i;
    }
    const result = decide(branches[i].attributes.get('test') ?? '', context);
    if (result === undefined) {
      return undefined;
    }
    if (result) {
      return i;
    }
  }
  return -1;
}

/** A value for a name in a test: what the user typed, or the size chosen for a `<foreach>` collection. */
function lookup(path: string, context: RenderContext): OgnlValue | undefined {
  // Inside <foreach>, the item and index have no typed value.
  if (context.foreach.some((scope) => [scope.item, scope.index].includes(path.split('.')[0]))) {
    return undefined;
  }
  const typed = valueFromSql(context.values[parameterKey(path)] ?? '');
  if (typed !== undefined) {
    return typed;
  }
  const size = context.collectionSizes.get(path);
  return size === undefined ? undefined : { list: size };
}

const TEST_KEYWORDS = new Set(['and', 'or', 'not', 'eq', 'neq', 'lt', 'gt', 'lte', 'gte', 'null', 'true', 'false']);

/** Names a test reads: `user.name` in `user.name != null`, `ids` in `ids.size() > 0`. */
function testNames(test: string): string[] {
  const withoutStrings = test.replace(/'(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*"/g, ' ');
  const names = new Set<string>();
  for (const match of withoutStrings.matchAll(/[A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*(\s*\()?/g)) {
    let name = match[0].replace(/\s+/g, '');
    if (match[1]) {
      // A call such as ids.size(): the name is what it is called on.
      name = name.slice(0, name.lastIndexOf('.'));
    }
    if (name.length > 0 && !name.includes('(') && !TEST_KEYWORDS.has(name)) {
      names.add(name);
    }
  }
  return [...names];
}

/** Each `<foreach>` collection and its repeat count, so tests such as `ids.size() > 0` can use it. */
function collectionSizes(element: XmlElement, keyPrefix: string, choices: PreviewChoices): Map<string, number> {
  const sizes = new Map<string, number>();
  element.children.forEach((child, index) => {
    if (child.kind !== 'element') {
      return;
    }
    const key = `${keyPrefix}${index}`;
    if (child.name === 'foreach') {
      const collection = child.attributes.get('collection') ?? '';
      sizes.set(collection, Math.max(0, choices.foreachCounts?.[key] ?? DEFAULT_FOREACH_COUNT));
    }
    for (const [name, size] of collectionSizes(child, `${key}.`, choices)) {
      sizes.set(name, size);
    }
  });
  return sizes;
}

/** The name used for `:name` and typed values: `user.name` → `user_name`, `ids[0]` → `ids_0`. */
function parameterKey(name: string): string {
  return name.replace(/\[(\d+)\]/g, '_$1').replace(/[^\w$]+/g, '_').replace(/^_+|_+$/g, '');
}

function renderForeach(node: XmlElement, key: string, depth: number, context: RenderContext): string {
  const collection = node.attributes.get('collection') ?? '';
  context.controls.push({ key, kind: 'foreach', label: collection, depth });
  const count = Math.max(0, context.choices.foreachCounts?.[key] ?? DEFAULT_FOREACH_COUNT);
  const items: string[] = [];
  for (let iteration = 0; iteration < count; iteration++) {
    context.foreach.push({
      item: node.attributes.get('item') ?? '',
      index: node.attributes.get('index') ?? '',
      collection,
      iteration,
    });
    items.push(renderChildren(node, `${key}.`, depth + 1, context));
    context.foreach.pop();
  }
  // Like MyBatis, open and close are written even when the collection is empty.
  const body = items.join(node.attributes.get('separator') ?? '');
  return ` ${node.attributes.get('open') ?? ''}${body}${node.attributes.get('close') ?? ''} `;
}

function renderInclude(node: XmlElement, key: string, depth: number, context: RenderContext): string {
  const refid = substituteProperties(node.attributes.get('refid') ?? '', context.properties);
  const dot = refid.lastIndexOf('.');
  const namespace = dot < 0 ? context.namespace : refid.slice(0, dot);
  const id = dot < 0 ? refid : refid.slice(dot + 1);
  const qualified = `${namespace}.${id}`;
  const fragment = context.including.includes(qualified) ? undefined : context.fragments(namespace, id);
  if (!fragment) {
    return ` /* <include refid="${refid}"> not found */ `;
  }
  const properties = { ...context.properties };
  for (const property of childElements(node).filter((child) => child.name === 'property')) {
    properties[property.attributes.get('name') ?? ''] = substituteProperties(property.attributes.get('value') ?? '', context.properties);
  }
  const inner: RenderContext = { ...context, namespace, properties, including: [...context.including, qualified] };
  return renderChildren(fragment, `${key}>`, depth, inner);
}

/** MyBatis replaces `${name}` in an included fragment with the include's `<property>` values. */
function substituteProperties(text: string, properties: Record<string, string>): string {
  return text.replace(/\$\{([^}]*)\}/g, (match, name: string) => properties[name.trim()] ?? match);
}

/** TrimSqlNode: drop one matching prefix / suffix override, then wrap non-empty content. */
function trim(content: string, prefix: string, prefixOverrides: string[], suffix: string, suffixOverrides: string[]): string {
  let trimmed = content.trim();
  if (trimmed.length === 0) {
    return ' ';
  }
  const upper = trimmed.toUpperCase();
  const leading = prefixOverrides.find((override) => upper.startsWith(override.toUpperCase()));
  if (leading) {
    trimmed = trimmed.slice(leading.length);
  }
  const trailing = suffixOverrides.find((override) => trimmed.toUpperCase().endsWith(override.toUpperCase()));
  if (trailing) {
    trimmed = trimmed.slice(0, trimmed.length - trailing.length);
  }
  return ` ${prefix ? `${prefix} ` : ''}${trimmed}${suffix ? ` ${suffix}` : ''} `;
}

function overrides(attribute: string): string[] {
  return attribute.length === 0 ? [] : attribute.split('|');
}

/** Replaces `#{...}` / `${...}` in SQL text according to the parameter style. */
function renderText(text: string, context: RenderContext): string {
  const withProperties = context.including.length > 0 ? substituteProperties(text, context.properties) : text;
  return withProperties.replace(/([#$])\{([^}]*)\}/g, (match, sign: string, content: string) => {
    const expression = content.split(',')[0].split(':')[0].trim();
    const resolved = resolveForeachNames(expression, context.foreach);
    if (resolved.literal !== undefined) {
      return resolved.literal;
    }
    const key = parameterKey(resolved.name);
    const dollar = sign === '$';
    if (!context.parameters.has(key) || context.parameters.get(key)?.testOnly) {
      context.parameters.set(key, { key, dollar });
    }
    const value = context.values[key];
    if (dollar) {
      return value !== undefined && value !== '' ? value : match;
    }
    if (context.style === 'question') {
      return '?';
    }
    if (context.style === 'values' && value !== undefined && value !== '') {
      return value;
    }
    return `:${key}`;
  });
}

/**
 * `#{item.name}` inside `<foreach collection="users" item="item">` refers to `users[1].name`
 * on the second time round; `#{index}` is the iteration number.
 */
function resolveForeachNames(expression: string, scopes: ForeachScope[]): { name: string; literal?: string } {
  let name = expression;
  for (let i = scopes.length - 1; i >= 0; i--) {
    const scope = scopes[i];
    const root = name.split(/[.[]/)[0];
    if (scope.index && root === scope.index && name === root) {
      return { name, literal: String(scope.iteration) };
    }
    if (scope.item && root === scope.item) {
      name = `${scope.collection}[${scope.iteration}]${name.slice(root.length)}`;
    }
  }
  return { name };
}

// Lines starting with these begin a clause and are not indented; other lines are, by two spaces.
const CLAUSE_START =
  /^(?:SELECT|INSERT|UPDATE|DELETE|MERGE|WITH|FROM|WHERE|SET|VALUES|ORDER\s+BY|GROUP\s+BY|HAVING|UNION|MINUS|INTERSECT|FETCH|OFFSET|FOR\s+UPDATE|CONNECT\s+BY|START\s+WITH|RETURNING)\b/i;

/**
 * Lays the SQL out the same way whatever the XML's indentation was: blank lines left by tags go,
 * clauses start at the left edge and the lines inside them are indented by two spaces.
 */
function tidy(sql: string): string {
  return sql
    .split(/\r?\n/)
    .map((line) => collapseSpaces(line.trim()))
    .filter((line) => line.length > 0)
    .map((line, index) => (index === 0 || CLAUSE_START.test(line) ? line : `  ${line}`))
    .join('\n');
}

/** Runs of spaces left by tags become one space, except inside string literals, which must stay as written. */
function collapseSpaces(line: string): string {
  return line.split(/('(?:[^']|'')*')/).map((part, index) =>
    index % 2 === 1 ? part : part.replace(/ {2,}/g, ' ')).join('');
}
