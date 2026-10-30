import assert from 'node:assert/strict';
import { test } from 'node:test';

import { displayWidth, insertColumnHints, lineEndHints } from '../src/insertColumnHints';

/** Each hint as "COLUMN→text at the hint", so tests read like the editor. */
function render(text: string): string[] {
  return insertColumnHints(text).map((hint) => `${hint.column}→${text.slice(hint.offset).split(/[,\n]/)[0].trim()}`);
}

test('pairs each VALUES value with its column', () => {
  const xml = `<mapper namespace="a.B">
  <insert id="insert">
    INSERT INTO orders (ORDER_ID, AMOUNT, NOTE)
    VALUES (#{orderId,jdbcType=NUMERIC}, NVL(#{amount}, 0), 'a, b')
  </insert>
</mapper>`;

  assert.deepEqual(render(xml), ['ORDER_ID→#{orderId', 'AMOUNT→NVL(#{amount}', "NOTE→'a"]);
  assert.deepEqual(
    insertColumnHints(xml).map((hint) => hint.sameName),
    [true, false, false],
  );
});

test('pairs each SELECT item with its column, ignoring comments and CDATA markers', () => {
  const xml = `<insert id="copy"><![CDATA[
    INSERT INTO orders_history (ORDER_ID, AMOUNT, CREATED_BY)
    SELECT o.ORDER_ID, -- the id
           CASE WHEN o.AMOUNT < 0 THEN 0 ELSE o.AMOUNT END,
           #{userId}
      FROM orders o
  ]]></insert>`;

  assert.deepEqual(render(xml), ['ORDER_ID→o.ORDER_ID', 'AMOUNT→CASE WHEN o.AMOUNT < 0 THEN 0 ELSE o.AMOUNT END', 'CREATED_BY→#{userId}']);
});

test('shows the columns that do have a value when the counts differ', () => {
  const xml = `<insert id="insert">INSERT INTO t (A, B, C) VALUES (#{a}, #{b})</insert>`;
  assert.deepEqual(render(xml), ['A→#{a}', 'B→#{b})</insert>']);
});

test('skips statements whose columns or values depend on dynamic elements', () => {
  const dynamic = `<insert id="insert">
    INSERT INTO t (A
    <if test="b != null">, B</if>)
    VALUES (#{a})
  </insert>
  <insert id="insertDollar">INSERT INTO t (A, B) VALUES (#{a}, \${b})</insert>
  <insert id="insertInclude">INSERT INTO t (A, B) SELECT <include refid="cols"/> FROM s</insert>
  <insert id="noColumns">INSERT INTO t VALUES (#{a})</insert>
  <insert id="empty"/>`;
  assert.deepEqual(render(dynamic), []);
});

/** The document with each line-end hint written in, as the editor would show it. */
function withLineEndHints(text: string, tabSize = 4): string {
  let shown = text;
  for (const hint of lineEndHints(text, insertColumnHints(text), tabSize).reverse()) {
    shown = shown.slice(0, hint.offset) + ' '.repeat(hint.padding) + hint.text + shown.slice(hint.offset);
  }
  return shown;
}

test('shows the columns at the end of each line, lined up within a statement', () => {
  const xml = `<insert id="copy">
    INSERT INTO orders_history (ORDER_ID, CUSTOMER_NAME, AMOUNT, NOTE)
    SELECT o.ORDER_ID,
           o.NAME,
           NVL(o.AMOUNT, 0), 'x'
      FROM orders o
  </insert>`;

  assert.equal(
    withLineEndHints(xml),
    `<insert id="copy">
    INSERT INTO orders_history (ORDER_ID, CUSTOMER_NAME, AMOUNT, NOTE)
    SELECT o.ORDER_ID,            ORDER_ID
           o.NAME,                CUSTOMER_NAME
           NVL(o.AMOUNT, 0), 'x'  AMOUNT, NOTE
      FROM orders o
  </insert>`,
  );
});

test('measures tabs and full-width characters as the editor shows them', () => {
  assert.equal(displayWidth('\tab', 4), 6);
  assert.equal(displayWidth('a\tb', 4), 5);
  assert.equal(displayWidth('-- 金額', 4), 7);

  const xml = '<insert id="i">\n\tINSERT INTO t (A, B)\n\tVALUES (#{a}, -- 金額\n\t        #{b})\n</insert>';
  const shown = withLineEndHints(xml).split('\n');
  assert.equal(displayWidth(shown[2], 4) - 'A'.length, displayWidth(shown[3], 4) - 'B'.length);
});

test('handles CRLF line endings', () => {
  const xml = '<insert id="i">\r\n  INSERT INTO t (A, B)\r\n  VALUES (#{a},\r\n          #{b})\r\n</insert>';
  const [first, second] = lineEndHints(xml, insertColumnHints(xml), 4);
  assert.equal(xml[first.offset], '\r');
  assert.equal(xml[second.offset], '\r');
});
