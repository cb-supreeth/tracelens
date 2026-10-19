package com.chargebee.tracelens.bridge;

import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.assertj.core.api.Assertions.assertThat;

class W3CTraceContextTest {
    private static final String HEADER = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @Test void childKeepsTraceAndFlagsButHasFreshSpan() {
        W3CTraceContext parent = W3CTraceContext.parse(HEADER, "vendor=opaque,tenant@vendor=value");
        W3CTraceContext child = parent.child();
        assertThat(child.getTraceId()).isEqualTo(parent.getTraceId());
        assertThat(child.getSpanId()).isNotEqualTo(parent.getSpanId());
        assertThat(child.getFlags()).isEqualTo("01");
        assertThat(child.getTracestate()).isEqualTo("vendor=opaque,tenant@vendor=value");
        assertThat(W3CTraceContext.parse(child.header(), null)).isNotNull();
    }

    @Test void rejectsZeroIdsInvalidHexVersionsAndAmbiguousValues() {
        for (String invalid : new String[] {null, "", HEADER.toUpperCase(), " " + HEADER,
                HEADER + "," + HEADER, HEADER.replace("00-", "ff-"), HEADER + "-extra",
                HEADER.replace("0af7651916cd43dd8448eb211c80319c", "00000000000000000000000000000000"),
                HEADER.replace("b7ad6b7169203331", "0000000000000000"), HEADER.substring(0, 53) + "zz"}) {
            assertThat(W3CTraceContext.parse(invalid, null)).isNull();
        }
    }

    @Test void invalidStateDoesNotDestroyValidParentAndIsNotForwarded() {
        for (String state : new String[] {"a=one,a=two", "a=x\r\nAuthorization: secret", "A=x", "a=x=y", "a=", "a=x,", "=x"}) {
            W3CTraceContext context = W3CTraceContext.parse(HEADER, state);
            assertThat(context).isNotNull();
            assertThat(context.getTracestate()).isNull();
        }
    }

    @Test void rootAndLegacySnapshot() {
        W3CTraceContext root = W3CTraceContext.fresh();
        assertThat(W3CTraceContext.isValidTraceId(root.getTraceId())).isTrue();
        assertThat(W3CTraceContext.isValidSpanId(root.getSpanId())).isTrue();
        assertThat(root.getFlags()).isEqualTo("00");
        assertThat(W3CTraceContext.fromSnapshot(new TraceSnapshot("legacy-uuid", "label", "user", Collections.emptyMap()))).isNull();
    }
}
