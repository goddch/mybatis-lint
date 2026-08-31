package com.example;

import org.apache.ibatis.annotations.Param;

public interface InvalidMapper {
    String findWithStatus(@Param("status") String status, String name);
    String findWithMap(java.util.Map<String, Object> params);
}
