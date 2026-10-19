package com.chargebee.tracelens.bridge;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * SDK-free, metadata-only messaging observation. Never reads records' keys, values,
 * message bodies, arbitrary headers, callbacks, or Futures. A returned send is only
 * SUBMITTED, not delivered. Kafka poll observes deliveries only under an already
 * active trace; it never binds an arbitrary record's context to subsequent work.
 */
public final class MessagingBoundaryRuntime {
    private static final int MAX_TEXT = 256;
    private static final int MAX_PARTITIONS = 64;
    private static final int MAX_NESTING = 32;
    private static final ThreadLocal<Publish> PUBLISH = new ThreadLocal<>();
    private static final ThreadLocal<Poll> POLL = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CALLBACK = new ThreadLocal<>();
    private static volatile boolean captureEnabled;
    private static volatile boolean propagationEnabled;
    private static final String[] TRACE_KEYS = {
            TraceSnapshot.SEGMENT_ID_KEY, TraceSnapshot.ACTION_ID_KEY,
            TraceSnapshot.ACTION_NAME_KEY, TraceSnapshot.SESSION_ID_KEY, "siteId"
    };

    private MessagingBoundaryRuntime() { }

    public static void configure(boolean capture, boolean propagation) {
        captureEnabled = capture;
        propagationEnabled = propagation;
    }

    public static boolean isPropagationEnabled() { return propagationEnabled; }

    public static Object enterSqs(Object request, boolean batch) {
        try {
            if (!active()) return null;
            String destination = queueDestination(string(read(request, "getQueueUrl")));
            Integer batchCount = null;
            if (batch) {
                Object entries = read(request, "getEntries");
                if (entries instanceof Collection) {
                    try { batchCount = ((Collection<?>) entries).size(); }
                    catch (Throwable ignored) { }
                }
            }
            Publish call = beginPublish("sqs", request, destination,
                    batch ? "sendMessageBatch" : "sendMessage");
            if (call != null && batchCount != null) {
                call.fields.put("batchRecordCount", Integer.toString(batchCount));
            }
            return call;
        } catch (Throwable ignored) { return null; }
    }

    public static Object enterKafkaSend(Object record) {
        try {
            if (!active()) return null;
            return beginPublish("kafka", record, name(string(read(record, "topic"))), "send");
        } catch (Throwable ignored) { return null; }
    }

    private static Publish beginPublish(String system, Object request, String destination, String operation) {
        Publish previous = PUBLISH.get();
        // Kafka send(record) delegates to send(record, callback), as do some SDK wrappers.
        if (previous != null && previous.request == request && system.equals(previous.fields.get("system"))) {
            previous.depth++;
            return previous;
        }
        if (previous != null && previous.nesting >= MAX_NESTING) return null;
        Publish call = new Publish(request, previous, fields(system, "publish", destination, operation));
        PUBLISH.set(call);
        return call;
    }

    public static void exitPublish(Object token, Throwable failure) {
        if (!(token instanceof Publish)) return;
        Publish call = (Publish) token;
        try {
            if (--call.depth > 0) return;
            finish(call.fields, failure == null ? "SUBMITTED" : "ERROR", failure);
            emit(call.segmentId, call.fields);
        } catch (Throwable ignored) {
        } finally {
            if (call.depth <= 0 && PUBLISH.get() == call) {
                if (call.previous == null) PUBLISH.remove(); else PUBLISH.set(call.previous);
            }
        }
    }

    public static Object enterKafkaPoll() {
        return enterKafkaPoll(null);
    }

    public static Object enterKafkaPoll(Object consumer) {
        if (!active()) return null;
        Poll previous = POLL.get();
        if (previous != null && previous.consumer == consumer) {
            previous.depth++;
            return previous;
        }
        if (previous != null && previous.nesting >= MAX_NESTING) return null;
        Poll poll = new Poll(consumer, previous);
        POLL.set(poll);
        return poll;
    }

    public static void exitKafkaPoll(Object token, Object records, Throwable failure) {
        if (!(token instanceof Poll)) return;
        Poll poll = (Poll) token;
        if (--poll.depth > 0) return;
        try { observePoll(poll.segmentId, records, failure); }
        catch (Throwable ignored) { }
        finally {
            if (POLL.get() == poll) {
                if (poll.previous == null) POLL.remove(); else POLL.set(poll.previous);
            }
        }
    }

