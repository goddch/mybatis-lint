package com.example;

import lombok.Data;

/** 親クラスのプロパティ(createdBy)も別名の対応先になることを確かめる題材。 */
@Data
public abstract class BaseDomain {
    private String createdBy;
}
