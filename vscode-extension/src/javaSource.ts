/**
 * A light, text-level reader of Java source files, for editor assistance
 * (hover and completion). It is deliberately forgiving: it reads the first
 * top-level type of a file and only what assistance needs — fields, getters,
 * setters, and method signatures — and returns less when the source is
 * unusual. Lint rules use the analyzer's JavaParser index instead.
 */

export interface JavaProperty {
  name: string;
  /** The type as written, such as `String` or `List<Item>`. */
  type: string;
}

export interface JavaParameter {
  name: string;
  type: string;
  /** The `@Param("...")` value, if any. */
  alias?: string;
}

export interface JavaMethod {
  name: string;
  returnType: string;
  parameters: JavaParameter[];
  /** The declaration as written, on one line, for display. */
  signature: string;
}

export interface JavaType {
  packageName: string;
  name: string;
  qualifiedName: string;
  isInterface: boolean;
  /** The superclass as written, without type arguments. */
  superclass?: string;
  /** Simple name → fully qualified name, from the imports. */
  imports: Map<string, string>;
  /** Properties MyBatis can read or write: fields, getters and setters. */
  properties: JavaProperty[];
  methods: JavaMethod[];
}

const MODIFIERS = /^(?:(?:public|protected|private|static|final|abstract|default|synchronized|native|transient|volatile|strictfp)\s+)*/;
const LEADING_ANNOTATIONS = /^\s*(?:@[\w.]+\s*(?:\((?:[^()]|\([^()]*\))*\))?\s*)*/;
const SIMPLE_TYPES = new Set([
  'byte', 'short', 'int', 'long', 'float', 'double', 'boolean', 'char',
  'Byte', 'Short', 'Integer', 'Long', 'Float', 'Double', 'Boolean', 'Character',
  'String', 'Object', 'Number', 'BigDecimal', 'BigInteger', 'Date', 'LocalDate', 'LocalDateTime', 'LocalTime',
  'Timestamp', 'Map', 'HashMap', 'List', 'ArrayList', 'Set', 'Collection',
]);

/** The first top-level type declared in a Java file, or undefined. */
export function parseJavaType(text: string): JavaType | undefined {
  const code = maskJava(text);
  const packageName = /\bpackage\s+([\w.]+)\s*;/.exec(code)?.[1] ?? '';
  const imports = new Map<string, string>();
  for (const match of code.matchAll(/\bimport\s+(?!static\b)([\w.]+)\s*;/g)) {
    imports.set(match[1].slice(match[1].lastIndexOf('.') + 1), match[1]);
  }
  const declaration = /\b(class|interface|record|enum)\s+([\w$]+)/.exec(code);
  if (!declaration) {
    return undefined;
  }
  const bodyStart = code.indexOf('{', declaration.index);
  if (bodyStart < 0) {
    return undefined;
  }
  const header = code.slice(declaration.index, bodyStart);
  const superclass = declaration[1] === 'class' ? /\bextends\s+([\w.$]+)/.exec(header)?.[1] : undefined;
  const type: JavaType = {
    packageName,
    name: declaration[2],
    qualifiedName: packageName ? `${packageName}.${declaration[2]}` : declaration[2],
    isInterface: declaration[1] === 'interface',
    superclass,
    imports,
    properties: [],
    methods: [],
  };
  // Enum constants are not properties, and assistance does not need enum members.
  if (declaration[1] !== 'enum') {
    readMembers(text, code, bodyStart, type);
  }
  return type;
}

/** Reads the members directly inside the type body (nested types and method bodies are skipped). */
function readMembers(text: string, code: string, bodyStart: number, type: JavaType): void {
  const properties = new Map<string, string>();
  let segmentStart = bodyStart + 1;
  for (let i = bodyStart + 1; i < code.length; i++) {
    const c = code[i];
    if (c === '}') {
      break;
    }
    if (c !== ';' && c !== '{') {
      continue;
    }
    const segment = code.slice(segmentStart, i);
    const original = text.slice(segmentStart, i);
    const isBlock = c === '{';
    if (isBlock && /\b(class|interface|enum|record)\s/.test(segment)) {
      i = closingBrace(code, i);
    } else if (isMethod(segment)) {
      const method = readMethod(segment, original);
      if (method) {
        type.methods.push(method);
        addAccessor(method, properties);
      }
      if (isBlock) {
        i = closingBrace(code, i);
      }
    } else if (!isBlock) {
      readField(segment, properties);
    } else {
      // An initializer block.
      i = closingBrace(code, i);
    }
    segmentStart = i + 1;
  }
  type.properties = [...properties.entries()].map(([name, propertyType]) => ({ name, type: propertyType }));
}

/**
 * A member with a parameter list is a method. Annotations can have parentheses too
 * (`@Size(max = 10) String name;`), and so can a field's initializer (`= new ArrayList<>()`).
 */
function isMethod(segment: string): boolean {
  const declaration = segment.replace(LEADING_ANNOTATIONS, '');
  const open = declaration.indexOf('(');
  return open >= 0 && !declaration.slice(0, open).includes('=');
}

function readField(segment: string, properties: Map<string, string>): void {
  const declaration = segment.replace(LEADING_ANNOTATIONS, '').split('=')[0].trim();
  if (/\bstatic\b/.test(declaration) || declaration.length === 0) {
    return;
  }
  const match = /^(.*\S)\s+([\w$]+)$/.exec(declaration.replace(MODIFIERS, ''));
  if (match) {
    properties.set(match[2], match[1].replace(/\s+/g, ' '));
  }
}