    private static void observePoll(String segmentId, Object records, Throwable failure) {
        try {
            if (failure != null) {
                Map<String, String> fields = fields("kafka", "consume", "unknown", "poll");
                finish(fields, "ERROR", failure);
                emit(segmentId, fields);
                return;
            }
            Object count = read(records, "count");
            if (!(count instanceof Integer) || (Integer) count <= 0) return;
            Set<String> topics = new LinkedHashSet<>();
            Object partitions = read(records, "partitions");
            int examined = 0;
            boolean truncated = false;
            if (partitions instanceof Iterable) {
                for (Object partition : (Iterable<?>) partitions) {
                    if (examined++ == MAX_PARTITIONS) { truncated = true; break; }
                    topics.add(name(string(read(partition, "topic"))));
                }
            }
            if (topics.isEmpty()) topics.add("unknown");
            for (String topic : topics) {
                Map<String, String> fields = fields("kafka", "consume", topic, "poll");
                fields.put("status", "OBSERVED");
                fields.put("batchRecordCount", count.toString());
                fields.put("observation", "poll-delivery-only");
                if (truncated) fields.put("destinationsTruncated", "true");
                emit(segmentId, fields);
            }
        } catch (Throwable ignored) { }
    }

    public static Object enterQueueman(Object handler, Map<?, ?> headers) {
        String lifecycle = null;
        try {
            if (Boolean.TRUE.equals(CALLBACK.get()) || (!captureEnabled && !propagationEnabled)) return null;
            String destination = captureEnabled ? firstHeader(headers, "queueUrl", "queueName", "queue", "topic") : null;
            destination = destination == null ? "unknown" : queueDestination(destination);
            if (TraceContext.currentSegmentId() == null) {
                Map<String, String> wire = new LinkedHashMap<>();
                // Only known correlation fields are eligible for continuation. Never
                // stringify arbitrary header values or retain application headers.
                if (propagationEnabled) {
                    for (String key : TRACE_KEYS) {
                        String value = header(headers, key);
                        if (value != null) wire.put(key, value);
                    }
                }
                String traceId = propagationEnabled ? header(headers, TraceSnapshot.TRACE_ID_KEY) : null;
                if (traceId == null && !captureEnabled) return null;
                if (propagationEnabled) copyW3cContext(headers, wire);
                String lens = propagationEnabled ? header(headers, TraceSnapshot.LENS_ID_KEY) : null;
                String user = propagationEnabled ? header(headers, TraceSnapshot.USER_KEY) : null;
                lifecycle = TraceRuntime.beginLifecycle(traceId,
                        lens == null ? "auto:queueman" : lens,
                        user == null ? "anonymous" : user, "SQS", wire);
            }
            String segmentId = TraceContext.currentSegmentId();
            if (segmentId == null) { TraceRuntime.endLifecycle(lifecycle); return null; }
            Map<String, String> fields = captureEnabled ? fields("queueman", "consume", destination, "processMessage") : null;
            if (fields != null && handler != null) fields.put("handler", bounded(handler.getClass().getName()));
            return new Consumption(segmentId, lifecycle, fields);
        } catch (Throwable ignored) {
            TraceRuntime.endLifecycle(lifecycle);
            return null;
        }
    }

    public static void exitQueueman(Object token, Throwable failure) {
        if (!(token instanceof Consumption)) return;
        Consumption call = (Consumption) token;
        try {
            if (call.fields != null) {
                finish(call.fields, failure == null ? "OBSERVED" : "ERROR", failure);
                emit(call.segmentId, call.fields);
            }
        } catch (Throwable ignored) {
        } finally { TraceRuntime.endLifecycle(call.lifecycle); }
    }

    private static boolean active() {
        return captureEnabled && !Boolean.TRUE.equals(CALLBACK.get()) && TraceContext.currentSegmentId() != null;
    }

    private static Object read(Object value, String accessor) {
        if (value == null) return null;
        try { return value.getClass().getMethod(accessor).invoke(value); }
        catch (Throwable ignored) { return null; }
    }

    private static String string(Object value) { return value instanceof String ? (String) value : null; }

