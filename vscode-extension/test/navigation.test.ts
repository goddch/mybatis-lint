import assert from 'node:assert/strict';
import { test } from 'node:test';

import { findJavaMethod, findXmlIds, javaMethodAt, mapperNamespace, xmlReferenceAt } from '../src/navigation';

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
