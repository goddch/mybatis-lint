package com.example;

import java.util.List;
import java.util.Map;

public interface SearchMapper {
    List<Map<String, Object>> searchInvalid(SearchCondition condition);

    List<Map<String, Object>> searchValid(SearchCondition condition);
}
