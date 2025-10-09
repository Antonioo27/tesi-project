package com.example.focal;

import org.junit.jupiter.api.Test;

class NoAssertionTest {
    @Test
    void does_nothing() {
        MathUtils mu = new MathUtils();
        mu.add(1, 2);
        mu.mul(2, 3);
        // nessun assert
    }
}
