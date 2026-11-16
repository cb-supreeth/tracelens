package com.chargebee.tracelens.spring;

import com.chargebee.tracelens.core.config.TraceLensConfig;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcQueryPropertiesTest {
    @Test void nativeClickHouseHasIndependentOptInAndBindings() {
        TraceLensAutoConfiguration factory = new TraceLensAutoConfiguration();
        TraceLensProperties props = new TraceLensProperties().setPackages(Collections.singletonList("com.example"));
        TraceLensConfig defaults = factory.traceLensConfig(props, new MockEnvironment());
        assertThat(defaults.isCaptureClickHouseQueries()).isFalse();
        assertThat(defaults.isCaptureClickHouseParameters()).isFalse();
        props.setCaptureClickHouseQueries(true).setCaptureClickHouseParameters(true);
        TraceLensConfig enabled = factory.traceLensConfig(props, new MockEnvironment());
        assertThat(enabled.isCaptureClickHouseQueries()).isTrue();
        assertThat(enabled.isCaptureClickHouseParameters()).isTrue();
        assertThat(enabled.isCaptureJdbcQueries()).isFalse();
    }

    @Test void defaultsAndExplicitLimitsReachCoreConfiguration() {
        TraceLensAutoConfiguration factory = new TraceLensAutoConfiguration();
        TraceLensProperties props = new TraceLensProperties().setPackages(Collections.singletonList("com.example"));
        assertThat(factory.traceLensConfig(props, new MockEnvironment()).isCaptureJdbcQueries()).isFalse();
        assertThat(factory.traceLensConfig(props, new MockEnvironment()).isCaptureJdbcParameters()).isFalse();
        props.setCaptureJdbcQueries(true).setCaptureJdbcParameters(true).setMaxSqlLength(512).setMaxSqlQueriesPerRequest(10);
        TraceLensConfig config = factory.traceLensConfig(props, new MockEnvironment());
        assertThat(config.isCaptureJdbcQueries()).isTrue();
        assertThat(config.isCaptureJdbcParameters()).isTrue();
        assertThat(config.getMaxSqlLength()).isEqualTo(512);
        assertThat(config.getMaxSqlQueriesPerRequest()).isEqualTo(10);
        props.setMaxSqlQueriesPerRequest(0);
        assertThatThrownBy(() -> factory.traceLensConfig(props, new MockEnvironment()))
                .hasMessageContaining("maxSqlQueriesPerRequest");
    }
}