    private static String header(Map<?, ?> headers, String key) {
        try {
            String value = headers == null ? null : string(headers.get(key));
            return value == null || value.length() > MAX_TEXT || value.trim().isEmpty() ? null : bounded(value);
        } catch (Throwable ignored) { return null; }
    }

    private static String firstHeader(Map<?, ?> headers, String... keys) {
        for (String key : keys) {
            String value = header(headers, key);
            if (value != null) return value;
        }
        return null;
    }

    private static void copyW3cContext(Map<?, ?> headers, Map<String, String> wire) {
        try {
            if (headers == null) return;
            String trace = rawHeader(headers, TraceSnapshot.TRACE_ID_KEY, 32);
            String span = rawHeader(headers, "spanId", 16);
            Object flagValue = headers.get("traceFlags");
            String flags = flagValue == null ? "00" : string(flagValue);
            if (flags == null || flags.length() != 2) return;
            // Validate the original bounded values, without turning malformed
            // control characters into a valid context by sanitizing them first.
            W3CTraceContext context = W3CTraceContext.parse("00-" + trace + "-" + span + "-" + flags,
                    rawHeader(headers, "tracestate", 512));
            if (context == null) return;
            wire.put("spanId", context.getSpanId());
            wire.put("traceFlags", context.getFlags());
            if (context.getTracestate() != null) wire.put("tracestate", context.getTracestate());
        } catch (Throwable ignored) { }
    }

    private static String rawHeader(Map<?, ?> headers, String key, int maxLength) {
        String value = string(headers.get(key));
        return value != null && value.length() <= maxLength ? value : null;
    }

    private static String queueDestination(String value) {
        if (value == null || value.length() > 4096) return "unknown";
        try {
            if (value.contains("://")) {
                URI uri = new URI(value);
                // Keep only the queue name, never URL credentials, account ids,
                // signed query strings, or fragments.
                String path = uri.getRawPath();
                if (path == null || path.isEmpty()) return "unknown";
                return name(path.substring(path.lastIndexOf('/') + 1));
            }
            return name(value);
        } catch (Throwable ignored) { return "unknown"; }
    }

    private static String name(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_TEXT) return "unknown";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.')) return "unknown";
        }
        return value;
    }

    private static String bounded(String value) {
        return value.substring(0, Math.min(MAX_TEXT, value.length())).replaceAll("[\\p{Cntrl}]", "");
    }

    private static Map<String, String> fields(String system, String direction, String destination, String operation) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("boundaryKind", "messaging");
        fields.put("system", system);
        fields.put("direction", direction);
        fields.put("destination", destination);
        fields.put("operation", operation);
        fields.put("evidenceKind", "client-boundary");
        return fields;
    }

    private static void finish(Map<String, String> fields, String status, Throwable failure) {
        fields.put("status", status);
        if (failure != null) fields.put("errorType", bounded(failure.getClass().getName()));
    }

    private static void emit(String segmentId, Map<String, String> fields) {
        if (Boolean.TRUE.equals(CALLBACK.get())) return;
        CALLBACK.set(Boolean.TRUE);
        try { TraceRuntime.onBoundary(segmentId, fields); }
        finally { CALLBACK.remove(); }
    }

    private static final class Publish {
        final Object request;
        final Publish previous;
        final int nesting;
        final String segmentId = TraceContext.currentSegmentId();
        final Map<String, String> fields;
        int depth = 1;
        Publish(Object request, Publish previous, Map<String, String> fields) {
            this.request = request;
            this.previous = previous;
            this.nesting = previous == null ? 1 : previous.nesting + 1;
            this.fields = fields;
        }
    }

    private static final class Consumption {
        final String segmentId;
        final String lifecycle;
        final Map<String, String> fields;
        Consumption(String segmentId, String lifecycle, Map<String, String> fields) {
            this.segmentId = segmentId;
            this.lifecycle = lifecycle;
            this.fields = fields;
        }
    }

    private static final class Poll {
        final Object consumer;
        final Poll previous;
        final int nesting;
        final String segmentId = TraceContext.currentSegmentId();
        int depth = 1;
        Poll(Object consumer, Poll previous) {
            this.consumer = consumer;
            this.previous = previous;
            this.nesting = previous == null ? 1 : previous.nesting + 1;
        }
    }
}
