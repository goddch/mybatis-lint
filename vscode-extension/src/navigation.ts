/**
 * Text-level helpers for jumping between mapper XML and Java mapper
 * interfaces ("Go to Definition"). They read raw document text and know
 * nothing about `vscode`, so they can be unit tested; `extension.ts` finds the
 * files and turns offsets into locations.
 *
 * Everything here is a best-effort text search: when a name cannot be found
 * the caller simply offers no jump.
 */

/** What the cursor is on in a mapper XML file. */
export type XmlReference =
  /** A statement's `id`: jump to the Java mapper method. */
  | { kind: 'statement'; namespace: string; id: string }
  /** `<include refid>`: jump to the `<sql>` fragment. */
  | { kind: 'sql'; namespace: string; id: string }
  /** `resultMap="..."` or `<resultMap extends="...">`: jump to the `<resultMap>`. */
  | { kind: 'resultMap'; namespace: string; id: string };

const STATEMENT_TAGS = new Set(['select', 'insert', 'update', 'delete']);

/** The `namespace` of the `<mapper>` element, or undefined. */
export function mapperNamespace(text: string): string | undefined {
  const match = /<mapper\b[^>]*?\bnamespace\s*=\s*(["'])(.*?)\1/s.exec(text);
  return match ? match[2].trim() : undefined;
}

/** The reference under `offset` in a mapper XML document, or undefined. */
export function xmlReferenceAt(text: string, offset: number): XmlReference | undefined {
  const tagStart = text.lastIndexOf('<', offset);
  const tagEnd = text.indexOf('>', tagStart);
  if (tagStart < 0 || (tagEnd >= 0 && tagEnd < offset)) {
    return undefined;
  }
  const tag = text.slice(tagStart, tagEnd < 0 ? text.length : tagEnd);
  const tagName = /^<([\w-]+)/.exec(tag)?.[1];
  if (!tagName) {
    return undefined;
  }
  const attribute = /([\w-]+)\s*=\s*(["'])(.*?)\2/gs;
  let match: RegExpExecArray | null;
  while ((match = attribute.exec(tag)) !== null) {
    const valueStart = tagStart + match.index + match[0].length - 1 - match[3].length;
    const valueEnd = valueStart + match[3].length;
    if (offset < valueStart || offset > valueEnd) {
      continue;
    }
    const name = match[1];
    const value = itemAt(match[3], offset - valueStart);
    const ownNamespace = mapperNamespace(text) ?? '';
    if (value.length === 0 || value.includes('${')) {
      return undefined;
    }
    if (name === 'id' && STATEMENT_TAGS.has(tagName)) {
      return { kind: 'statement', namespace: ownNamespace, id: value };
    }
    if (name === 'refid' && tagName === 'include') {
      return { kind: 'sql', ...qualify(value, ownNamespace) };
    }
    if (name === 'resultMap' || (name === 'extends' && tagName === 'resultMap')) {
      return { kind: 'resultMap', ...qualify(value, ownNamespace) };
    }
    return undefined;
  }
  return undefined;
}

/** `resultMap="a,b"` lists several ids; the one the cursor is on. */
function itemAt(value: string, offsetInValue: number): string {
  const start = value.lastIndexOf(',', offsetInValue - 1) + 1;
  const end = value.indexOf(',', offsetInValue);
  return value.slice(start, end < 0 ? value.length : end).trim();
}

/** `other.Mapper.id` refers to another namespace; a plain `id` to the file's own. */
function qualify(value: string, ownNamespace: string): { namespace: string; id: string } {
  const dot = value.lastIndexOf('.');
  return dot < 0
    ? { namespace: ownNamespace, id: value }
    : { namespace: value.slice(0, dot), id: value.slice(dot + 1) };
}

/** Offsets of the `id` values of `<tag id="id">` elements (for any of `tags`). */
export function findXmlIds(text: string, tags: string[], id: string): number[] {
  const offsets: number[] = [];
  const element = new RegExp(`<(${tags.map(escape).join('|')})\\b[^>]*>`, 'g');
  let match: RegExpExecArray | null;
  while ((match = element.exec(text)) !== null) {
    const idMatch = /\bid\s*=\s*(["'])(.*?)\1/s.exec(match[0]);
    // MyBatis also accepts a statement id written with its namespace.
    if (idMatch && (idMatch[2].trim() === id || idMatch[2].trim().endsWith(`.${id}`))) {
      offsets.push(match.index + idMatch.index + idMatch[0].indexOf(idMatch[2]));
    }
  }
  return offsets;
}

/** Tags whose `id` a reference of each kind points to. */
export const TARGET_TAGS: Record<XmlReference['kind'], string[]> = {
  statement: ['select', 'insert', 'update', 'delete'],
  sql: ['sql'],
  resultMap: ['resultMap'],
};

/**
 * Offset of the name of the method `name` declared in a Java interface, or -1.
 * A declaration is `name(` preceded by a return type, not by `.`, `=`, `new`
 * or `return` (which would make it a call).
 */
export function findJavaMethod(text: string, name: string): number {
  const code = maskJavaComments(text);
  const declaration = new RegExp(`(?<![\\w$.])${escape(name)}\\s*\\(`, 'g');
  let match: RegExpExecArray | null;
  while ((match = declaration.exec(code)) !== null) {
    const before = code.slice(Math.max(0, match.index - 200), match.index);
    // The return type ends right before the name: Foo, List<Foo>, Foo[], int.
    // A comma is not a sign of a call here: it appears in return types such as Map<String, Object>.
    if (/[\w$>\]]\s+$/.test(before) && !/(?:\breturn|\bnew|[=(!&|])\s*[\w$<>[\],.? ]*\s$/.test(before)) {
      return match.index;
    }
  }
  return -1;
}

/**
 * The mapper interface method whose name is under `offset` in a Java file, as
 * the namespace (the interface's fully qualified name) and the statement id.
 */
export function javaMethodAt(text: string, offset: number): { namespace: string; id: string } | undefined {
  const code = maskJavaComments(text);
  const interfaceName = /\binterface\s+([\w$]+)/.exec(code)?.[1];
  if (!interfaceName) {
    return undefined;
  }
  let start = offset;
  while (start > 0 && /[\w$]/.test(code[start - 1])) {
    start--;
  }
  let end = offset;
  while (end < code.length && /[\w$]/.test(code[end])) {
    end++;
  }
  const name = code.slice(start, end);
  if (name.length === 0 || findJavaMethod(text, name) !== start) {
    return undefined;
  }
  const packageName = /\bpackage\s+([\w$.]+)\s*;/.exec(code)?.[1];
  return { namespace: packageName ? `${packageName}.${interfaceName}` : interfaceName, id: name };
}

/** Blanks out comments and string literals, keeping offsets, so names inside them are not matched. */
function maskJavaComments(text: string): string {
  return text.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*|"(?:\\.|[^"\\\n])*"/g, (match) => match.replace(/[^\n]/g, ' '));
}

/** Escapes every regular expression metacharacter, so any id is matched literally. */
function escape(name: string): string {
  return name.replace(/[\\^$.*+?()[\]{}|]/g, '\\$&');
}

// ---------------------------------------------------------------------------
// Symbols for "Find All References", rename and CodeLens.

/** A `<sql>`, `<resultMap>` or statement, by namespace and id. */
export interface MapperSymbol {
  kind: XmlReference['kind'];
  namespace: string;
  id: string;
}

/** Where a symbol's id is written: `start`/`end` cover only the id, not a namespace prefix. */
export interface SymbolOccurrence {
  start: number;
  end: number;
  /** The `id` attribute of the definition itself, rather than a reference to it. */
  definition: boolean;
}

/**
 * The symbol under `offset` in mapper XML: a reference (`refid`, `resultMap`, `extends`,
 * `select` of a nested mapping) or a definition's `id`.
 */
export function symbolAt(text: string, offset: number): (MapperSymbol & { start: number; end: number }) | undefined {
  const namespace = mapperNamespace(text);
  if (!namespace) {
    return undefined;
  }
  return symbolOccurrences(text, undefined).find(
    (occurrence) => occurrence.start <= offset && offset <= occurrence.end,
  );
}

/**
 * Every definition and reference in a mapper XML file, or only those of `target`.
 * References written with a namespace (`other.Mapper.columns`) resolve to that namespace.
 */
export function symbolOccurrences(
  text: string,
  target: MapperSymbol | undefined,
): Array<MapperSymbol & SymbolOccurrence> {
  const ownNamespace = mapperNamespace(text);
  if (!ownNamespace) {
    return [];
  }
  const found: Array<MapperSymbol & SymbolOccurrence> = [];
  const element = /<([\w-]+)\b((?:[^>"']|"[^"]*"|'[^']*')*)>/g;
  let tag: RegExpExecArray | null;
  while ((tag = element.exec(text)) !== null) {
    const tagName = tag[1];
    const attribute = /([\w-]+)\s*=\s*(["'])(.*?)\2/gs;
    let match: RegExpExecArray | null;
    while ((match = attribute.exec(tag[2])) !== null) {
      const valueStart = tag.index + 1 + tagName.length + match.index + match[0].length - 1 - match[3].length;
      const kind = symbolKind(tagName, match[1]);
      if (!kind || match[3].includes('${')) {
        continue;
      }
      const definition = match[1] === 'id';
      // resultMap="a,b" lists several ids.
      const items = definition ? [[0, match[3].length]] : commaItems(match[3]);
      for (const [itemStart, itemEnd] of items) {
        const raw = match[3].slice(itemStart, itemEnd);
        const leading = raw.length - raw.trimStart().length;
        const value = raw.trim();
        if (value.length === 0) {
          continue;
        }
        // A definition's id may be written with its namespace too (statements accept it).
        const symbol = definition && value.startsWith(`${ownNamespace}.`)
          ? { namespace: ownNamespace, id: value.slice(ownNamespace.length + 1) }
          : definition ? { namespace: ownNamespace, id: value } : qualify(value, ownNamespace);
        const idStart = valueStart + itemStart + leading + value.length - symbol.id.length;
        if (target && (target.kind !== kind || target.namespace !== symbol.namespace || target.id !== symbol.id)) {
          continue;
        }
        found.push({ kind, ...symbol, start: idStart, end: idStart + symbol.id.length, definition });
      }
    }
  }
  return found;
}

/** What an attribute names, if it names a mapper symbol. */
function symbolKind(tagName: string, attribute: string): XmlReference['kind'] | undefined {
  if (attribute === 'id') {
    return STATEMENT_TAGS.has(tagName) ? 'statement' : tagName === 'sql' ? 'sql' : tagName === 'resultMap' ? 'resultMap' : undefined;
  }
  if (attribute === 'refid' && tagName === 'include') {
    return 'sql';
  }
  if (attribute === 'resultMap' || (attribute === 'extends' && tagName === 'resultMap')) {
    return 'resultMap';
  }
  // <association select="..."> and <collection select="..."> run another statement.
  if (attribute === 'select' && (tagName === 'association' || tagName === 'collection')) {
    return 'statement';
  }
  return undefined;
}

function commaItems(value: string): Array<[number, number]> {
  const items: Array<[number, number]> = [];
  let start = 0;
  for (let i = 0; i <= value.length; i++) {
    if (i === value.length || value[i] === ',') {
      items.push([start, i]);
      start = i + 1;
    }
  }
  return items;
}

/**
 * `@ResultMap("id")` / `@ResultMap({"a", "ns.b"})` values in a Java mapper interface that refer
 * to `target`, as the offsets of the id inside the string literal.
 */
export function javaResultMapOccurrences(text: string, target: MapperSymbol): Array<{ start: number; end: number }> {
  if (target.kind !== 'resultMap') {
    return [];
  }
  // A short id in @ResultMap belongs to the mapper interface's own namespace.
  const interfaceName = /\binterface\s+([\w$]+)/.exec(text)?.[1];
  const packageName = /\bpackage\s+([\w$.]+)\s*;/.exec(text)?.[1];
  const namespace = interfaceName && (packageName ? `${packageName}.${interfaceName}` : interfaceName);
  if (!namespace) {
    return [];
  }
  const found: Array<{ start: number; end: number }> = [];
  for (const annotation of text.matchAll(/@ResultMap\s*\(([^)]*)\)/g)) {
    const argumentsStart = (annotation.index ?? 0) + annotation[0].indexOf('(') + 1;
    for (const literal of annotation[1].matchAll(/"([^"\\]*)"/g)) {
      const symbol = qualify(literal[1].trim(), namespace);
      if (symbol.namespace === target.namespace && symbol.id === target.id) {
        const start = argumentsStart + (literal.index ?? 0) + 1 + literal[1].length - symbol.id.length;
        found.push({ start, end: start + symbol.id.length });
      }
    }
  }
  return found;
}

/** `kind namespace.id`, a key for counting occurrences of the same symbol across files. */
export function symbolKey(symbol: MapperSymbol): string {
  return `${symbol.kind} ${symbol.namespace}.${symbol.id}`;
}

/**
 * All occurrences in several mapper XML files, grouped by symbol, so CodeLens can count references
 * with one pass over the workspace instead of one per definition.
 */
export function occurrencesBySymbol<T>(
  files: Array<{ file: T; text: string }>,
): Map<string, Array<SymbolOccurrence & { file: T }>> {
  const bySymbol = new Map<string, Array<SymbolOccurrence & { file: T }>>();
  for (const { file, text } of files) {
    for (const occurrence of symbolOccurrences(text, undefined)) {
      const key = symbolKey(occurrence);
      const list = bySymbol.get(key) ?? [];
      list.push({ start: occurrence.start, end: occurrence.end, definition: occurrence.definition, file });
      bySymbol.set(key, list);
    }
  }
  return bySymbol;
}
