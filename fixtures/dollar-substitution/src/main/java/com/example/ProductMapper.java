package com.example;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface ProductMapper {
    List<Map<String, Object>> searchInvalid(@Param("name") String name, @Param("ids") String ids);

    List<Map<String, Object>> searchValid(@Param("name") String name, @Param("categoryId") Long categoryId,
                                          @Param("sortColumn") String sortColumn);
}
