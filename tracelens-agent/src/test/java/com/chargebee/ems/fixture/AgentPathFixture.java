package com.chargebee.ems.fixture;

import java.util.List;

/** Fixture for AgentBuilderPathTest — referenced reflectively so it loads only
 *  after the test's agent is installed. */
public final class AgentPathFixture {

    private AgentPathFixture() {
    }

    public static long run() {
        List<String> names = List.of("alpha", "beta", "gamma");
        return names.stream()
                .filter(name -> name.length() > 4) // lambda body line
                .count();
    }
}
