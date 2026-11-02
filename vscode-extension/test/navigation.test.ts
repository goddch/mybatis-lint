import assert from 'node:assert/strict';
import { test } from 'node:test';

import {
  findJavaMethod,
  findXmlIds,
  javaMethodAt,
  javaResultMapOccurrences,
  mapperNamespace,
  occurrencesBySymbol,
  symbolAt,
  symbolKey,
  symbolOccurrences,
  xmlReferenceAt,
} from '../src/navigation';

const XML = `<?xml version="1.0" encoding="UTF-8"?>
<mapper namespace="com.example.UserMapper">
  <resultMap id="base" type="com.example.User"/>
  <resultMap id="detail" type="com.example.User" extends="base"/>
  <sql id="columns">ID, NAME</sql>
  <select id="findById" resultMap="detail,com.example.OtherMapper.summary">
    SELECT <include refid="columns"/>, <include refid="com.example.Common.audit"/> FROM users
  </select>
  <update id="\${dynamicId}">UPDATE users</update>
</mapper>`;

/** Offset of the n-th occurrence of `needle`, plus `delta`. */
function at(text: string, needle: string, delta = 1, occurrence = 1): number {
  let index = -1;
  for (let i = 0; i < occurrence; i++) {
    index = text.indexOf(needle, index + 1);
  }
  assert.ok(index >= 0, `${needle} not found`);
  return index + delta;
}

test('reads the namespace of the mapper', () => {
  assert.equal(mapperNamespace(XML), 'com.example.UserMapper');
  assert.equal(mapperNamespace('<configuration/>'), undefined);
});

test('finds what the cursor is on in mapper XML', () => {
  assert.deepEqual(xmlReferenceAt(XML, at(XML, 'findById')), {
    kind: 'statement',
    namespace: 'com.example.UserMapper',
    id: 'findById',
  });
  assert.deepEqual(xmlReferenceAt(XML, at(XML, '"columns"/>')), {
    kind: 'sql',
    namespace: 'com.example.UserMapper',
    id: 'columns',
  });
  assert.deepEqual(xmlReferenceAt(XML, at(XML, 'com.example.Common.audit')), {
    kind: 'sql',
    namespace: 'com.example.Common',
    id: 'audit',
  });
  // resultMap="a,b": the id under the cursor.
  assert.deepEqual(xmlReferenceAt(XML, at(XML, 'detail,')), {
    kind: 'resultMap',
    namespace: 'com.example.UserMapper',
    id: 'detail',
  });
  assert.deepEqual(xmlReferenceAt(XML, at(XML, 'OtherMapper.summary', 14)), {
    kind: 'resultMap',
    namespace: 'com.example.OtherMapper',
    id: 'summary',
  });
  assert.deepEqual(xmlReferenceAt(XML, at(XML, 'extends="base"', 10)), {
    kind: 'resultMap',
    namespace: 'com.example.UserMapper',
    id: 'base',
  });
});

test('offers nothing outside references', () => {
  assert.equal(xmlReferenceAt(XML, at(XML, 'SELECT')), undefined);
  assert.equal(xmlReferenceAt(XML, at(XML, '"com.example.User"', 3)), undefined);
  assert.equal(xmlReferenceAt(XML, at(XML, 'dynamicId')), undefined);
  // The id of a <sql> or <resultMap> is a target, not a reference.
  assert.equal(xmlReferenceAt(XML, at(XML, 'id="columns"', 5)), undefined);
});

test('finds the ids that references point to', () => {
  assert.deepEqual(findXmlIds(XML, ['sql'], 'columns'), [XML.indexOf('columns')]);
  assert.deepEqual(findXmlIds(XML, ['resultMap'], 'base'), [XML.indexOf('base')]);
  assert.deepEqual(findXmlIds(XML, ['select', 'insert', 'update', 'delete'], 'findById'), [XML.indexOf('findById')]);
  assert.deepEqual(findXmlIds(XML, ['sql'], 'missing'), []);
  const qualified = '<mapper namespace="a.B"><select id="a.B.find">SELECT 1</select></mapper>';
  assert.deepEqual(findXmlIds(qualified, ['select'], 'find'), [qualified.indexOf('a.B.find')]);
});

const JAVA = `package com.example;

import java.util.List;
import java.util.Map;

/** Calls findById(id) are documented here. */
public interface UserMapper {
    @Select("SELECT * FROM users WHERE name = findByName(#{name})")
    User findBySql(String name);

    User findById(@Param("id") Long id);

    List<User> findAll();

    Map<String, Object> findMap(@Param("a") Long a,
                                @Param("b") Long b);

    default User findOrDefault(Long id) {
        User user = findById(id);
        return user == null ? findAll().get(0) : user;
    }
}`;

test('finds the declaration of a mapper method, not calls or comments', () => {
  assert.equal(findJavaMethod(JAVA, 'findById'), at(JAVA, 'findById(@Param', 0));
  assert.equal(findJavaMethod(JAVA, 'findAll'), at(JAVA, 'findAll();', 0));
  assert.equal(findJavaMethod(JAVA, 'findMap'), at(JAVA, 'findMap(', 0));
  assert.equal(findJavaMethod(JAVA, 'findByName'), -1);
  assert.equal(findJavaMethod(JAVA, 'missing'), -1);
});

