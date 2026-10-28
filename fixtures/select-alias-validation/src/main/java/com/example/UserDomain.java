package com.example;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * mybatis-lint/unknown-select-alias の題材。Lombok の @Data でプロパティを作り、
 * BaseDomain を継承している。getDisplayName() は getter だけなので、
 * MyBatis は displayName に値を書き込めない。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserDomain extends BaseDomain {
    private Long userId;
    private String userName;

    public String getDisplayName() {
        return userName;
    }
}
