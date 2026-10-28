# Java の Mapper と XML の結び付きの検証

実行すると例外で落ちる、Java の Mapper メソッドと mapper XML の食い違いを見つける
2 つのルールの仕様と設計判断をまとめたものです。

## `mybatis-lint/unknown-parameter-property`(error)

`#{...}` / `${...}` / `<if test>` などで、引数のオブジェクトが持っていないプロパティを
読もうとしているものを報告します。MyBatis は実行時に
`ReflectionException: There is no getter for property named '...'` で失敗します。

### 引数が `@Param` のない Bean 1 つの場合

MyBatis は引数のオブジェクトそのものをパラメータにするので、`#{userName}` は
Bean の `userName` プロパティを指します。

```java
int insertUser(UserDomain user);
```

```xml
<insert id="insertUser">
  INSERT INTO users VALUES (#{userId}, #{userNmae})  <!-- userNmae → error -->
</insert>
```

`#{user.userId}` のように引数名を書くと、Bean の `user` プロパティを探して失敗するので、
これも報告します。

### `@Param` を付けた引数の場合

`#{user.userNmae}` の `userNmae` が、`@Param("user")` を付けた引数の型のプロパティに
あるかを見ます。`param1` などの名前でも同じです。`user` の後ろのプロパティ 1 つだけを
見て、`#{user.address.street}` の `street` 以降は見ません。`<if test>` の中の
`user.userNmae` は、今は見ていません。

### プロパティの決め方

MyBatis が値を**読める**名前です。getter があれば getter を、なければフィールドから直接読むので、

- フィールド名(private を含む)
- 引数なしの `getXxx()` / `isXxx()` から求めた名前(getter だけの名前も読める)
- Lombok(`@Data`、`@Getter` など)。`boolean isActive` フィールドの `active` も含む
- ワークスペースにある親クラスのプロパティ

### 検証しない場合(フェイルオープン)

- 引数の型が、ワークスペースにただ 1 つの具体クラスとして見つからない
  (`String` などの単純型、インターフェース、別モジュールのクラス、同じ名前のクラスが複数)
- **ワークスペースに子クラスがあるクラスと抽象クラス**。実行時に子クラスのオブジェクトが
  渡されると、子クラスのプロパティも読めるため
- 親クラスがワークスペースにない、Lombok の `@Accessors` / `@Delegate` を使っている
- `Map` の引数、コレクションや配列の引数(こちらは `undefined-parameter` で検証する)
- `<bind>` や `<foreach>` の変数と同じ名前(statement 全体で引数より優先するものとして扱う)

## `mybatis-lint/missing-mapper-statement`(warning)

Java の Mapper メソッドに、同じ id の `<select>` / `<insert>` / `<update>` / `<delete>` が
mapper XML にないものを、**Java ファイルのメソッド名に**報告します。そのメソッドを呼ぶと
`BindingException: Invalid bound statement (not found)` で失敗します。メソッド名を変えたときに
XML の id を直し忘れるとよく起きます。

```java
public interface UserMapper {
    List<UserDomain> findByNmae(String name);  // XML の id は findByName → warning
}
```

同じ namespace の mapper XML が複数ファイルに分かれていても、まとめて探します。

### 検証しない場合(フェイルオープン)

- そのインターフェースの FQCN を namespace にした mapper XML がワークスペースにない
  (注釈だけで書いた Mapper や、XML が別モジュールにある Mapper)
- ほかのインターフェースに extends されている。MyBatis は継承したメソッドの statement を
  子の namespace から先に探すので、子の XML で実装されていることがある
- 親インターフェースから継承したメソッド(宣言したインターフェースで判断する)
- `default` / `static` メソッド、`@Select` などの注釈や `@...Provider` で SQL を書いたメソッド
- 同じ FQCN のインターフェースが複数ある
- その namespace の mapper XML が壊れていて読めない(編集中など)。namespace も
  読めない壊れた mapper XML があるときは、すべての Mapper を検証しない

severity を warning にしているのは、呼ばれていないメソッドなら実害がないことと、
ワークスペースの外にある仕組みで statement が追加される可能性を否定しきれないためです。

## 手動確認

```sh
cd analyzer
./gradlew jar
java -jar build/libs/analyzer.jar ../fixtures/mapper-binding-validation
```

`MemberMapper.xml` に error が 2 件(`#{memberNmae}` と `#{member.memberNmae}`)、
`MemberMapper.java` に warning が 1 件(`findByNmae`)出ます。
