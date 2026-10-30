/**
 * Finds, in a mapper XML document, which INSERT column each value of
 * `VALUES (...)` or each item of the `SELECT` list belongs to, so the editor
 * can show the column name faintly at the end of its line.
 *
 * Works on the raw document text (no `vscode` import) so hints follow the
 * user's typing without running the analyzer. Statements whose column list or
 * values contain XML elements (`<if>`, `<include>`, `<foreach>`, ...) are
 * skipped: what MyBatis outputs there depends on run-time values.
 */

export interface InsertColumnHint {
  /** Offset in the document text where the value starts. */
  offset: number;
  /** The INSERT column the value is written to. */
  column: string;
  /** The value's own name matches the column (ignoring case and underscores), e.g. `#{orderId}` for ORDER_ID. */
  sameName: boolean;
  /** Offset of the `<insert>` body the value belongs to, so hints can be aligned per statement. */
  statement: number;
}

/** A hint shown at the end of a line: the columns of the values that start on that line. */
export interface LineEndHint {
  /** Offset of the end of the line. */
  offset: number;
  /** Character cells between the end of the line and the hint, lining it up with the others in the statement. */
  padding: number;
  /** The column names. */
  text: string;
}

const INSERT_TAG = /<insert\b[^>]*?(\/)?>/g;
const INSERT_INTO = /\bINSERT\s+INTO\s+[\w$#."@]+\s*\(/i;
const VALUES = /^\s*VALUES\s*\(/i;
const SELECT = /^\s*SELECT\b(\s+(DISTINCT|UNIQUE|ALL)\b)?/i;
const FROM = /^FROM\b/i;
const COLUMN_NAME = /^"?[\w$#]+"?$/;

export function insertColumnHints(text: string): InsertColumnHint[] {
  const hints: InsertColumnHint[] = [];
  INSERT_TAG.lastIndex = 0;
  let tag: RegExpExecArray | null;
  while ((tag = INSERT_TAG.exec(text)) !== null) {
    if (tag[1]) {
      continue;
    }
    const bodyStart = tag.index + tag[0].length;
    const bodyEnd = text.indexOf('</insert>', bodyStart);
    if (bodyEnd < 0) {
      break;
    }
    hints.push(...hintsForStatement(text, bodyStart, bodyEnd));
    INSERT_TAG.lastIndex = bodyEnd;
  }
  return hints;
}

function hintsForStatement(text: string, bodyStart: number, bodyEnd: number): InsertColumnHint[] {
  // Offsets in `masked` are offsets in the statement body.
  const masked = mask(text.slice(bodyStart, bodyEnd));
  const insert = INSERT_INTO.exec(masked);
  if (!insert) {
    return [];
  }
  const columnsOpen = insert.index + insert[0].length - 1;
  const columnsClose = closingParen(masked, columnsOpen);
  const columnSpans = columnsClose < 0 ? undefined : split(masked, columnsOpen + 1, columnsClose);
  if (!columnSpans) {
    return [];
  }
  const columns = columnSpans.map(([start, end]) => text.slice(bodyStart + start, bodyStart + end).trim());
  if (!columns.every((column) => COLUMN_NAME.test(column))) {
    return [];
  }

  let valueSpans: Array<[number, number]> | undefined;
  const rest = masked.slice(columnsClose + 1);
  const values = VALUES.exec(rest);
  const select = SELECT.exec(rest);
  if (values) {
    const open = columnsClose + 1 + values[0].length - 1;
    const close = closingParen(masked, open);
    valueSpans = close < 0 ? undefined : split(masked, open + 1, close);
  } else if (select) {
    const listStart = columnsClose + 1 + select[0].length;
    const from = topLevelFrom(masked, listStart);
    valueSpans = from < 0 ? undefined : split(masked, listStart, from);
  }
  if (!valueSpans) {
    return [];
  }

  const hints: InsertColumnHint[] = [];
  valueSpans.slice(0, columns.length).forEach(([start, end], index) => {
    const leading = masked.slice(start, end).search(/\S/);
    const value = text.slice(bodyStart + start, bodyStart + end).trim();
    const column = columns[index].replace(/"/g, '');
    hints.push({
      offset: bodyStart + start + leading,
      column,
      sameName: normalize(valueName(value)) === normalize(column),
      statement: bodyStart,
    });
  });
  return hints;
}

/**
 * Moves hints to the end of the line their value starts on, so they do not
 * push the SQL sideways, and pads them so that every hint of one statement
 * starts in the same column. Several values on one line share one hint.
 *
 * @param tabSize width of a tab, to measure lines indented with tabs
 */
export function lineEndHints(text: string, hints: InsertColumnHint[], tabSize: number): LineEndHint[] {
  // Line end offset → columns, grouped by statement, in document order.
  const byStatement = new Map<number, Map<number, string[]>>();
  for (const hint of hints) {
    let lineEnd = text.indexOf('\n', hint.offset);
    lineEnd = lineEnd < 0 ? text.length : lineEnd;
    if (lineEnd > 0 && text[lineEnd - 1] === '\r') {
      lineEnd--;
    }
    const lines = byStatement.get(hint.statement) ?? new Map<number, string[]>();
    lines.set(lineEnd, [...(lines.get(lineEnd) ?? []), hint.column]);
    byStatement.set(hint.statement, lines);
  }

  const result: LineEndHint[] = [];
  for (const lines of byStatement.values()) {
    const widths = new Map<number, number>();
    for (const lineEnd of lines.keys()) {
      const lineStart = text.lastIndexOf('\n', lineEnd - 1) + 1;
      widths.set(lineEnd, displayWidth(text.slice(lineStart, lineEnd), tabSize));
    }
    // Two spaces after the longest line keep the hint clear of the SQL.
    const column = Math.max(...widths.values()) + 2;
    for (const [lineEnd, columns] of lines) {
      result.push({ offset: lineEnd, padding: column - (widths.get(lineEnd) ?? 0), text: columns.join(', ') });
    }
  }
  return result.sort((a, b) => a.offset - b.offset);
}

/** Width of a line as an editor shows it: tabs expand to the next tab stop, full-width characters take two cells. */
export function displayWidth(line: string, tabSize: number): number {
  let width = 0;
  for (const char of line) {
    if (char === '\t') {
      width += tabSize - (width % tabSize);
    } else {
      width += isWide(char.codePointAt(0) ?? 0) ? 2 : 1;
    }
  }
  return width;
}

/** East Asian wide and full-width characters (CJK, kana, hangul, full-width forms, emoji). */
function isWide(code: number): boolean {
  return (
    (code >= 0x1100 && code <= 0x115f) ||
    (code >= 0x2e80 && code <= 0xa4cf) ||
    (code >= 0xac00 && code <= 0xd7a3) ||
    (code >= 0xf900 && code <= 0xfaff) ||
    (code >= 0xfe30 && code <= 0xfe4f) ||
    (code >= 0xff00 && code <= 0xff60) ||
    (code >= 0xffe0 && code <= 0xffe6) ||
    (code >= 0x1f300 && code <= 0x1faff) ||
    (code >= 0x20000 && code <= 0x3fffd)
  );
}

/**
 * Blanks out XML comments, CDATA markers and SQL comments, and fills string
 * literals and `#{...}` with `x`, keeping every offset. A `<` that starts an
 * XML element is kept, so callers can tell where an element interrupts the SQL;
 * `${...}` becomes `\0`, since it can expand to any number of columns.
 */
function mask(sql: string): string {
  const chars = sql.split('');
  const fill = (from: number, to: number, c: string): number => {
    for (let i = from; i < to && i < chars.length; i++) {
      chars[i] = chars[i] === '\n' ? '\n' : c;
    }
    return Math.min(to, chars.length);
  };
  let inCdata = false;
  for (let i = 0; i < sql.length; i++) {
    if (!inCdata && sql.startsWith('<![CDATA[', i)) {
      inCdata = true;
      i = fill(i, i + 9, ' ') - 1;
    } else if (inCdata && sql.startsWith(']]>', i)) {
      inCdata = false;
      i = fill(i, i + 3, ' ') - 1;
    } else if (!inCdata && sql.startsWith('<!--', i)) {
      const end = sql.indexOf('-->', i);
      i = fill(i, end < 0 ? sql.length : end + 3, ' ') - 1;
    } else if (sql.startsWith('--', i)) {
      const end = sql.indexOf('\n', i);
      i = fill(i, end < 0 ? sql.length : end, ' ') - 1;
    } else if (sql.startsWith('/*', i)) {
      const end = sql.indexOf('*/', i + 2);
      i = fill(i, end < 0 ? sql.length : end + 2, ' ') - 1;
    } else if (sql[i] === "'") {
      let end = i + 1;
      while (end < sql.length && (sql[end] !== "'" || sql.startsWith("''", end))) {
        end += sql.startsWith("''", end) ? 2 : 1;
      }
      fill(i + 1, end, 'x');
      i = end;
    } else if (sql.startsWith('#{', i)) {
      const end = sql.indexOf('}', i);
      i = fill(i + 2, end < 0 ? sql.length : end, 'x');
    } else if (sql.startsWith('${', i)) {
      chars[i] = '\0';
    } else if (inCdata && sql[i] === '<') {
      // Inside CDATA, < is a comparison operator, not an element.
      chars[i] = ' ';
    }
  }
  return chars.join('');
}

function closingParen(sql: string, open: number): number {
  let depth = 0;
  for (let i = open; i < sql.length; i++) {
    if (sql[i] === '<') {
      return -1;
    }
    if (sql[i] === '(') {
      depth++;
    } else if (sql[i] === ')' && --depth === 0) {
      return i;
    }
  }
  return -1;
}

function topLevelFrom(sql: string, from: number): number {
  let depth = 0;
  for (let i = from; i < sql.length; i++) {
    const c = sql[i];
    if (c === '<') {
      return -1;
    }
    if (c === '(') {
      depth++;
    } else if (c === ')') {
      depth--;
    } else if (depth === 0 && !/[\w$#.]/.test(sql[i - 1] ?? ' ') && FROM.test(sql.slice(i, i + 5))) {
      return i;
    }
  }
  return -1;
}

/** Splits on commas outside parentheses. Undefined if an item is empty or contains `${...}` or an element. */
function split(sql: string, from: number, to: number): Array<[number, number]> | undefined {
  const spans: Array<[number, number]> = [];
  let depth = 0;
  let itemStart = from;
  for (let i = from; i <= to; i++) {
    const c = i === to ? ',' : sql[i];
    if (c === '\0' || c === '<') {
      return undefined;
    }
    if (c === '(') {
      depth++;
    } else if (c === ')') {
      depth--;
    } else if (c === ',' && depth === 0) {
      if (sql.slice(itemStart, i).trim().length === 0) {
        return undefined;
      }
      spans.push([itemStart, i]);
      itemStart = i + 1;
    }
  }
  return spans;
}

/** The name a value goes by: `#{orderId,...}` → orderId, `o.ORDER_ID` → ORDER_ID, `x AS ORDER_ID` → ORDER_ID. */
function valueName(value: string): string {
  const parameter = /^#\{\s*([\w$.]+)/.exec(value);
  if (parameter) {
    return parameter[1].split('.').pop() ?? '';
  }
  const last = /([\w$#]+)"?\s*$/.exec(value);
  return last ? last[1] : '';
}

function normalize(name: string): string {
  return name.replace(/_/g, '').toLowerCase();
}
