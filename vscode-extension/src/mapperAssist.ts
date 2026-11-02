/**
 * Hover text and completion items for mapper XML, worked out from the raw XML
 * text and the workspace's Java sources. No `vscode` import: `extension.ts`
 * provides the Java lookup and turns offsets into positions.
 *
 * Everything is best effort. When a type or method cannot be found, the
 * functions return less (or nothing) instead of guessing.
 */

import { elementTypeName, isSimpleType, parseJavaType, qualifyTypeName, rawTypeName } from './javaSource';
import type { JavaMethod, JavaType } from './javaSource';
import { mapperNamespace } from './navigation';

/** Finds the workspace's Java types by simple name. */
export interface JavaLookup {
  findTypes(simpleName: string): Promise<JavaType[]>;
}

export interface AssistHover {
  /** Markdown shown in the hover. */
  markdown: string;
  start: number;
  end: number;
}

export interface AssistCompletion {
  label: string;
  /** A short note shown next to the label, such as the Java type. */
  detail: string;
  kind: 'parameter' | 'property' | 'id';
}

export interface AssistCompletions {
  items: AssistCompletion[];
  /** Offset where the text being completed starts; it is replaced by the chosen item. */
  start: number;
}

/** A name that can be used in `#{...}`, or a property, with where it comes from. */
interface NamedValue {
  name: string;
  type: string;
  /** "parameter of UserMapper.findById" or "property of com.example.User". */
  origin: string;
  kind: 'parameter' | 'property';
}

const STATEMENT_START = /<(select|insert|update|delete)\b([^>]*)>/g;
const TYPE_ATTRIBUTES = new Set(['resultType', 'parameterType', 'type', 'ofType', 'javaType']);

export async function hoverAt(xml: string, offset: number, lookup: JavaLookup): Promise<AssistHover | undefined> {
  const attribute = attributeAt(xml, offset);
  if (attribute) {
    return attributeHover(xml, attribute, lookup);
  }
  const statement = statementAt(xml, offset);
  if (!statement) {
    return undefined;
  }
  const expression = parameterExpressionAt(xml, offset);
  if (expression) {
    const word = wordAt(xml, offset);
    if (!word || word.start < expression.start) {
      return undefined;
    }
    const path = xml.slice(expression.start, word.end).split('.');
    const values = await resolvePath(xml, statement, path, lookup);
    const value = values?.find((candidate) => candidate.name === path[path.length - 1]);
    return value ? { markdown: describeValue(value), start: word.start, end: word.end } : undefined;
  }
  const alias = wordAt(xml, offset);
  if (alias && statement.tag === 'select' && /\bAS\s+"?$/i.test(xml.slice(statement.bodyStart, alias.start))) {
    const properties = await resultTypeProperties(statement, lookup);
    const normalized = normalize(alias.text);
    const property = properties?.find((candidate) => normalize(candidate.name) === normalized);
    return property ? { markdown: describeValue(property), start: alias.start, end: alias.end } : undefined;
  }
  return undefined;
}

export async function completionsAt(xml: string, offset: number, lookup: JavaLookup): Promise<AssistCompletions | undefined> {
  const attribute = attributeAt(xml, offset);
  if (attribute) {
    return idCompletions(xml, attribute, offset);
  }
  const statement = statementAt(xml, offset);
  if (!statement) {
    return undefined;
  }
  const expression = parameterExpressionAt(xml, offset);
  if (expression) {
    const typed = xml.slice(expression.start, offset);
    if (!/^[\w$]*(?:\.[\w$]*)*$/.test(typed)) {
      return undefined;
    }
    const path = typed.split('.');
    const values = await resolvePath(xml, statement, path, lookup);
    if (!values) {
      return undefined;
    }
    const prefix = path[path.length - 1];
    return { items: values.map(toCompletion), start: offset - prefix.length };
  }
  const alias = /\bAS\s+([\w$]*)$/i.exec(xml.slice(statement.bodyStart, offset));
  if (alias && statement.tag === 'select') {
    const properties = await resultTypeProperties(statement, lookup);
    return properties ? { items: properties.map(toCompletion), start: offset - alias[1].length } : undefined;
  }
  return undefined;
}