function readMethod(segment: string, original: string): JavaMethod | undefined {
  const leading = LEADING_ANNOTATIONS.exec(segment)?.[0].length ?? 0;
  const open = segment.indexOf('(', leading);
  const close = closingParen(segment, open);
  if (open < 0 || close < 0) {
    return undefined;
  }
  const head = segment.slice(leading, open).replace(MODIFIERS, '').replace(/^<[^>]*>\s*/, '').trim();
  const match = /^(.*\S)\s+([\w$]+)$/.exec(head);
  if (!match) {
    return undefined;
  }
  const parameters: JavaParameter[] = [];
  for (const [start, end] of splitTopLevel(segment, open + 1, close)) {
    const written = original.slice(start, end);
    const alias = /@Param\s*\(\s*(?:value\s*=\s*)?"([^"]*)"/.exec(written)?.[1];
    const declaration = segment.slice(start, end).replace(LEADING_ANNOTATIONS, '').replace(/^\s*final\s+/, '').trim();
    const parameter = /^(.*\S)\s+([\w$]+)$/.exec(declaration);
    if (parameter) {
      parameters.push({ name: parameter[2], type: parameter[1].replace(/\s+/g, ' '), ...(alias !== undefined ? { alias } : {}) });
    }
  }
  return {
    name: match[2],
    returnType: match[1].replace(/\s+/g, ' '),
    parameters,
    signature: original.slice(leading, close + 1).replace(/\s+/g, ' ').trim(),
  };
}

/** getName() / isActive() / setName(x) also make properties, named as MyBatis names them. */
function addAccessor(method: JavaMethod, properties: Map<string, string>): void {
  const accessor = /^(get|is|set)([A-Z][\w$]*)$/.exec(method.name);
  if (!accessor) {
    return;
  }
  const name = propertyName(accessor[2]);
  if (accessor[1] === 'set' && method.parameters.length === 1) {
    properties.set(name, properties.get(name) ?? method.parameters[0].type);
  } else if (accessor[1] !== 'set' && method.parameters.length === 0 && method.returnType !== 'void') {
    properties.set(name, properties.get(name) ?? method.returnType);
  }
}

/** `Name` → `name`, but `URL` stays `URL` (java.beans.Introspector.decapitalize). */
function propertyName(capitalized: string): string {
  return capitalized.length > 1 && capitalized[1] === capitalized[1].toUpperCase() && /[A-Z]/.test(capitalized[1])
    ? capitalized
    : capitalized[0].toLowerCase() + capitalized.slice(1);
}

/** `List<User>` → `List`, `User[]` → `User`, `com.example.User` stays as is. */
export function rawTypeName(type: string): string {
  return type.replace(/<.*$/s, '').replace(/\[\]/g, '').replace(/\.\.\.$/, '').trim();
}

/** The element type of `List<User>`, `Set<? extends User>` or `User[]`, or undefined. */
export function elementTypeName(type: string): string | undefined {
  const generic = /^[\w.]+\s*<\s*(?:\?\s+extends\s+)?([\w.]+)\s*>$/.exec(type.trim());
  if (generic) {
    return generic[1];
  }
  const array = /^([\w.]+)\s*(?:\[\]|\.\.\.)$/.exec(type.trim());
  return array ? array[1] : undefined;
}

/** Types whose value is used as is (MyBatis does not look inside them for properties). */
export function isSimpleType(type: string): boolean {
  const raw = rawTypeName(type);
  return SIMPLE_TYPES.has(raw.slice(raw.lastIndexOf('.') + 1)) || type.trim().endsWith('[]');
}

/**
 * The fully qualified name a type written in `context` most likely refers to:
 * through the imports, or in the same package. Undefined for simple types.
 */
export function qualifyTypeName(type: string, context: JavaType): string | undefined {
  const raw = rawTypeName(type);
  if (raw.length === 0 || isSimpleType(raw)) {
    return undefined;
  }
  if (raw.includes('.')) {
    return raw;
  }
  return context.imports.get(raw) ?? (context.packageName ? `${context.packageName}.${raw}` : raw);
}

/** Blanks out comments and the contents of string and character literals, keeping every offset. */
export function maskJava(text: string): string {
  return text.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'/g, (match) => {
    if (match.startsWith('"') || match.startsWith("'")) {
      return match[0] + ' '.repeat(match.length - 2) + match[0];
    }
    return match.replace(/[^\n]/g, ' ');
  });
}

function closingBrace(code: string, open: number): number {
  let depth = 0;
  for (let i = open; i < code.length; i++) {
    if (code[i] === '{') {
      depth++;
    } else if (code[i] === '}' && --depth === 0) {
      return i;
    }
  }
  return code.length;
}

function closingParen(code: string, open: number): number {
  if (open < 0) {
    return -1;
  }
  let depth = 0;
  for (let i = open; i < code.length; i++) {
    if (code[i] === '(') {
      depth++;
    } else if (code[i] === ')' && --depth === 0) {
      return i;
    }
  }
  return -1;
}

/** Splits on commas outside parentheses and angle brackets. */
function splitTopLevel(code: string, from: number, to: number): Array<[number, number]> {
  const spans: Array<[number, number]> = [];
  let depth = 0;
  let start = from;
  for (let i = from; i < to; i++) {
    const c = code[i];
    if (c === '(' || c === '<') {
      depth++;
    } else if (c === ')' || c === '>') {
      depth--;
    } else if (c === ',' && depth === 0) {
      spans.push([start, i]);
      start = i + 1;
    }
  }
  if (code.slice(start, to).trim().length > 0) {
    spans.push([start, to]);
  }
  return spans;
}
