package com.example;

import java.util.List;
import java.util.Map;

public interface OrderMapper {
    List<Map<String, Object>> findAll();

    // findByCustomerName から名前を変えたが、XML の id を直し忘れている(XML 側に warning)。
    List<Map<String, Object>> findByCustomer(String customerName);
}