function toCompletion(value: NamedValue): AssistCompletion {
  return { label: value.name, detail: `${value.type} — ${value.origin}`, kind: value.kind };
}

function describeValue(value: NamedValue): string {
  return `\`${value.type} ${value.name}\` — ${value.origin}`;
}

// ---------------------------------------------------------------------------
// Statements, expressions and attributes in the XML text.

interface Statement {
  tag: string;
  id: string;
  attributes: Map<string, string>;
  bodyStart: number;
}

function statementAt(xml: string, offset: number): Statement | undefined {
  STATEMENT_START.lastIndex = 0;
  let found: Statement | undefined;
  let match: RegExpExecArray | null;
  while ((match = STATEMENT_START.exec(xml)) !== null && match.index < offset) {
    const bodyStart = match.index + match[0].length;
    const end = xml.indexOf(`</${match[1]}>`, bodyStart);
    if (offset >= bodyStart && (end < 0 || offset <= end)) {
      const attributes = parseAttributes(match[2]);
      found = { tag: match[1], id: attributes.get('id') ?? '', attributes, bodyStart };
    }
  }
  return found;
}

/** The `#{...}` or `${...}` the offset is in: where its name starts. Undefined outside one. */
function parameterExpressionAt(xml: string, offset: number): { start: number } | undefined {
  for (let i = offset - 1; i >= 0 && offset - i < 200; i--) {
    const c = xml[i];
    if (c === '}' || c === '\n' || c === ',') {
      return undefined;
    }
    if (c === '{') {
      return i > 0 && (xml[i - 1] === '#' || xml[i - 1] === '$') ? { start: skipSpaces(xml, i + 1) } : undefined;
    }
  }
  return undefined;
}

interface Attribute {
  tagName: string;
  name: string;
  value: string;
  valueStart: number;
}

