package com.example.focal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class OrderServiceIT {
    @Test
    void placeOrder_end_to_end_no_mocks() {
        OrderRepository repo = new InMemoryOrderRepository();
        InventoryService inv = new InventoryService(repo);
        PaymentGateway gw = new PaymentGateway();
        EmailSender es = new EmailSender();
        OrderService svc = new OrderService(inv, gw, es);

        boolean ok = svc.placeOrder("ABC", 2, "a@b.com"); // chiama più classi “reali”
        assertTrue(ok);
    }
}
