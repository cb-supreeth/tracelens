package com.chargebee.tracelens.core.event;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    /** Absolute System.nanoTime() at capture; the owning trace derives relative offsets. */
    private final long nanoTime;
    private final String thread;
    private final Long threadId;
    private final EventType type;
    private final String className;
    private final String methodName;
    private final String methodDescriptor;
    private final int line;
    private final String message;
    private final Map<String, String> attributes;
    private final boolean fieldsTruncated;
    private final boolean detailGapBefore;

    private TraceEvent(Builder b) {
        this.seq = b.seq;
        this.epochMillis = b.epochMillis;
        this.nanoTime = b.nanoTime;
        this.thread = bound(b.thread, 512);
        this.threadId = b.threadId;
        this.type = b.type;
        this.className = bound(b.className, 1024);
        this.methodName = bound(b.methodName, 256);
        this.methodDescriptor = bound(b.methodDescriptor, 2048);
        this.line = b.line;
        this.message = bound(b.message, 4096);
        this.attributes = b.attributes == null ? null : Collections.unmodifiableMap(b.attributes);
        this.fieldsTruncated = b.fieldsTruncated || longer(b.thread, 512) || longer(b.className, 1024)
                || longer(b.methodName, 256) || longer(b.methodDescriptor, 2048) || longer(b.message, 4096);
        this.detailGapBefore = b.detailGapBefore;
    }

    private static String bound(String value, int limit) {
        return value == null || value.length() <= limit ? value : value.substring(0, limit);
    }
    private static boolean longer(String value, int limit) { return value != null && value.length() > limit; }
    public boolean isFieldsTruncated() { return fieldsTruncated; }
    public boolean isDetailGapBefore() { return detailGapBefore; }

    public long getSeq() {
        return seq;
    }

    public long getEpochMillis() {
        return epochMillis;
    }

    public long getNanoTime() {
        return nanoTime;
    }

    public String getThread() {
        return thread;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public Long getThreadId() { return threadId; }

    public EventType getType() {
        return type;
    }

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getMethodDescriptor() { return methodDescriptor; }

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

    public static TraceEvent line(long seq, long epochMillis, long nanoTime, String thread,
                                  String fqcn, int line) {
        return builder(EventType.LINE).seq(seq).epochMillis(epochMillis).nanoTime(nanoTime)
                .thread(thread).className(fqcn).line(line).build();
    }

    public static TraceEvent method(long seq, long epochMillis, long nanoTime, String thread,
                                    EventType enterOrExit, String fqcn, String method) {
        return builder(enterOrExit).seq(seq).epochMillis(epochMillis).nanoTime(nanoTime)
                .thread(thread).className(fqcn).methodName(method).build();
    }

    public static TraceEvent exception(long seq, long epochMillis, long nanoTime, String thread,
                                       String fqcn, String method, String exceptionType, String exceptionMessage) {
        return builder(EventType.EXCEPTION).seq(seq).epochMillis(epochMillis).nanoTime(nanoTime)
                .thread(thread).className(fqcn).methodName(method)
                .message(exceptionMessage)
                .attr("exceptionType", exceptionType).build();
    }

    public static TraceEvent marker(long seq, long epochMillis, long nanoTime, String thread, String text) {
        return builder(EventType.MARKER).seq(seq).epochMillis(epochMillis).nanoTime(nanoTime)
                .thread(thread).message(text).build();
    }

    public static final class Builder {
        private final EventType type;
        private long seq;
        private long epochMillis;
        private long nanoTime;
        private String thread;
        private Long threadId;
        private String className;
        private String methodName;
        private String methodDescriptor;
        private int line = -1;
        private String message;
        private Map<String, String> attributes;
        private boolean fieldsTruncated;
        private boolean detailGapBefore;

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

        public Builder nanoTime(long nanoTime) {
            this.nanoTime = nanoTime;
            return this;
        }

        public Builder thread(String thread) {
            this.thread = thread;
            return this;
        }

        public Builder threadId(Long threadId) {
            this.threadId = threadId;
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

        public Builder methodDescriptor(String methodDescriptor) {
            this.methodDescriptor = methodDescriptor;
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
            if (key != null && key.length() <= 128 && value != null) {
                if (attributes == null) {
                    attributes = new LinkedHashMap<>();
                }
                if (attributes.size() < 32 || attributes.containsKey(key)) {
                    attributes.put(key, bound(value, 16384));
                    fieldsTruncated |= value.length() > 16384;
                } else fieldsTruncated = true;
            } else if (value != null) {
                fieldsTruncated = true;
            }
            return this;
        }
        public Builder fieldsTruncated(boolean value) { fieldsTruncated |= value; return this; }
        public Builder detailGapBefore(boolean value) { detailGapBefore |= value; return this; }

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
