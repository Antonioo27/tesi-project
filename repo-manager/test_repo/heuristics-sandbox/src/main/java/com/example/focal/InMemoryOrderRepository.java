package com.example.focal;

import java.util.HashMap;
import java.util.Map;

public class InMemoryOrderRepository implements OrderRepository {
    private final Map<String, Integer> stock = new HashMap<>();

    public InMemoryOrderRepository() {
        stock.put("ABC", 10);
        stock.put("XYZ", 0);
    }

    @Override
    public int stockFor(String sku) {
        return stock.getOrDefault(sku, 0);
    }

    @Override
    public void decrementStock(String sku, int qty) {
        stock.put(sku, stockFor(sku) - qty);
    }
}
