package com.example.focal;

public interface OrderRepository {
    int stockFor(String sku);

    void decrementStock(String sku, int qty);
}
