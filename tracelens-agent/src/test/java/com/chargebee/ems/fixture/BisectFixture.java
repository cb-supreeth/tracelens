package com.chargebee.ems.fixture;

import java.util.List;

/** Fixture for BisectTest — referenced only by name so it stays unloaded until woven. */
public final class BisectFixture {

    private BisectFixture() {
    }

    public static long run() {
        List<String> names = List.of("alpha", "beta", "gamma");
        return names.stream()
                .filter(name -> name.length() > 4) // lambda body line
                .count();
    }
}
