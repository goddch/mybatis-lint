package com.example;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface UserMapper {
    User findById(@Param("userId") Long id);

    List<User> search(@Param("name") String name, @Param("ids") List<Long> ids);
}
