package com.example.focal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class BarServiceIntTest {
    @Test
    void ping_ok() {
        assertEquals("bar-ok", new BarService().ping());
    }
}