/** The attribute whose value the offset is in, or undefined (also outside tags). */
function attributeAt(xml: string, offset: number): Attribute | undefined {
  const tagStart = xml.lastIndexOf('<', offset - 1);
  const tagEnd = xml.indexOf('>', tagStart);
  if (tagStart < 0 || (tagEnd >= 0 && tagEnd < offset)) {
    return undefined;
  }
  const tag = xml.slice(tagStart, tagEnd < 0 ? xml.length : tagEnd);
  const tagName = /^<([\w-]+)/.exec(tag)?.[1];
  if (!tagName) {
    return undefined;
  }
  const pattern = /([\w-]+)\s*=\s*(["'])(.*?)(?:\2|$)/gs;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(tag)) !== null) {
    const valueStart = tagStart + match.index + match[0].indexOf(match[2]) + 1;
    if (offset >= valueStart && offset <= valueStart + match[3].length) {
      return { tagName, name: match[1], value: match[3], valueStart };
    }
  }
  return undefined;
}

function parseAttributes(text: string): Map<string, string> {
  const attributes = new Map<string, string>();
  for (const match of text.matchAll(/([\w-]+)\s*=\s*(["'])(.*?)\2/gs)) {
    attributes.set(match[1], match[3].trim());
  }
  return attributes;
}

function wordAt(xml: string, offset: number): { text: string; start: number; end: number } | undefined {
  let start = offset;
  while (start > 0 && /[\w$]/.test(xml[start - 1])) {
    start--;
  }
  let end = offset;
  while (end < xml.length && /[\w$]/.test(xml[end])) {
    end++;
  }
  return end > start ? { text: xml.slice(start, end), start, end } : undefined;
}

function skipSpaces(xml: string, index: number): number {
  let i = index;
  while (i < xml.length && xml[i] === ' ') {
    i++;
  }
  return i;
}

// ---------------------------------------------------------------------------
// Attribute values: types, statement ids and references.

async function attributeHover(xml: string, attribute: Attribute, lookup: JavaLookup): Promise<AssistHover | undefined> {
  const range = { start: attribute.valueStart, end: attribute.valueStart + attribute.value.length };
  if (TYPE_ATTRIBUTES.has(attribute.name)) {
    const type = await findType(attribute.value.trim(), lookup);
    if (!type) {
      return undefined;
    }
    const properties = await allProperties(type, lookup);
    const lines = properties.slice(0, 40).map((property) => `- \`${property.type} ${property.name}\``);
    const more = properties.length > 40 ? [`- … ${properties.length - 40} more`] : [];
    return { markdown: [`\`${type.qualifiedName}\``, '', ...lines, ...more].join('\n'), ...range };
  }
  if (attribute.name === 'id' && ['select', 'insert', 'update', 'delete'].includes(attribute.tagName)) {
    const method = await mapperMethod(xml, attribute.value, lookup);
    return method ? { markdown: `\`\`\`java\n${method.signature}\n\`\`\``, ...range } : undefined;
  }
  return undefined;
}

/** `refid` offers the file's `<sql>` ids; `resultMap` and `extends` its `<resultMap>` ids. */
function idCompletions(xml: string, attribute: Attribute, offset: number): AssistCompletions | undefined {
  const tag =
    attribute.name === 'refid' && attribute.tagName === 'include'
      ? 'sql'
      : attribute.name === 'resultMap' || (attribute.name === 'extends' && attribute.tagName === 'resultMap')
        ? 'resultMap'
        : undefined;
  if (!tag) {
    return undefined;
  }
  const typed = xml.slice(attribute.valueStart, offset);
  // resultMap="a,b": complete the id after the last comma.
  const itemStart = attribute.valueStart + typed.lastIndexOf(',') + 1;
  const ids = [...xml.matchAll(new RegExp(`<${tag}\\b[^>]*?\\bid\\s*=\\s*(["'])(.*?)\\1`, 'gs'))].map((match) => match[2]);
  return {
    items: [...new Set(ids)].map((id) => ({ label: id, detail: `<${tag}> in this file`, kind: 'id' })),
    start: skipSpaces(xml, itemStart),
  };
}

// ---------------------------------------------------------------------------
// Java: the mapper method, its parameters, and properties of types.

async function mapperMethod(xml: string, statementId: string, lookup: JavaLookup): Promise<JavaMethod | undefined> {
  const mapper = await mapperType(xml, lookup);
  const id = statementId.slice(statementId.lastIndexOf('.') + 1);
  const methods = mapper?.methods.filter((method) => method.name === id) ?? [];
  // Overloaded methods share one statement; their parameters may differ, so do not guess.
  return methods.length === 1 ? methods[0] : undefined;
}

async function mapperType(xml: string, lookup: JavaLookup): Promise<JavaType | undefined> {
  const namespace = mapperNamespace(xml);
  if (!namespace) {
    return undefined;
  }
  const candidates = await lookup.findTypes(namespace.slice(namespace.lastIndexOf('.') + 1));
  return candidates.find((candidate) => candidate.qualifiedName === namespace && candidate.isInterface);
}

/**
 * The values available at each step of `#{a.b}`: for `["a"]` the names usable in the
 * statement, for `["a", "b"]` the properties of `a`'s type. Undefined when unknown.
 */
async function resolvePath(
  xml: string,
  statement: Statement,
  path: string[],
  lookup: JavaLookup,
): Promise<NamedValue[] | undefined> {
  const mapper = await mapperType(xml, lookup);
  const method = await mapperMethod(xml, statement.id, lookup);
  if (!mapper || !method) {
    return undefined;
  }
  let values = await statementNames(mapper, method, lookup);
  let context = mapper;
  for (const name of path.slice(0, -1)) {
    const value = values?.find((candidate) => candidate.name === name);
    if (!value) {
      return undefined;
    }
    const type = await findReferencedType(value.type, context, lookup);
    if (!type) {
      return undefined;
    }
    values = await allProperties(type, lookup);
    context = type;
  }
  return values;
}

/** The names `#{...}` can use in a statement, as MyBatis binds the method's arguments. */
async function statementNames(mapper: JavaType, method: JavaMethod, lookup: JavaLookup): Promise<NamedValue[] | undefined> {
  const origin = `parameter of ${mapper.name}.${method.name}`;
  const parameters = method.parameters;
  if (parameters.length === 1 && parameters[0].alias === undefined && !isSimpleType(parameters[0].type)) {
    // A single bean without @Param: #{...} names its properties.
    const bean = await findReferencedType(parameters[0].type, mapper, lookup);
    return bean ? allProperties(bean, lookup) : undefined;
  }
  return parameters.map((parameter) => ({
    name: parameter.alias ?? parameter.name,
    type: parameter.type,
    origin,
    kind: 'parameter' as const,
  }));
}

async function resultTypeProperties(statement: Statement, lookup: JavaLookup): Promise<NamedValue[] | undefined> {
  const resultType = statement.attributes.get('resultType');
  const type = resultType ? await findType(resultType, lookup) : undefined;
  return type ? allProperties(type, lookup) : undefined;
}

/** Properties of a class and its superclasses in the workspace. */
async function allProperties(type: JavaType, lookup: JavaLookup): Promise<NamedValue[]> {
  const values: NamedValue[] = [];
  const seen = new Set<string>();
  let current: JavaType | undefined = type;
  for (let depth = 0; current && depth < 5; depth++) {
    for (const property of current.properties) {
      if (!seen.has(property.name)) {
        seen.add(property.name);
        values.push({ ...property, origin: `property of ${current.qualifiedName}`, kind: 'property' });
      }
    }
    current = current.superclass ? await findReferencedType(current.superclass, current, lookup) : undefined;
  }
  return values;
}

/** A type written in Java source (`List<User>` refers to `User`), as seen from `context`. */
async function findReferencedType(written: string, context: JavaType, lookup: JavaLookup): Promise<JavaType | undefined> {
  const element = elementTypeName(written) ?? rawTypeName(written);
  const qualified = qualifyTypeName(element, context);
  return qualified ? findType(qualified, lookup) : undefined;
}

/**
 * A type by fully qualified name, or by simple name (an alias such as `User`). When the
 * qualified name is a guess that does not exist, a single type with the simple name is used.
 */
async function findType(name: string, lookup: JavaLookup): Promise<JavaType | undefined> {
  if (name.length === 0 || name.includes('${')) {
    return undefined;
  }
  const simple = name.slice(name.lastIndexOf('.') + 1);
  // Type aliases are case-insensitive (resultType="user" for User), but file names are not.
  const fileName = /^[a-z]/.test(simple) && !name.includes('.') ? simple[0].toUpperCase() + simple.slice(1) : simple;
  const candidates = await lookup.findTypes(fileName);
  const exact = candidates.find((candidate) => candidate.qualifiedName === name);
  if (exact) {
    return exact;
  }
  const bySimpleName = candidates.filter((candidate) => candidate.name.toLowerCase() === simple.toLowerCase());
  return bySimpleName.length === 1 ? bySimpleName[0] : undefined;
}

/** Column aliases match properties ignoring case and underscores, as MyBatis auto-mapping does. */
function normalize(name: string): string {
  return name.replace(/_/g, '').toLowerCase();
}

/** Parses each text and keeps the types, for lookups built from file contents. */
export function typesFromSources(texts: string[]): JavaType[] {
  return texts.map(parseJavaType).filter((type): type is JavaType => type !== undefined);
}
