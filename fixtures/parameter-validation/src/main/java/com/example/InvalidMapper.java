package com.example;

import org.apache.ibatis.annotations.Param;

public interface InvalidMapper {
    String findWithStatus(@Param("status") String status, String name);
    String findWithMap(java.util.Map<String, Object> params);

    // 引数名は "ids" だが、対応する XML では foreach の collection 属性に
    // "missingIds" というタイプミスを書いている想定のメソッド。
    String findByIds(java.util.List<String> ids);
}
