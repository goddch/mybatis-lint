/**
 * Finds, in a mapper XML document, which INSERT column each value of
 * `VALUES (...)` or each item of the `SELECT` list belongs to, so the editor
 * can show the column name as an inlay hint in front of it.
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
    });
  });
  return hints;
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
