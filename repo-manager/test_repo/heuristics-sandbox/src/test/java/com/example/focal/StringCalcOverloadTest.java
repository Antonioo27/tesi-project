package com.example.focal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class StringCalcOverloadTest {
    @Test
    void sum_overloads_are_matched_by_arity() {
        StringCalc sc = new StringCalc();
        assertEquals(5, sc.sum(2, 3)); // producer DIRECT -> sum(int,int)
        assertEquals(6, sc.sum(1, 2, 3)); // producer DIRECT -> sum(int,int,int)
    }
}
