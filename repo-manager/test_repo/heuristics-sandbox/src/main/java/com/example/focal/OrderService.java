package com.example.focal;

public class OrderService {
    private static final double UNIT_PRICE = 10.0;
    private final InventoryService inventory;
    private final PaymentGateway gateway;
    private final EmailSender email;

    public OrderService(InventoryService inventory, PaymentGateway gateway, EmailSender email) {
        this.inventory = inventory;
        this.gateway = gateway;
        this.email = email;
    }

    /** flusso “integrato”: inventory -> payment -> email */
    public boolean placeOrder(String sku, int qty, String customerEmail) {
        if (!inventory.reserve(sku, qty))
            return false;
        double amount = qty * UNIT_PRICE;
        if (!gateway.charge(amount))
            return false;
        email.send(customerEmail, "Order for %s x%d confirmed (%.2f)".formatted(sku, qty, amount));
        return true;
    }
}
