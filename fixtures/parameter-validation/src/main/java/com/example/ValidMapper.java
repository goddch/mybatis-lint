package com.example;

import org.apache.ibatis.annotations.Param;

public interface ValidMapper {
    String findByStatus(@Param("status") String status, String name);
    String findByRequest(RequestModel request);
    String findByItems(java.util.List<String> items);

    // foreach の collection 属性が引数名 "ids" とそのまま一致する、
    // 最もシンプルな正常系のケース。
    String findByIds(java.util.List<String> ids);

    // foreach の collection 属性がプロパティパス(request.groups)を参照し、
    // かつ外側の foreach の item("group")を内側の foreach の collection で
    // 参照するネスト構造の正常系のケース。
    String findByGroups(RequestModel request);
}

class RequestModel {
    public Long userId;
    // request.groups のようなプロパティパスの検証では、ルート識別子である
    // "request" だけをチェックし、"groups" が実在するフィールドかどうかは
    // 見ない(保守的な設計のため)。そのため groups の型は何であっても良いが、
    // ここではネストした foreach の例として二重リストにしている。
    public java.util.List<java.util.List<String>> groups;
}
