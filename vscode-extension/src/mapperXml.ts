/**
 * A small, forgiving XML reader for mapper files, for the SQL preview. Node has
 * no XML parser built in and the extension avoids runtime dependencies, so this
 * reads just what mapper XML uses: elements, attributes, text, CDATA (kept as
 * text) and comments (dropped). Unclosed elements are closed at the end instead
 * of failing, so a file being edited still previews.
 */

export interface XmlElement {
  kind: 'element';
  name: string;
  attributes: Map<string, string>;
  children: XmlNode[];
  /** Offset of the `<` of the start tag. */
  start: number;
  /** Offset just after the end tag (or the start tag, if self-closing). */
  end: number;
}

export interface XmlText {
  kind: 'text';
  text: string;
}

export type XmlNode = XmlElement | XmlText;

const ENTITIES: Record<string, string> = { lt: '<', gt: '>', amp: '&', quot: '"', apos: "'" };

/** The root element of the document, or undefined when there is none. */
export function parseXml(text: string): XmlElement | undefined {
  const document: XmlElement = { kind: 'element', name: '#document', attributes: new Map(), children: [], start: 0, end: text.length };
  const stack: XmlElement[] = [document];
  let i = 0;
  while (i < text.length) {
    const open = text.indexOf('<', i);
    const textEnd = open < 0 ? text.length : open;
    if (textEnd > i) {
      appendText(stack[stack.length - 1], decode(text.slice(i, textEnd)));
    }
    if (open < 0) {
      break;
    }
    if (text.startsWith('<!--', open)) {
      const close = text.indexOf('-->', open + 4);
      i = close < 0 ? text.length : close + 3;
    } else if (text.startsWith('<![CDATA[', open)) {
      const close = text.indexOf(']]>', open + 9);
      appendText(stack[stack.length - 1], text.slice(open + 9, close < 0 ? text.length : close));
      i = close < 0 ? text.length : close + 3;
    } else if (text.startsWith('<?', open) || text.startsWith('<!', open)) {
      // The XML declaration and DOCTYPE carry nothing the preview needs.
      const close = tagEnd(text, open);
      i = close < 0 ? text.length : close + 1;
    } else if (text.startsWith('</', open)) {
      const close = tagEnd(text, open);
      const name = text.slice(open + 2, close < 0 ? text.length : close).trim();
      // Close up to the matching element; an end tag nothing matches is ignored.
      const index = findLastIndex(stack, (element) => element.name === name);
      if (index > 0) {
        for (let j = stack.length - 1; j >= index; j--) {
          stack[j].end = close < 0 ? text.length : close + 1;
        }
        stack.length = index;
      }
      i = close < 0 ? text.length : close + 1;
    } else {
      const close = tagEnd(text, open);
      const inside = text.slice(open + 1, close < 0 ? text.length : close);
      const selfClosing = inside.endsWith('/');
      const name = /^[\w:.-]+/.exec(inside)?.[0];
      if (!name) {
        appendText(stack[stack.length - 1], '<');
        i = open + 1;
        continue;
      }
      const element: XmlElement = {
        kind: 'element',
        name,
        attributes: parseAttributes(inside.slice(name.length)),
        children: [],
        start: open,
        end: close < 0 ? text.length : close + 1,
      };
      stack[stack.length - 1].children.push(element);
      if (!selfClosing) {
        stack.push(element);
      }
      i = close < 0 ? text.length : close + 1;
    }
  }
  return document.children.find((node): node is XmlElement => node.kind === 'element');
}

/** The `>` that ends a tag starting at `open`; `>` inside quoted attribute values does not count. */
function tagEnd(text: string, open: number): number {
  let quote = '';
  for (let i = open + 1; i < text.length; i++) {
    const c = text[i];
    if (quote) {
      if (c === quote) {
        quote = '';
      }
    } else if (c === '"' || c === "'") {
      quote = c;
    } else if (c === '>') {
      return i;
    }
  }
  return -1;
}

function parseAttributes(text: string): Map<string, string> {
  const attributes = new Map<string, string>();
  for (const match of text.matchAll(/([\w:.-]+)\s*=\s*(["'])([\s\S]*?)\2/g)) {
    attributes.set(match[1], decode(match[3]));
  }
  return attributes;
}

function appendText(parent: XmlElement, text: string): void {
  const last = parent.children[parent.children.length - 1];
  if (last?.kind === 'text') {
    last.text += text;
  } else {
    parent.children.push({ kind: 'text', text });
  }
}

function decode(text: string): string {
  return text.replace(/&(#x[0-9a-fA-F]+|#\d+|\w+);/g, (entity, name: string) => {
    if (name.startsWith('#x')) {
      return String.fromCodePoint(parseInt(name.slice(2), 16));
    }
    if (name.startsWith('#')) {
      return String.fromCodePoint(parseInt(name.slice(1), 10));
    }
    return ENTITIES[name] ?? entity;
  });
}

function findLastIndex<T>(items: T[], predicate: (item: T) => boolean): number {
  for (let i = items.length - 1; i >= 0; i--) {
    if (predicate(items[i])) {
      return i;
    }
  }
  return -1;
}

/** Child elements of an element. */
export function childElements(element: XmlElement): XmlElement[] {
  return element.children.filter((node): node is XmlElement => node.kind === 'element');
}
