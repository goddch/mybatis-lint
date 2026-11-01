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
  const element = new RegExp(`<(${tags.join('|')})\\b[^>]*>`, 'g');
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

function escape(name: string): string {
  return name.replace(/[$]/g, '\\$');
}
