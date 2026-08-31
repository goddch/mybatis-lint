package com.example;

import org.apache.ibatis.annotations.Param;

public interface ValidMapper {
    String findByStatus(@Param("status") String status, String name);
    String findByRequest(RequestModel request);
    String findByItems(java.util.List<String> items);
}

class RequestModel { public Long userId; }