test('reads the mapper method under the cursor in Java', () => {
  assert.deepEqual(javaMethodAt(JAVA, at(JAVA, 'findById(@Param', 3)), {
    namespace: 'com.example.UserMapper',
    id: 'findById',
  });
  assert.deepEqual(javaMethodAt(JAVA, at(JAVA, 'findMap(', 0)), { namespace: 'com.example.UserMapper', id: 'findMap' });
  // A call inside a default method is not a declaration.
  assert.equal(javaMethodAt(JAVA, at(JAVA, 'findById(id)', 2)), undefined);
  assert.equal(javaMethodAt(JAVA, at(JAVA, 'Long id)', 1)), undefined);
  assert.equal(javaMethodAt('public class UserService { User findById(Long id) { return null; } }', 30), undefined);
});

test('treats names with regular expression characters literally', () => {
  assert.equal(findJavaMethod('interface A { int a$b(); }', 'a$b'), 'interface A { int '.length);
  assert.equal(findJavaMethod('interface A { int ab(); }', 'a.b'), -1);
  assert.equal(findJavaMethod('interface A { int ab(); }', 'a\\b'), -1);
});

const SYMBOLS = `<mapper namespace="a.UserMapper">
  <sql id="columns">ID</sql>
  <resultMap id="base" type="map"/>
  <resultMap id="detail" type="map" extends="base">
    <collection property="items" select="findItems" resultMap="a.UserMapper.base"/>
  </resultMap>
  <select id="find" resultMap="detail, base">SELECT <include refid="columns"/>, <include refid="b.Other.columns"/></select>
  <select id="a.UserMapper.findItems" resultType="map">SELECT 1</select>
</mapper>`;

/** Each occurrence as "text at the range" plus "*" for definitions. */
function show(text: string, occurrences: Array<{ start: number; end: number; definition?: boolean }>): string[] {
  return occurrences.map((o) => text.slice(o.start, o.end) + (o.definition ? '*' : '') + '@' + o.start);
}

test('finds every definition and reference of a symbol, resolving namespaces', () => {
  const base = symbolOccurrences(SYMBOLS, { kind: 'resultMap', namespace: 'a.UserMapper', id: 'base' });
  assert.deepEqual(show(SYMBOLS, base).map((s) => s.split('@')[0]), ['base*', 'base', 'base', 'base']);
  // In resultMap="a.UserMapper.base" only the id part is covered.
  assert.equal(SYMBOLS.slice(base[2].start - 13, base[2].end), 'a.UserMapper.base');

  const columns = symbolOccurrences(SYMBOLS, { kind: 'sql', namespace: 'a.UserMapper', id: 'columns' });
  assert.deepEqual(show(SYMBOLS, columns).map((s) => s.split('@')[0]), ['columns*', 'columns']);
  assert.deepEqual(
    symbolOccurrences(SYMBOLS, { kind: 'sql', namespace: 'b.Other', id: 'columns' }).map((o) => o.definition),
    [false],
  );

  // A statement id written with its namespace, and <collection select> referring to it.
  const findItems = symbolOccurrences(SYMBOLS, { kind: 'statement', namespace: 'a.UserMapper', id: 'findItems' });
  assert.deepEqual(show(SYMBOLS, findItems).map((s) => s.split('@')[0]), ['findItems', 'findItems*']);
});

test('reads the symbol under the cursor, on definitions and references alike', () => {
  assert.deepEqual(
    (({ kind, namespace, id }) => ({ kind, namespace, id }))(symbolAt(SYMBOLS, SYMBOLS.indexOf('detail, base') + 2)!),
    { kind: 'resultMap', namespace: 'a.UserMapper', id: 'detail' },
  );
  assert.equal(symbolAt(SYMBOLS, SYMBOLS.indexOf('id="columns"') + 5)?.kind, 'sql');
  assert.equal(symbolAt(SYMBOLS, SYMBOLS.indexOf('SELECT 1')), undefined);
});

test('finds @ResultMap values in Java that refer to a resultMap', () => {
  const java = `package a;
public interface UserMapper {
    @ResultMap("base") User find();
    @ResultMap({"a.UserMapper.base", "other"}) User find2();
}`;
  const found = javaResultMapOccurrences(java, { kind: 'resultMap', namespace: 'a.UserMapper', id: 'base' });
  assert.deepEqual(found.map((o) => java.slice(o.start, o.end)), ['base', 'base']);
  assert.equal(java.slice(found[1].start - 13, found[1].end), 'a.UserMapper.base');
});

test('groups occurrences across files by symbol', () => {
  const other = `<mapper namespace="b.Other">
  <select id="find" resultMap="a.UserMapper.base"><include refid="a.UserMapper.columns"/></select>
</mapper>`;
  const grouped = occurrencesBySymbol([
    { file: 'user', text: SYMBOLS },
    { file: 'other', text: other },
  ]);
  const base = grouped.get(symbolKey({ kind: 'resultMap', namespace: 'a.UserMapper', id: 'base' })) ?? [];
  assert.deepEqual(base.map((o) => `${o.file}${o.definition ? '*' : ''}`), ['user*', 'user', 'user', 'user', 'other']);
  const columns = grouped.get(symbolKey({ kind: 'sql', namespace: 'a.UserMapper', id: 'columns' })) ?? [];
  assert.deepEqual(columns.map((o) => o.file), ['user', 'user', 'other']);
});
