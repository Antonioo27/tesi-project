package com.example.focal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class MultiProducerMathTest {
    @Test
    void two_different_methods_in_two_asserts() {
        MathUtils mu = new MathUtils();
        assertEquals(7, mu.add(3, 4)); // DIRECT -> add
        assertEquals(12, mu.mul(3, 4)); // DIRECT -> mul
    }
}
