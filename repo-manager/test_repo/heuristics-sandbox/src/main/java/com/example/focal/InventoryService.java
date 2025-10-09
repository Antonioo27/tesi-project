package com.example.focal;

public class InventoryService {
    private final OrderRepository repo;

    public InventoryService(OrderRepository repo) {
        this.repo = repo;
    }

    /** true se c’è abbastanza stock e lo riserva (decrementa) */
    public boolean reserve(String sku, int qty) {
        if (repo.stockFor(sku) >= qty) {
            repo.decrementStock(sku, qty);
            return true;
        }
        return false;
    }
}
