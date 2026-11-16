package com.chargebee.tracelens.spring;

import com.chargebee.tracelens.bridge.DataStoreBoundaryRuntime;
import com.chargebee.tracelens.bridge.TraceContext;
import com.chargebee.tracelens.bridge.TraceScope;
import com.chargebee.tracelens.bridge.TraceSnapshot;
import com.chargebee.tracelens.core.config.TraceLensConfig;
import com.chargebee.tracelens.core.record.Tracer;
import com.chargebee.tracelens.core.store.TraceStore;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

class DataStoreBoundaryPropertiesTest {
    @Test void defaultAndOptOutReachCoreConfiguration() {
        TraceLensAutoConfiguration configuration = new TraceLensAutoConfiguration();
        TraceLensProperties props = new TraceLensProperties().setPackages(Collections.singletonList("com.example"));
        assertThat(configuration.traceLensConfig(props, new MockEnvironment()).isCaptureDataStoreBoundaries()).isTrue();
        props.setCaptureDataStoreBoundaries(false);
        assertThat(configuration.traceLensConfig(props, new MockEnvironment()).isCaptureDataStoreBoundaries()).isFalse();
    }

    @Test void adapterLifecycleAppliesOptOutToAlreadyInstalledAgentHooks() {
        TraceLensConfig config = new TraceLensConfig().setCaptureDataStoreBoundaries(false);
        TraceStore store = new TraceStore(config);
        TraceLensAutoConfiguration.TraceLensLifecycle lifecycle =
                new TraceLensAutoConfiguration.TraceLensLifecycle(store, new Tracer(store), config);
        DataStoreBoundaryRuntime.configure(true);
        try {
            lifecycle.start();
            try (TraceScope ignored = TraceContext.activate(new TraceSnapshot("trace", "segment", "lens", "user", null))) {
                assertThat(DataStoreBoundaryRuntime.enter("com.clickhouse.client.api.Client", "query", true)).isNull();
            }
        } finally {
            lifecycle.stop();
            DataStoreBoundaryRuntime.configure(false);
            TraceContext.clear();
        }
    }
}
