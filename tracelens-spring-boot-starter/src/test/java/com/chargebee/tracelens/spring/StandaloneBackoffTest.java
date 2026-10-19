package com.chargebee.tracelens.spring;

import com.chargebee.tracelens.core.store.TraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Installing the standalone agent must not create a second store or app auth exemption. */
class StandaloneBackoffTest {
    @Test
    void enabledStarterBacksOffWhenAgentOwnsRuntime() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TraceLensAutoConfiguration.class,
                        TraceLensSecurityAutoConfiguration.class))
                .withPropertyValues("tracelens.enabled=true", "tracelens.standalone=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(TraceStore.class);
                    assertThat(context).doesNotHaveBean("traceLensFilter")
                            .doesNotHaveBean("traceLensDebugServlet")
                            .doesNotHaveBean("traceLensWebSecurityCustomizer");
                });
    }
}
