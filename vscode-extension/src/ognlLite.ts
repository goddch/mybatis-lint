/**
 * Evaluates the `test` expressions of `<if>` / `<when>` for the SQL preview,
 * for the subset of OGNL mapper files commonly use: comparisons, and / or /
 * not, null, numbers, strings, `x.size()` / `length()` / `isEmpty()`.
 *
 * It follows OGNL where MyBatis users get surprised, so the preview shows what
 * MyBatis would really do: `'A'` is a char and never equals the String "A",
 * and `''` compared with a number counts as 0.
 *
 * Anything it cannot work out (an unknown value, an unsupported expression)
 * makes the result `undefined`, and the preview keeps the user's own choice.
 */

/** A value in a test: a list stands for a collection parameter (only its size matters). */
export type OgnlValue = null | boolean | number | string | { char: string } | { list: number };

/** Looks up a name such as `name` or `user.name`; undefined when its value is not known. */
export type OgnlScope = (path: string) => OgnlValue | undefined;

const UNKNOWN = Symbol('unknown');
type Result = OgnlValue | typeof UNKNOWN;

interface Token {
  kind: 'name' | 'number' | 'string' | 'char' | 'op' | 'paren';
  text: string;
}

/** True or false when the test can be decided, undefined otherwise. */
export function evaluateTest(expression: string, scope: OgnlScope): boolean | undefined {
  const tokens = tokenize(expression);
  if (!tokens) {
    return undefined;
  }
  const parser = new Parser(tokens, scope);
  const result = parser.parseOr();
  if (!parser.done() || result === UNKNOWN) {
    return undefined;
  }
  return truthy(result);
}

/**
 * Reads a value typed in the preview as SQL (`'abc'`, `123`, `NULL`, `true`). Empty text
 * means not entered, which is not the same as null.
 */
export function valueFromSql(text: string): OgnlValue | undefined {
  const trimmed = text.trim();
  if (trimmed.length === 0) {
    return undefined;
  }
  if (/^null$/i.test(trimmed)) {
    return null;
  }
  if (/^(true|false)$/i.test(trimmed)) {
    return trimmed.toLowerCase() === 'true';
  }
  if (/^-?\d+(\.\d+)?$/.test(trimmed)) {
    return Number(trimmed);
  }
  const quoted = /^'((?:[^']|'')*)'$/.exec(trimmed);
  return quoted ? quoted[1].replace(/''/g, "'") : undefined;
}

function tokenize(expression: string): Token[] | undefined {
  const tokens: Token[] = [];
  const pattern =
    /\s+|([A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*(?:\(\s*\))?)*)|(\d+(?:\.\d+)?)|'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)"|(==|!=|<=|>=|&&|\|\||<|>|!)|([()])/gy;
  let match: RegExpExecArray | null;
  let index = 0;
  while (index < expression.length) {
    pattern.lastIndex = index;
    match = pattern.exec(expression);
    if (!match || match[0].length === 0) {
      return undefined;
    }
    index = pattern.lastIndex;
    if (match[1] !== undefined) {
      const word = match[1].replace(/\s+/g, '');
      const operator = { and: '&&', or: '||', not: '!', eq: '==', neq: '!=', lt: '<', gt: '>', lte: '<=', gte: '>=' }[word];
      tokens.push(operator ? { kind: 'op', text: operator } : { kind: 'name', text: word });
    } else if (match[2] !== undefined) {
      tokens.push({ kind: 'number', text: match[2] });
    } else if (match[3] !== undefined) {
      // OGNL reads a single character in single quotes as a char, longer text as a String.
      tokens.push({ kind: match[3].length === 1 ? 'char' : 'string', text: match[3] });
    } else if (match[4] !== undefined) {
      tokens.push({ kind: 'string', text: match[4] });
    } else if (match[5] !== undefined) {
      tokens.push({ kind: 'op', text: match[5] });
    } else if (match[6] !== undefined) {
      tokens.push({ kind: 'paren', text: match[6] });
    }
  }
  return tokens;
}

class Parser {
  private position = 0;

  constructor(private readonly tokens: Token[], private readonly scope: OgnlScope) {}

  done(): boolean {
    return this.position === this.tokens.length;
  }

  parseOr(): Result {
    let left = this.parseAnd();
    while (this.accept('op', '||')) {
      const right = this.parseAnd();
      // true or anything is true, even when the other side is unknown.
      left = left !== UNKNOWN && truthy(left) ? true
        : right !== UNKNOWN && truthy(right) ? true
          : left === UNKNOWN || right === UNKNOWN ? UNKNOWN : false;
    }
    return left;
  }

  private parseAnd(): Result {
    let left = this.parseNot();
    while (this.accept('op', '&&')) {
      const right = this.parseNot();
      left = left !== UNKNOWN && !truthy(left) ? false
        : right !== UNKNOWN && !truthy(right) ? false
          : left === UNKNOWN || right === UNKNOWN ? UNKNOWN : true;
    }
    return left;
  }

