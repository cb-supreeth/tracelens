package com.chargebee.tracelens.spring;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationIdDefaultTest {
    private final TraceLensAutoConfiguration configuration = new TraceLensAutoConfiguration();

    @Test
    void unnamedServiceDefaultsToCbApp() {
        assertThat(configuration.traceLensConfig(properties(), new MockEnvironment()).getApplicationId())
                .isEqualTo("cb-app");
    }

    @Test
    void springApplicationNameStillOverridesFallback() {
        assertThat(configuration.traceLensConfig(properties(),
                new MockEnvironment().withProperty("spring.application.name", "cb-ems")).getApplicationId())
                .isEqualTo("cb-ems");
    }

    @Test
    void explicitTraceLensNameStillOverridesSpringName() {
        assertThat(configuration.traceLensConfig(properties().setApplicationId("custom-service"),
                new MockEnvironment().withProperty("spring.application.name", "cb-ems")).getApplicationId())
                .isEqualTo("custom-service");
    }

    private TraceLensProperties properties() {
        return new TraceLensProperties().setPackages(List.of("com.example.app"));
    }
}
