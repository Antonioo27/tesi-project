package com.example.focal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class MathUtilsTest {
    @Test
    void sumAndScale_directAssert() {
        MathUtils mu = new MathUtils();
        // Method call dentro l'assert -> producer DIRECT su MathUtils.sumAndScale
        assertEquals(14, mu.sumAndScale(4, 3));
    }
}
