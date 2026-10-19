package com.chargebee.cbappfixture;

import java.util.Arrays;

/** Load-time weaving fixture deliberately compiled to Java 8 bytecode. */
public final class LegacyService {
    public static int calculate() {
        return Arrays.asList(1, 2, 3).stream().mapToInt(value -> value * 2).sum();
    }

    public static void fail() {
        throw new IllegalStateException("expected smoke-test failure");
    }
}
