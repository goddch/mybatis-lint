package com.example;

import lombok.Data;

/**
 * すべてのフィールドが final なので、Lombok は引数なしのコンストラクタを作らず、
 * フィールドを宣言順に受け取るコンストラクタだけを作る。MyBatis は SELECT 句の列を
 * このコンストラクタへ順に渡す。
 */
@Data
public class OrderDomain {
    private final Long orderId;
    private final String customerName;
    private final java.math.BigDecimal amount;
}
