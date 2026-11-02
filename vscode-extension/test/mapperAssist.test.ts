import assert from 'node:assert/strict';
import { test } from 'node:test';

import { parseJavaType } from '../src/javaSource';
import { completionsAt, hoverAt, typesFromSources } from '../src/mapperAssist';
import type { JavaLookup } from '../src/mapperAssist';

const SOURCES = [
  `package com.example;

import java.util.List;
import com.example.domain.UserDomain;
import org.apache.ibatis.annotations.Param;

/** Users. */
public interface UserMapper {
    UserDomain findById(@Param("userId") Long id);

    List<UserDomain> search(UserDomain condition);

    int update(@Param("user") UserDomain user, @Param("status") String status);

    // Calls like find(x) in comments are ignored.
    default int count() { return search(null).size(); }
}`,
  `package com.example.domain;

import lombok.Data;

@Data
public class UserDomain extends BaseDomain {
    private static final long serialVersionUID = 1L;
    @Size(max = 10)
    private String userName;
    private final Long userId;
    private AddressDomain address;
    private List<String> tags = new ArrayList<>();

    public String getDisplayName() { return userName; }
}`,
  `package com.example.domain;

public abstract class BaseDomain {
    private String createdBy;
}`,
  `package com.example.domain;

public class AddressDomain {
    private String city;
}`,
];

const lookup: JavaLookup = {
  findTypes: async (simpleName) => typesFromSources(SOURCES).filter((type) => type.name === simpleName),
};

const XML = `<mapper namespace="com.example.UserMapper">
  <sql id="columns">ID</sql>
  <resultMap id="userMap" type="com.example.domain.UserDomain"/>
  <select id="findById" resultType="com.example.domain.UserDomain">
    SELECT u.ID AS userId, u.NAME AS user_name FROM users u WHERE u.ID = #{userId}
  </select>
  <select id="search" resultType="userDomain">
    SELECT <include refid="columns"/> FROM users WHERE name = #{userName} AND city = #{address.city}
  </select>
  <update id="update">UPDATE users SET name = #{user.userName}, status = #{status} WHERE id = #{user.userId}</update>
</mapper>`;

function at(text: string, needle: string, delta = 0): number {
  const index = text.indexOf(needle);
  assert.ok(index >= 0, `${needle} not found`);
  return index + delta;
}

test('reads fields, accessors, superclass and method parameters from Java source', () => {
  const domain = parseJavaType(SOURCES[1]);
  assert.equal(domain?.qualifiedName, 'com.example.domain.UserDomain');
  assert.equal(domain?.superclass, 'BaseDomain');
  assert.deepEqual(
    domain?.properties.map((property) => `${property.type} ${property.name}`),
    ['String userName', 'Long userId', 'AddressDomain address', 'List<String> tags', 'String displayName'],
  );
  const mapper = parseJavaType(SOURCES[0]);
  assert.equal(mapper?.isInterface, true);
  assert.deepEqual(
    mapper?.methods.map((method) => method.name),
    ['findById', 'search', 'update', 'count'],
  );
  assert.deepEqual(mapper?.methods[2].parameters, [
    { name: 'user', type: 'UserDomain', alias: 'user' },
    { name: 'status', type: 'String', alias: 'status' },
  ]);
  assert.equal(mapper?.methods[0].signature, 'UserDomain findById(@Param("userId") Long id)');
});

test('describes parameters and properties under the cursor', async () => {
  assert.equal(
    (await hoverAt(XML, at(XML, '#{userId}', 4), lookup))?.markdown,
    '`Long userId` — parameter of UserMapper.findById',
  );
  // A single bean without @Param: names are its properties, including inherited ones.
  assert.equal(
    (await hoverAt(XML, at(XML, '#{userName}', 4), lookup))?.markdown,
    '`String userName` — property of com.example.domain.UserDomain',
  );
  assert.equal(
    (await hoverAt(XML, at(XML, 'address.city', 9), lookup))?.markdown,
    '`String city` — property of com.example.domain.AddressDomain',
  );
  assert.equal(
    (await hoverAt(XML, at(XML, 'user.userId', 7), lookup))?.markdown,
    '`Long userId` — property of com.example.domain.UserDomain',
  );
  // AS aliases match properties ignoring case and underscores.
  assert.equal(
    (await hoverAt(XML, at(XML, 'user_name', 2), lookup))?.markdown,
    '`String userName` — property of com.example.domain.UserDomain',
  );
});

test('describes types and statement ids in attributes', async () => {
  const type = await hoverAt(XML, at(XML, 'resultType="com.example', 15), lookup);
  assert.ok(type?.markdown.startsWith('`com.example.domain.UserDomain`'), type?.markdown);
  assert.ok(type?.markdown.includes('- `String createdBy`'), type?.markdown);
  // resultType="userDomain" is a type alias for UserDomain.
  assert.ok((await hoverAt(XML, at(XML, '"userDomain"', 3), lookup))?.markdown.includes('`Long userId`'));
  assert.equal(
    (await hoverAt(XML, at(XML, 'id="findById"', 6), lookup))?.markdown,
    '```java\nUserDomain findById(@Param("userId") Long id)\n```',
  );
});

test('offers parameter names, properties, aliases and ids', async () => {
  const labels = async (text: string, offset: number) =>
    (await completionsAt(text, offset, lookup))?.items.map((item) => item.label);

  const typing = XML.replace('#{status}', '#{st');
  assert.deepEqual(await labels(typing, at(typing, '#{st', 4)), ['user', 'status']);
  assert.equal((await completionsAt(typing, at(typing, '#{st', 4), lookup))?.start, at(typing, '#{st', 2));

  const nested = XML.replace('#{user.userId}', '#{user.}');
  assert.deepEqual(await labels(nested, at(nested, '#{user.}', 7)), [
    'userName', 'userId', 'address', 'tags', 'displayName', 'createdBy',
  ]);

  const alias = XML.replace('AS user_name', 'AS ');
  assert.ok((await labels(alias, at(alias, 'AS  FROM', 3)))?.includes('mailAddress') === false);
  assert.ok((await labels(alias, at(alias, 'AS  FROM', 3)))?.includes('userName'));

  assert.deepEqual(await labels(XML, at(XML, 'refid="columns"', 7)), ['columns']);
  const resultMap = XML.replace('resultType="userDomain"', 'resultMap="other,"');
  assert.deepEqual(await completionsAt(resultMap, at(resultMap, 'other,', 6), lookup), {
    items: [{ label: 'userMap', detail: '<resultMap> in this file', kind: 'id' }],
    start: at(resultMap, 'other,', 6),
  });
});

test('offers nothing it cannot work out', async () => {
  // Outside statements and expressions, unknown namespaces, and options after a comma.
  assert.equal(await hoverAt(XML, at(XML, 'SELECT'), lookup), undefined);
  assert.equal(await completionsAt(XML, at(XML, 'FROM users WHERE'), lookup), undefined);
  const unknown = XML.replace('com.example.UserMapper', 'com.example.Missing');
  assert.equal(await hoverAt(unknown, at(unknown, '#{userId}', 4), lookup), undefined);
  const withOptions = XML.replace('#{status}', '#{status,jdbcType=VARCHAR}');
  assert.equal(await hoverAt(withOptions, at(withOptions, 'jdbcType', 2), lookup), undefined);
});
