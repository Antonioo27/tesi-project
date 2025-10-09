package com.example.focal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderServiceUnitTest {

    @Mock
    OrderRepository repo; // @Mock -> raccolto dall’euristica
    @Mock
    PaymentGateway gateway; // @Mock
    @Mock
    EmailSender email; // @Mock

    @Test
    void placeOrder_happyPath_uses_stubbing_and_verify() {
        InventoryService inv = new InventoryService(repo);
        OrderService svc = new OrderService(inv, gateway, email);

        when(repo.stockFor("ABC")).thenReturn(10); // STUBBING (when)
        when(gateway.charge(20.0)).thenReturn(true); // STUBBING (when)

        boolean ok = svc.placeOrder("ABC", 2, "u@e.com");
        assertTrue(ok);

        verify(repo).decrementStock("ABC", 2); // VERIFY + verified method
        verify(email).send(anyString(), anyString()); // VERIFY + verified method
    }

    @Test
    void local_mock_example_counts_as_mock_creation() {
        PaymentGateway gw = mock(PaymentGateway.class); // Mockito.mock(..)
        when(gw.charge(20.0)).thenReturn(true); // STUBBING
        assertTrue(gw.charge(20.0));
        verify(gw).charge(20.0); // VERIFY
    }
}
