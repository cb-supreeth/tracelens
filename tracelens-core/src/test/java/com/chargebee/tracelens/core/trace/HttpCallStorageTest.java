package com.chargebee.tracelens.core.trace;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class HttpCallStorageTest {
    @Test void boundedUpsertAndImmutableSnapshot() {
        Trace trace = new Trace("test", "user", EntryChannel.HTTP, 1, 1);
        Map<String, String> call = new HashMap<>();
        call.put("spanId", "first");
        call.put("status", "ACTIVE");
        trace.recordHttpCall(call);
        call.put("status", "COMPLETED");
        assertThat(trace.httpCalls().get(0).get("status")).isEqualTo("ACTIVE");
        trace.recordHttpCall(call);
        assertThat(trace.httpCalls()).hasSize(1);
        assertThat(trace.httpCalls().get(0).get("status")).isEqualTo("COMPLETED");
        for (int i = 0; i < 100; i++) {
            call.put("spanId", "span-" + i);
            trace.recordHttpCall(call);
        }
        assertThat(trace.httpCalls()).hasSize(64);
        assertThat(trace.isHttpCallsTruncated()).isTrue();
        call.put("spanId", "first");
        call.put("status", "ERROR");
        trace.recordHttpCall(call);
        assertThat(trace.httpCalls().get(0).get("status")).isEqualTo("ERROR");
    }
}
