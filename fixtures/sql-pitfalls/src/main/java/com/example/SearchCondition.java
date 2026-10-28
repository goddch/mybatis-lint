package com.example;

import lombok.Data;

/** <if test> の比較の題材。status は数値型、type は String。 */
@Data
public class SearchCondition {
    private String name;
    private String type;
    private Integer status;
}
