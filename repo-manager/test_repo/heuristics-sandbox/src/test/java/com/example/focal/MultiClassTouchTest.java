package com.example.focal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class MultiClassTouchTest {
    @Test
    void touches_two_prod_classes_directly() {
        MathUtils mu = new MathUtils();
        EmailSender es = new EmailSender();
        mu.add(1, 2); // call #1 -> classe A
        es.format("X"); // call #2 -> classe B
        assertTrue(true);
        // uniqueClassCount = 2, totalMethodCalls >= 2 -> concentration < 1.0
    }
}
