package com.example.focal;

public final class StaticUtil {
    private StaticUtil() {
    }

    public static String banner(String s) {
        return "[" + (s == null ? "" : s.toUpperCase()) + "]";
    }

    public static int nowHour() {
        return 12;
    }
}
