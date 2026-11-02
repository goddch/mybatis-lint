import assert from 'node:assert/strict';
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
