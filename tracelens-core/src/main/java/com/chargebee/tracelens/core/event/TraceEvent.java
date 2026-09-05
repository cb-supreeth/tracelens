package com.chargebee.tracelens.core.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry on a trace timeline. Deliberately a single flat class (not a type
 * hierarchy): events are allocated on the hottest path in the JVM, so we keep
 * allocation to one object and reuse the same shape for every event kind.
 *
 * Fields that don't apply to a type stay null / -1 and are omitted from export.
 */
public final class TraceEvent {

    private final long seq;
    private final long epochMillis;
    /** Nanoseconds since the owning trace started — ordering + durations within a trace. */
    private final long offsetNanos;
    private final String thread;
    private final EventType type;
    private final String className;
    private final String methodName;
    private final int line;
    private final String message;
    private final Map<String, String> attributes;

    private TraceEvent(Builder b) {
        this.seq = b.seq;
        this.epochMillis = b.epochMillis;
        this.offsetNanos = b.offsetNanos;
        this.thread = b.thread;
        this.type = b.type;
        this.className = b.className;
        this.methodName = b.methodName;
        this.line = b.line;
        this.message = b.message;
        this.attributes = b.attributes == null ? null : Collections.unmodifiableMap(b.attributes);
    }

    public long getSeq() {
        return seq;
    }

    public long getEpochMillis() {
        return epochMillis;
    }

    public long getOffsetNanos() {
        return offsetNanos;
    }

    public String getThread() {
        return thread;
    }

    public EventType getType() {
        return type;
    }

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    public int getLine() {
        return line;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, String> getAttributes() {
        return attributes == null ? Collections.emptyMap() : attributes;
    }

    public static Builder builder(EventType type) {
        return new Builder(type);
    }

    public static TraceEvent line(long seq, long epochMillis, long offsetNanos, String thread,
                                  String fqcn, int line) {
        return builder(EventType.LINE).seq(seq).epochMillis(epochMillis).offsetNanos(offsetNanos)
                .thread(thread).className(fqcn).line(line).build();
    }

    public static TraceEvent method(long seq, long epochMillis, long offsetNanos, String thread,
                                    EventType enterOrExit, String fqcn, String method) {
        return builder(enterOrExit).seq(seq).epochMillis(epochMillis).offsetNanos(offsetNanos)
                .thread(thread).className(fqcn).methodName(method).build();
    }

    public static TraceEvent exception(long seq, long epochMillis, long offsetNanos, String thread,
                                       String fqcn, String method, String exceptionType, String exceptionMessage) {
        return builder(EventType.EXCEPTION).seq(seq).epochMillis(epochMillis).offsetNanos(offsetNanos)
                .thread(thread).className(fqcn).methodName(method)
                .message(exceptionMessage)
                .attr("exceptionType", exceptionType).build();
    }

    public static TraceEvent marker(long seq, long epochMillis, long offsetNanos, String thread, String text) {
        return builder(EventType.MARKER).seq(seq).epochMillis(epochMillis).offsetNanos(offsetNanos)
                .thread(thread).message(text).build();
    }

    public static final class Builder {
        private final EventType type;
        private long seq;
        private long epochMillis;
        private long offsetNanos;
        private String thread;
        private String className;
        private String methodName;
        private int line = -1;
        private String message;
        private Map<String, String> attributes;

        private Builder(EventType type) {
            this.type = type;
        }

        public Builder seq(long seq) {
            this.seq = seq;
            return this;
        }

        public Builder epochMillis(long epochMillis) {
            this.epochMillis = epochMillis;
            return this;
        }

        public Builder offsetNanos(long offsetNanos) {
            this.offsetNanos = offsetNanos;
            return this;
        }

        public Builder thread(String thread) {
            this.thread = thread;
            return this;
        }

        public Builder className(String className) {
            this.className = className;
            return this;
        }

        public Builder methodName(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public Builder line(int line) {
            this.line = line;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder attr(String key, String value) {
            if (value != null) {
                if (attributes == null) {
                    attributes = new LinkedHashMap<>();
                }
                attributes.put(key, value);
            }
            return this;
        }

        public TraceEvent build() {
            return new TraceEvent(this);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('#').append(seq).append(' ').append(type).append(" [").append(thread).append("] ");
        if (className != null) {
            sb.append(className);
            if (methodName != null) {
                sb.append('#').append(methodName);
            }
            if (line >= 0) {
                sb.append(':').append(line);
            }
        }
        if (message != null) {
            sb.append(" — ").append(message);
        }
        return sb.toString();
    }
}
