package com.example.focal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class BazServiceIT {
    @Test
    void ping_ok() {
        assertEquals("baz-ok", new BazService().ping());
    }
}
