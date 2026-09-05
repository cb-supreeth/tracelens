package com.chargebee.tracelens.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    @AfterEach
    void cleanup() {
        TraceContext.clear();
    }

    @Test
    void activateAndRestoreNesting() {
        assertThat(TraceContext.current()).isNull();

        TraceSnapshot outer = new TraceSnapshot("t-1", "lens-outer", "a@cb.com", Map.of());
        TraceSnapshot inner = new TraceSnapshot("t-2", "lens-inner", "b@cb.com", Map.of());

        try (TraceScope ignored = TraceContext.activate(outer)) {
            assertThat(TraceContext.current()).isEqualTo(outer);

            try (TraceScope ignored2 = TraceContext.activate(inner)) {
                assertThat(TraceContext.current()).isEqualTo(inner);
            }

            assertThat(TraceContext.current()).isEqualTo(outer);
        }

        assertThat(TraceContext.current()).isNull();
    }

    @Test
    void snapshotRoundTripsThroughTransportMap() {
        TraceSnapshot snapshot = new TraceSnapshot("trace-9", "my-lens", "me@chargebee.com",
                Map.of("siteId", "site-1", "siteType", "LIVE"));

        Map<String, String> wire = snapshot.toMap();

        assertThat(wire).containsEntry("tracelens-trace-id", "trace-9");
        assertThat(wire).containsEntry("tracelens-id", "my-lens");
        assertThat(wire).containsEntry("tracelens-user", "me@chargebee.com");
        assertThat(wire).containsEntry("siteId", "site-1");

        TraceSnapshot restored = TraceSnapshot.fromMap(wire);
        assertThat(restored.getTraceId()).isEqualTo("trace-9");
        assertThat(restored.getLensId()).isEqualTo("my-lens");
        assertThat(restored.getUser()).isEqualTo("me@chargebee.com");
        assertThat(restored.getAttributes()).containsOnly(Map.entry("siteId", "site-1"),
                Map.entry("siteType", "LIVE"));
    }

    @Test
    void fromMapReturnsNullWithoutTraceId() {
        assertThat(TraceSnapshot.fromMap(Map.of("tracelens-id", "x"))).isNull();
        assertThat(TraceSnapshot.fromMap(null)).isNull();
    }
}
