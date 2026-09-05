package com.chargebee.ems.fixture;

import java.util.List;

/** Fixture for LoadTimeWeavingTest — loaded only through a fresh loader in-test. */
public final class LoadTimeFixture {

    private LoadTimeFixture() {
    }

    public static long run() {
        List<String> names = List.of("alpha", "beta", "gamma");
        return names.stream()
                .filter(name -> name.length() > 4) // lambda body line
                .count();
    }
}
