package com.example;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 引数なしのコンストラクタがあるので、MyBatis は列を名前だけで書き込む。 */
@Data
@NoArgsConstructor(force = true)
@AllArgsConstructor
public class ItemDomain {
    private final Long itemId;
    private final String itemName;
}