  private parseNot(): Result {
    if (this.accept('op', '!')) {
      const value = this.parseNot();
      return value === UNKNOWN ? UNKNOWN : !truthy(value);
    }
    return this.parseComparison();
  }

  private parseComparison(): Result {
    const left = this.parsePrimary();
    const operator = this.tokens[this.position];
    if (operator?.kind !== 'op' || !['==', '!=', '<', '>', '<=', '>='].includes(operator.text)) {
      return left;
    }
    this.position++;
    const right = this.parsePrimary();
    if (left === UNKNOWN || right === UNKNOWN) {
      return UNKNOWN;
    }
    return compare(left, right, operator.text);
  }

  private parsePrimary(): Result {
    const token = this.tokens[this.position++];
    if (!token) {
      return UNKNOWN;
    }
    if (token.kind === 'paren' && token.text === '(') {
      const value = this.parseOr();
      return this.accept('paren', ')') ? value : UNKNOWN;
    }
    if (token.kind === 'number') {
      return Number(token.text);
    }
    if (token.kind === 'string') {
      return token.text;
    }
    if (token.kind === 'char') {
      return { char: token.text };
    }
    if (token.kind === 'name') {
      return this.name(token.text);
    }
    return UNKNOWN;
  }

  /** A name, possibly with a call at the end: `ids.size()`, `name.length()`, `list.isEmpty()`. */
  private name(text: string): Result {
    if (text === 'null') {
      return null;
    }
    if (text === 'true' || text === 'false') {
      return text === 'true';
    }
    const call = /^(.*)\.(size|length|isEmpty|trim)\(\)$/.exec(text);
    const value = this.scope(call ? call[1] : text);
    if (value === undefined) {
      return UNKNOWN;
    }
    if (!call) {
      return value;
    }
    const length = typeof value === 'string' ? value.length : isList(value) ? value.list : undefined;
    if (call[2] === 'trim') {
      return typeof value === 'string' ? value.trim() : UNKNOWN;
    }
    if (length === undefined) {
      return UNKNOWN;
    }
    return call[2] === 'isEmpty' ? length === 0 : length;
  }

  private accept(kind: Token['kind'], text: string): boolean {
    const token = this.tokens[this.position];
    if (token?.kind === kind && token.text === text) {
      this.position++;
      return true;
    }
    return false;
  }
}

function isList(value: OgnlValue): value is { list: number } {
  return typeof value === 'object' && value !== null && 'list' in value;
}

function isChar(value: OgnlValue): value is { char: string } {
  return typeof value === 'object' && value !== null && 'char' in value;
}

/** OgnlOps.booleanValue: null is false, numbers are true unless 0, and any other object is true. */
function truthy(value: OgnlValue): boolean {
  if (value === null) {
    return false;
  }
  if (typeof value === 'boolean') {
    return value;
  }
  if (typeof value === 'number') {
    return value !== 0;
  }
  if (isChar(value)) {
    return value.char.charCodeAt(0) !== 0;
  }
  return true;
}

function compare(left: OgnlValue, right: OgnlValue, operator: string): Result {
  if (operator === '==' || operator === '!=') {
    const equal = equals(left, right);
    if (equal === UNKNOWN) {
      return UNKNOWN;
    }
    return operator === '==' ? equal : !equal;
  }
  const a = numeric(left);
  const b = numeric(right);
  if (a === undefined || b === undefined) {
    return typeof left === 'string' && typeof right === 'string' ? order(left.localeCompare(right), operator) : UNKNOWN;
  }
  return order(a - b, operator);
}

function order(difference: number, operator: string): boolean {
  return operator === '<' ? difference < 0 : operator === '>' ? difference > 0 : operator === '<=' ? difference <= 0 : difference >= 0;
}

function equals(left: OgnlValue, right: OgnlValue): boolean | typeof UNKNOWN {
  if (left === null || right === null) {
    return left === right;
  }
  if (isList(left) || isList(right)) {
    return UNKNOWN;
  }
  // A char is never equal to a String in OGNL ('A' vs "A"), but it compares with numbers as its code.
  if (isChar(left) !== isChar(right) && (typeof left === 'string' || typeof right === 'string')) {
    return false;
  }
  if (typeof left === 'number' || typeof right === 'number') {
    const a = numeric(left);
    const b = numeric(right);
    // OGNL cannot turn "abc" into a number and fails; the preview leaves that to the user.
    return a === undefined || b === undefined ? UNKNOWN : a === b;
  }
  if (isChar(left) && isChar(right)) {
    return left.char === right.char;
  }
  return left === right;
}

/** OGNL turns a String into a number to compare it with one; '' becomes 0. */
function numeric(value: OgnlValue): number | undefined {
  if (typeof value === 'number') {
    return value;
  }
  if (typeof value === 'boolean') {
    return value ? 1 : 0;
  }
  if (isChar(value)) {
    return value.char.charCodeAt(0);
  }
  if (typeof value === 'string') {
    if (value.trim().length === 0) {
      return 0;
    }
    const number = Number(value);
    return Number.isNaN(number) ? undefined : number;
  }
  return undefined;
}
