package com.example.focal;

public class MathUtils {
    public int add(int a, int b) {
        return a + b;
    }

    public int mul(int a, int b) {
        return a * b;
    }

    /** Usa SOLO metodi interni -> perfetto per test "unit" */
    public int sumAndScale(int a, int b) {
        return mul(add(a, b), 2);
    }
}
