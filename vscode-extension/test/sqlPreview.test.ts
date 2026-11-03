import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';

import { childElements, parseXml } from '../src/mapperXml';
import type { XmlElement } from '../src/mapperXml';
import { previewStatement } from '../src/sqlPreview';
import type { PreviewChoices, ParameterStyle } from '../src/sqlPreview';

const COMMON = parseXml(`<mapper namespace="a.Common">
  <sql id="audit">\${alias}.CREATED_BY, \${alias}.CREATED_AT</sql>
</mapper>`)!;

const MAPPER = parseXml(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="a.UserMapper">
  <!-- a comment with <tags> inside -->
  <sql id="columns">u.ID, u.NAME</sql>
  <select id="search" resultType="map">
    SELECT <include refid="columns"/>, <include refid="a.Common.audit"><property name="alias" value="u"/></include>
      FROM users u
    <where>
      <if test="name != null">AND u.NAME LIKE '%' || #{name} || '%'</if>
      <if test="status != null and status &gt; 0">AND u.STATUS = #{status,jdbcType=NUMERIC}</if>
      <choose>
        <when test="type == 'A'">AND u.TYPE = 'A'</when>
        <otherwise>AND u.TYPE IN ('B', 'C')</otherwise>
      </choose>
      AND u.ID IN
      <foreach collection="ids" item="id" index="i" open="(" separator=", " close=")">#{id}</foreach>
    </where>
     ORDER BY \${sort}
  </select>
  <update id="update"><![CDATA[UPDATE users]]>
    <set>
      <if test="name != null">NAME = #{user.name},</if>
      <if test="mail != null">MAIL = #{user.mail},</if>
    </set>
     WHERE ID = #{user.id} AND AGE < 100
  </update>
</mapper>`)!;

const fragments = (namespace: string, id: string) =>
  [MAPPER, COMMON]
    .filter((mapper) => mapper.attributes.get('namespace') === namespace)
    .flatMap(childElements)
    .find((element) => element.name === 'sql' && element.attributes.get('id') === id);

function statement(id: string): XmlElement {
  return childElements(MAPPER).find((element) => element.attributes.get('id') === id)!;
}

function preview(id: string, choices: PreviewChoices = {}, style: ParameterStyle = 'named', values = {}) {
  return previewStatement(statement(id), 'a.UserMapper', fragments, choices, style, values);
}

test('builds the SQL with every branch on, expanding includes and foreach', () => {
  assert.equal(
    preview('search').sql,
    `SELECT u.ID, u.NAME, u.CREATED_BY, u.CREATED_AT
FROM users u
WHERE u.NAME LIKE '%' || :name || '%'
  AND u.STATUS = :status
  AND u.TYPE = 'A'
  AND u.ID IN
  (:ids_0, :ids_1)
ORDER BY \${sort}`,
  );
});

test('follows the chosen branches like <where> and <choose> do in MyBatis', () => {
  const result = preview('search', {
    ifs: { '5.1': false, '5.3': false },
    chooses: { '5.5': 1 },
    foreachCounts: { '5.7': 3 },
  });
  assert.match(result.sql, /WHERE u\.TYPE IN \('B', 'C'\)\n {2}AND u\.ID IN\n {2}\(:ids_0, :ids_1, :ids_2\)/);

  // With nothing left inside, <where> writes nothing at all.
  const empty = previewStatement(
    parseXml('<select id="x">SELECT 1 FROM dual <where><if test="a">AND a = 1</if></where></select>')!,
    'a.UserMapper',
    fragments,
    { ifs: { '1.0': false } },
  );
  assert.equal(empty.sql, 'SELECT 1 FROM dual');
});

test('lists the switchable branches with stable keys', () => {
  assert.deepEqual(
    preview('search').controls.map((control) => [control.key, control.kind, control.label, control.options]),
    [
      ['5.1', 'if', 'name != null', undefined],
      ['5.3', 'if', 'status != null and status > 0', undefined],
      ['5.5', 'choose', 'choose', ["type == 'A'", 'otherwise']],
      ['5.7', 'foreach', 'ids', undefined],
    ],
  );
});

test('removes the trailing comma in <set> and keeps CDATA text', () => {
  assert.equal(
    preview('update', { ifs: { '1.3': false } }).sql,
    `UPDATE users
SET NAME = :user_name
WHERE ID = :user_id AND AGE < 100`,
  );
});

test('writes parameters as ?, or as the values typed in', () => {
  assert.match(preview('update', {}, 'question').sql, /SET NAME = \?,\n {2}MAIL = \?\nWHERE ID = \? AND/);
  const typed = preview('search', {}, 'values', { name: "'yama'", sort: 'u.ID DESC' }).sql;
  assert.match(typed, /LIKE '%' \|\| 'yama' \|\| '%'/);
  assert.match(typed, /u\.STATUS = :status/);
  assert.match(typed, /ORDER BY u\.ID DESC/);
  assert.deepEqual(
    preview('search').parameters.map((parameter) => `${parameter.key}${parameter.dollar ? '$' : ''}`),
    ['name', 'status', 'ids_0', 'ids_1', 'sort$'],
  );
});

test('keeps string literals as written when tidying spaces', () => {
  const result = previewStatement(parseXml("<select id=\"x\">SELECT 'a   b'   FROM dual</select>")!, 'a.UserMapper', fragments);
  assert.equal(result.sql, "SELECT 'a   b' FROM dual");
});

const AUTO = parseXml(`<select id="auto">
  SELECT * FROM users
  <where>
    <if test="name != null and name != ''">AND NAME = #{name}</if>
    <if test="status != null and status != ''">AND STATUS = #{status}</if>
    <if test="ids != null and ids.size() > 0">AND ID IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></if>
    <choose>
      <when test="type == 'A'">AND TYPE = 'A'</when>
      <when test='type == "B"'>AND TYPE = 'B'</when>
      <otherwise>AND TYPE IS NULL</otherwise>
    </choose>
    <if test="deleted">AND DELETED = 1</if>
  </where>
</select>`)!;

function auto(values: Record<string, string>, choices: PreviewChoices = {}) {
  return previewStatement(AUTO, 'a.UserMapper', fragments, choices, 'values', values, true);
}

test('picks branches from typed values, the way OGNL evaluates the tests', () => {
  const result = auto({ name: "'yama'", status: '0', type: "'B'", deleted: 'false' }, { foreachCounts: { '1.5.1': 0 } });
  // status 0 compared with '' counts as 0, so that <if> is false, as in MyBatis.
  assert.equal(result.sql, `SELECT * FROM users
WHERE NAME = 'yama'
  AND TYPE = 'B'`);
  assert.deepEqual(
    result.controls.map((control) => [control.kind, control.decided ?? control.decidedIndex]),
    [['if', true], ['if', false], ['if', false], ['choose', 1], ['if', false]],
  );
});

test("shows that type == 'A' never matches the String \"A\"", () => {
  const result = auto({ type: "'A'" });
  // Neither <when> matches the String "A", so <otherwise> is used.
  assert.equal(result.controls.find((control) => control.kind === 'choose')?.decidedIndex, 2);
  assert.match(result.sql, /TYPE IS NULL/);
  assert.doesNotMatch(result.sql, /TYPE = 'A'/);
});

test('keeps the manual choice where a test cannot be decided, and offers test-only names', () => {
  const result = auto({}, { ifs: { '1.1': false } });
  const ifs = result.controls.filter((control) => control.kind === 'if');
  // Nothing typed: name, status and deleted are unknown; ids uses the <foreach> count (2).
  assert.deepEqual(ifs.map((control) => control.decided), [undefined, undefined, true, undefined]);
  assert.doesNotMatch(result.sql, /NAME = /);
  assert.match(result.sql, /ID IN \(:ids_0,:ids_1\)/);
  assert.equal(result.controls.find((control) => control.kind === 'choose')?.decidedIndex, undefined);
  assert.deepEqual(
    result.parameters.filter((parameter) => parameter.testOnly).map((parameter) => parameter.key),
    // #{name} is in the <if> switched off, so name is only read by the test.
    ['name', 'type', 'deleted'],
  );
});

test('shows the SQL each branch adds', () => {
  const controls = preview('search').controls;
  assert.equal(controls[0].snippet, "AND u.NAME LIKE '%' || #{name} || '%'");
  assert.deepEqual(controls[2].optionSnippets, ["AND u.TYPE = 'A'", "AND u.TYPE IN ('B', 'C')"]);
  assert.equal(controls[3].snippet, '#{id}');
  const long = previewStatement(parseXml(`<select id="x"><if test="a">${'x'.repeat(100)}</if></select>`)!, 'a', fragments);
  assert.equal(long.controls[0].snippet?.length, 80);
  assert.ok(long.controls[0].snippet?.endsWith('…'));
});

test('builds the same SQL as the analyzer SQL syntax check (shared fixture)', () => {
  // analyzer の DynamicSqlRendererTest も同じ見本と期待値を使う。片方だけ組み立て方を変えると、どちらかが落ちる。
  const dir = path.resolve(__dirname, '..', '..', '..', 'fixtures', 'sql-render');
  const cases = parseXml(fs.readFileSync(path.join(dir, 'RenderCasesMapper.xml'), 'utf8'))!;
  const common = parseXml(fs.readFileSync(path.join(dir, 'CommonMapper.xml'), 'utf8'))!;
  const expected = JSON.parse(fs.readFileSync(path.join(dir, 'expected.json'), 'utf8')) as Record<string, string>;
  const fragments = (namespace: string, id: string) =>
    [cases, common]
      .filter((mapper) => mapper.attributes.get('namespace') === namespace)
      .flatMap(childElements)
      .find((child) => child.name === 'sql' && child.attributes.get('id') === id);

  const statements = childElements(cases).filter((child) => child.name !== 'sql');
  assert.equal(statements.length, 4);
  for (const statement of statements) {
    const id = statement.attributes.get('id') ?? '';
    const { sql } = previewStatement(statement, 'example.RenderCasesMapper', fragments, {}, 'question');
    assert.equal(sql.replace(/\s+/g, ' ').trim(), expected[id], id);
  }
});
