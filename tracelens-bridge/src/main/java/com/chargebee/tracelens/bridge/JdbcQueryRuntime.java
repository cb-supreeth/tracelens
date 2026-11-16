package com.chargebee.tracelens.bridge;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/** Opt-in local JDBC evidence. Bindings require a separate opt-in; result sets are never inspected. */
public final class JdbcQueryRuntime {
    private static final int MAX_STATEMENTS = 1024;
    private static final int MAX_SEGMENTS = 4096;
    private static final long INACTIVE_RETENTION_NANOS = 30L * 60 * 1000000000L;
    private static final WeakIdentityMap<StatementState> STATEMENTS = new WeakIdentityMap<>(MAX_STATEMENTS);
    private static final Map<String, Quota> QUOTAS = new LinkedHashMap<>();
    private static final ThreadLocal<Call> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<BatchCall> BATCH = new ThreadLocal<>();
    private static final ThreadLocal<BindingCall> BINDING = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CALLBACK = new ThreadLocal<>();
    private static volatile boolean enabled;
    private static volatile int sqlLimit = 4096;
    private static volatile int queryLimit = 100;
    private static volatile boolean parametersEnabled;
    private static final int MAX_PARAMETERS = 128;
    private static final int MAX_VALUE_LENGTH = 512;
    private static final int MAX_PARAMETER_LENGTH = 8192;
    private static final int MAX_PARAMETER_SETS = 10;

    private JdbcQueryRuntime() { }

    public static void configure(boolean capture, int maxSqlLength, int maxQueriesPerRequest) {
        configure(capture, maxSqlLength, maxQueriesPerRequest, false);
    }

    public static void configure(boolean capture, int maxSqlLength, int maxQueriesPerRequest, boolean captureParameters) {
        enabled = false;
        parametersEnabled = false;
        sqlLimit = Math.max(1, Math.min(16384, maxSqlLength));
        queryLimit = Math.max(1, Math.min(1000, maxQueriesPerRequest));
        STATEMENTS.clear();
        synchronized (QUOTAS) { QUOTAS.clear(); }
        CURRENT.remove();
        BATCH.remove();
        BINDING.remove();
        parametersEnabled = capture && captureParameters;
        enabled = capture;
    }

    /** Called only after a successful prepareStatement/prepareCall. No driver objects are introspected. */
    public static void prepared(Object statement, String sql) {
        try {
            if (!enabled || statement == null || sql == null || CURRENT.get() != null
                    || Boolean.TRUE.equals(CALLBACK.get())) return;
            STATEMENTS.put(statement, new StatementState(bounded(sql), sql.length() > sqlLimit, true));
        } catch (Throwable ignored) { }
    }

    /** Capture only scalar values supplied to public positional setters, never driver internals. */
    public static Object enterBinding(Object statement, String setter, int index, Object value) {
        try {
            if (!enabled || !parametersEnabled || statement == null || index < 1 || CURRENT.get() != null
                    || BATCH.get() != null || Boolean.TRUE.equals(CALLBACK.get())) return null;
            BindingCall nested = BINDING.get();
            if (nested != null) { nested.depth++; return nested; }
            BindingCall call = new BindingCall(statement, index, parameter(setter, value));
            BINDING.set(call);
            return call;
        } catch (Throwable ignored) { return null; }
    }

    public static void exitBinding(Object token, Throwable failure) {
        if (!(token instanceof BindingCall)) return;
        BindingCall call = (BindingCall) token;
        try {
            if (--call.depth != 0 || failure != null || !enabled || !parametersEnabled) return;
            synchronized (STATEMENTS) {
                StatementState state = STATEMENTS.get(call.statement);
                if (state == null) {
                    state = new StatementState(null, false, false);
                    if (!STATEMENTS.put(call.statement, state)) return;
                }
                if (call.index > MAX_PARAMETERS) { state.parametersTruncated = true; return; }
                Parameter old = state.parameters.get(call.index);
                int occupied = state.parameterChars - (old == null ? 0 : old.length());
                Parameter captured = call.parameter.limit(Math.max(0, MAX_PARAMETER_LENGTH - occupied));
                state.parameters.put(call.index, captured);
                state.parameterChars = occupied + captured.length();
            }
        } catch (Throwable ignored) {
        } finally {
            if (call.depth <= 0 && BINDING.get() == call) BINDING.remove();
        }
    }

    public static void clearParameters(Object statement) {
        try {
            if (!enabled || !parametersEnabled || CURRENT.get() != null || BATCH.get() != null
                    || BINDING.get() != null || Boolean.TRUE.equals(CALLBACK.get())) return;
            synchronized (STATEMENTS) {
                StatementState state = STATEMENTS.get(statement);
                if (state == null) {
                    state = new StatementState(null, false, true);
                    if (!STATEMENTS.put(statement, state)) return;
                }
                state.parameters.clear(); state.parameterChars = 0;
                state.parametersTruncated = false; state.parametersAvailable = true;
            }
        } catch (Throwable ignored) { }
    }

    /** Successful addBatch snapshots bindings before later setters can change the next execution. */
    public static void addBatch(Object statement, String sql) {
        try {
            if (!enabled || statement == null) return;
            synchronized (STATEMENTS) {
                StatementState state = STATEMENTS.get(statement);
                if (state == null) {
                    state = new StatementState(null, false, false);
                    if (!STATEMENTS.put(statement, state)) return;
                }
                if (state.batchSize < Integer.MAX_VALUE) state.batchSize++;
                if (sql != null) {
                    state.plainBatch = true;
                    String separator = state.batchSql.length() == 0 ? "" : ";\n";
                    int remaining = Math.max(0, sqlLimit - state.batchSql.length());
                    String part = separator + bounded(sql);
                    state.batchSql.append(part, 0, Math.min(remaining, part.length()));
                    state.batchTruncated |= sql.length() > sqlLimit || part.length() > remaining;
                } else {
                    state.preparedBatch = true;
                    if (parametersEnabled) {
                        ParameterSnapshot snapshot = parameterSnapshot(state);
                        if (state.parameterSetsOmitted == 0 && state.parameterSets.size() < MAX_PARAMETER_SETS
                                && state.parameterSetsLength + snapshot.json.length() + 1 <= MAX_PARAMETER_LENGTH - 2) {
                            state.parameterSets.add(snapshot.json);
                            state.parameterSetsLength += snapshot.json.length() + 1;
                            state.parameterSetsTruncated |= snapshot.truncated;
                        } else {
                            if (state.parameterSetsOmitted < Integer.MAX_VALUE) state.parameterSetsOmitted++;
                            state.parameterSetsTruncated = true;
                        }
                        state.parameterSetsAvailable &= state.parametersAvailable;
                    }
                }
            }
        } catch (Throwable ignored) { }
    }

    /** Public overloads/wrappers can delegate to each other; count each addBatch only once. */
    public static Object enterBatch(Object statement, String sql) {
        try {
            if (!enabled || CURRENT.get() != null || Boolean.TRUE.equals(CALLBACK.get())) return null;
            BatchCall current = BATCH.get();
            if (current != null) { current.depth++; return current; }
            BatchCall call = new BatchCall(statement, sql);
            BATCH.set(call);
            return call;
        } catch (Throwable ignored) { return null; }
    }

    public static void exitBatch(Object token, Throwable failure) {
        if (!(token instanceof BatchCall)) return;
        BatchCall call = (BatchCall) token;
        try {
            if (--call.depth == 0 && failure == null) addBatch(call.statement, call.sql);
        } catch (Throwable ignored) {
        } finally {
            if (call.depth <= 0 && BATCH.get() == call) BATCH.remove();
        }
    }

    public static void clearBatch(Object statement) {
        try {
            synchronized (STATEMENTS) {
                StatementState state = STATEMENTS.get(statement);
                if (state != null) {
                    state.batchSize = 0; state.batchSql.setLength(0); state.batchTruncated = false;
                    state.plainBatch = false; state.preparedBatch = false;
                    state.parameterSets.clear(); state.parameterSetsLength = 0; state.parameterSetsOmitted = 0;
                    state.parameterSetsTruncated = false; state.parameterSetsAvailable = true;
                }
            }
        } catch (Throwable ignored) { }
    }

    public static void closed(Object statement) {
        try { STATEMENTS.remove(statement); } catch (Throwable ignored) { }
    }

    public static Object enter(Object statement, String owner, String operation, String sql) {
        try {
            if (!enabled || Boolean.TRUE.equals(CALLBACK.get())) return null;
            String system = system(owner);
            if (system == null || !isOperation(operation)) return null;
            Call nested = CURRENT.get();
            if (nested != null) { nested.depth++; return nested; }
            TraceSnapshot snapshot = TraceContext.current();
            if (!TraceRuntime.hasSink() || snapshot == null || snapshot.getAttributes().containsKey("captureSkipped")) {
                return nonRecording(snapshot, statement, system, operation);
            }
            int reservation = reserve(snapshot);
            if (reservation <= 0) {
                Call limited = nonRecording(snapshot, statement, system, operation);
                if (reservation < 0) {
                    Map<String, String> marker = fields(system, "query-limit", "jdbc-query-limit", "TRUNCATED");
                    marker.put("queryLimit", String.valueOf(queryLimit));
                    emit(snapshot.getSegmentId(), marker);
                }
                return limited;
            }
            String captured = null;
            String source = "unavailable";
            boolean truncated = false;
            int batchSize = -1;
            boolean batch = operation.endsWith("Batch");
            if (sql != null) {
                captured = bounded(sql); source = "statement"; truncated = sql.length() > sqlLimit;
            } else {
                synchronized (STATEMENTS) {
                    StatementState state = STATEMENTS.get(statement);
                    if (state != null) {
                        if (batch) batchSize = state.batchSize;
                        if (batch && state.plainBatch) {
                            // MySQL accepts mixed prepared and plain addBatch calls. This is a
                            // bounded template inventory, not execution order or expanded bindings.
                            boolean includePrepared = state.preparedBatch && state.sql != null;
                            String templates = (includePrepared ? state.sql + ";\n" : "") + state.batchSql;
                            captured = bounded(templates); source = "batch";
                            truncated = state.batchTruncated || (includePrepared && state.truncated)
                                    || templates.length() > sqlLimit;
                        } else if (state.sql != null) {
                            captured = state.sql; source = "prepared"; truncated = state.truncated;
                        }
                    }
                }
            }
            Call call = new Call(snapshot, statement, system, operation, captured, source, truncated, batchSize, true);
            if (parametersEnabled) {
                synchronized (STATEMENTS) {
                    StatementState state = STATEMENTS.get(statement);
                    if (sql != null || (batch && state != null && !state.preparedBatch)) {
                        call.parametersStatus = "not-applicable";
                    } else if (state == null) {
                        call.parametersStatus = "unavailable";
                    } else if (batch) {
                        // Mixed plain/prepared batches cannot map a binding set to each SQL template.
                        call.parametersStatus = state.plainBatch || !state.parameterSetsAvailable ? "unavailable" : "batch";
                        if (!state.plainBatch) {
                            call.parameterSets = "[" + String.join(",", state.parameterSets) + "]";
                            call.parameterSetsOmitted = state.parameterSetsOmitted;
                            call.parametersTruncated = state.parameterSetsTruncated;
                        }
                    } else {
                        ParameterSnapshot bindings = parameterSnapshot(state);
                        call.parameters = bindings.json;
                        call.parametersStatus = state.parametersAvailable ? "captured" : "unavailable";
                        call.parametersTruncated = bindings.truncated;
                    }
                }
            }
            CURRENT.set(call);
            return call;
        } catch (Throwable ignored) { return null; }
    }

    private static Call nonRecording(TraceSnapshot snapshot, Object statement, String system, String operation) {
        // Drivers can rewrite batched bindings into internal SQL and call prepareStatement.
        // Shield those internal preparations even for executions that will not be captured.
        Call call = new Call(snapshot, statement, system, operation, null, "unavailable", false, -1, false);
        CURRENT.set(call);
        return call;
    }

    public static void exit(Object token, Throwable failure) {
        if (!(token instanceof Call)) return;
        Call call = (Call) token;
        try {
            if (call.finished || --call.depth > 0) return;
            call.finished = true;
            if (!call.record) return;
            Map<String, String> fields = fields(call.system, call.operation, "jdbc-execution", failure == null ? "OBSERVED" : "ERROR");
            if (call.sql != null) fields.put("sql", call.sql);
            fields.put("sqlSource", call.sqlSource);
            fields.put("sqlTruncated", String.valueOf(call.truncated));
            if (call.parametersStatus != null) {
                fields.put("sqlParametersStatus", call.parametersStatus);
                fields.put("sqlParametersTruncated", String.valueOf(call.parametersTruncated));
                if (call.parameters != null) fields.put("sqlParameters", call.parameters);
                if (call.parameterSets != null) fields.put("sqlParameterSets", call.parameterSets);
                if (call.parameterSetsOmitted > 0) fields.put("sqlParameterSetsOmitted", String.valueOf(call.parameterSetsOmitted));
            }
            fields.put("durationMs", String.valueOf(Math.max(0, System.nanoTime() - call.started) / 1000000.0));
            if (call.batchSize >= 0) fields.put("batchSize", String.valueOf(call.batchSize));
            if (failure != null) {
                String name = failure.getClass().getName();
                fields.put("errorType", name.substring(0, Math.min(256, name.length())));
            }
            emit(call.snapshot.getSegmentId(), fields);
        } catch (Throwable ignored) {
            // Optional evidence must never change JDBC return values or exceptions.
        } finally {
            if (call.depth <= 0 && CURRENT.get() == call) {
                if (call.operation.endsWith("Batch")) clearBatch(call.statement);
                CURRENT.remove();
            }
        }
    }

    private static void emit(String segment, Map<String, String> fields) {
        CALLBACK.set(Boolean.TRUE);
        try { TraceRuntime.onBoundary(segment, fields); } finally { CALLBACK.remove(); }
    }

    private static int reserve(TraceSnapshot snapshot) {
        synchronized (QUOTAS) {
            long now = System.nanoTime();
            Quota quota = QUOTAS.get(snapshot.getSegmentId());
            if (quota == null) {
                // Amortize weak-reference cleanup instead of scanning every quota on each request.
                if (QUOTAS.size() % 128 == 0 || QUOTAS.size() >= MAX_SEGMENTS) {
                    Iterator<Quota> entries = QUOTAS.values().iterator();
                    while (entries.hasNext()) {
                        Quota old = entries.next();
                        // Preserve quotas while async snapshots are alive. Inactive tombstones
                        // cover late copied contexts, without retaining SQL or snapshot objects.
                        if (old.snapshots.isEmpty() && now - old.lastSeen > INACTIVE_RETENTION_NANOS) entries.remove();
                    }
                }
                if (QUOTAS.size() >= MAX_SEGMENTS) return 0; // fail closed, never evict an active quota
                quota = new Quota();
                QUOTAS.put(snapshot.getSegmentId(), quota);
            }
            if (quota.snapshots.size() < 64) quota.snapshots.put(snapshot, Boolean.TRUE);
            quota.lastSeen = now;
            if (quota.count < queryLimit) { quota.count++; return 1; }
            if (!quota.limitEmitted) { quota.limitEmitted = true; return -1; }
            return 0;
        }
    }

    private static String bounded(String value) { return value.substring(0, Math.min(value.length(), sqlLimit)); }

    private static Parameter parameter(String setter, Object value) {
        String type = setter != null && setter.startsWith("set") ? setter.substring(3) : "Object";
        if (type.length() > 32) type = "Object";
        if ("Null".equals(type) || value == null) return new Parameter(type, null, "null");
        Class<?> kind = value.getClass();
        String text = null;
        // Exact class checks deliberately exclude user subclasses with arbitrary toString behavior.
        if (kind == String.class) text = (String) value;
        else if (kind == Boolean.class || kind == Byte.class || kind == Short.class || kind == Integer.class
                || kind == Long.class || kind == Float.class || kind == Double.class || kind == Character.class
                || kind == java.util.UUID.class || kind == java.sql.Date.class || kind == java.sql.Time.class
                || kind == java.sql.Timestamp.class || kind == java.time.LocalDate.class
                || kind == java.time.LocalTime.class || kind == java.time.LocalDateTime.class
                || kind == java.time.OffsetTime.class || kind == java.time.OffsetDateTime.class
                || kind == java.time.Instant.class) text = value.toString();
        else if (kind == java.math.BigDecimal.class) {
            if (((java.math.BigDecimal) value).precision() > MAX_VALUE_LENGTH)
                return new Parameter(type, "[numeric value exceeds capture limit]", "truncated");
            text = value.toString();
        } else if (kind == java.math.BigInteger.class) {
            if (((java.math.BigInteger) value).bitLength() > 1700)
                return new Parameter(type, "[numeric value exceeds capture limit]", "truncated");
            text = value.toString();
        }
        if (text == null) return new Parameter(type, null, "unsupported");
        return new Parameter(type, text.substring(0, Math.min(MAX_VALUE_LENGTH, text.length())),
                text.length() > MAX_VALUE_LENGTH ? "truncated" : "captured");
    }

    private static ParameterSnapshot parameterSnapshot(StatementState state) {
        StringBuilder json = new StringBuilder("[");
        boolean truncated = state.parametersTruncated;
        for (Map.Entry<Integer, Parameter> entry : state.parameters.entrySet()) {
            Parameter value = entry.getValue();
            String item = "{\"index\":" + entry.getKey() + ",\"type\":" + jsonString(value.type)
                    + ",\"value\":" + jsonString(value.value) + ",\"status\":" + jsonString(value.status) + "}";
            int separator = json.length() > 1 ? 1 : 0;
            if (json.length() + separator + item.length() + 1 > MAX_PARAMETER_LENGTH) { truncated = true; break; }
            if (separator != 0) json.append(',');
            json.append(item);
            truncated |= "truncated".equals(value.status);
        }
        return new ParameterSnapshot(json.append(']').toString(), truncated);
    }

    /** Only bounded safe scalar strings reach this serializer; no driver/result objects are consulted. */
    private static String jsonString(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20 || Character.isSurrogate(c)) {
                // Escaping UTF-16 code units keeps even truncated/unpaired surrogate strings valid JSON.
                out.append("\\u");
                String hex = Integer.toHexString(c);
                for (int n = hex.length(); n < 4; n++) out.append('0');
                out.append(hex);
            } else out.append(c);
        }
        return out.append('"').toString();
    }
    private static boolean isOperation(String value) {
        return "execute".equals(value) || "executeQuery".equals(value) || "executeUpdate".equals(value)
                || "executeLargeUpdate".equals(value) || "executeBatch".equals(value) || "executeLargeBatch".equals(value);
    }
    private static String system(String owner) {
        if (owner == null) return null;
        if (owner.startsWith("org.postgresql.jdbc.")) return "postgresql";
        if (owner.startsWith("com.mysql.cj.jdbc.") || owner.startsWith("com.mysql.jdbc.")) return "mysql";
        return null;
    }
    private static Map<String, String> fields(String system, String operation, String evidence, String status) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("boundaryKind", "database"); fields.put("system", system); fields.put("direction", "outbound");
        fields.put("operation", operation); fields.put("evidenceKind", evidence); fields.put("status", status);
        return fields;
    }
    private static final class StatementState {
        final String sql;
        final boolean truncated;
        final StringBuilder batchSql = new StringBuilder();
        int batchSize;
        boolean batchTruncated;
        boolean plainBatch;
        boolean preparedBatch;
        final Map<Integer, Parameter> parameters = new TreeMap<>();
        final List<String> parameterSets = new ArrayList<>();
        int parameterChars, parameterSetsLength, parameterSetsOmitted;
        boolean parametersTruncated, parameterSetsTruncated;
        boolean parametersAvailable, parameterSetsAvailable = true;
        StatementState(String sql, boolean truncated, boolean parametersAvailable) {
            this.sql = sql; this.truncated = truncated; this.parametersAvailable = parametersAvailable;
        }
    }
    private static final class Parameter {
        final String type, value, status;
        Parameter(String type, String value, String status) { this.type = type; this.value = value; this.status = status; }
        int length() { return value == null ? 0 : value.length(); }
        Parameter limit(int remaining) {
            return length() <= remaining ? this : new Parameter(type, value.substring(0, remaining), "truncated");
        }
    }
    private static final class ParameterSnapshot {
        final String json;
        final boolean truncated;
        ParameterSnapshot(String json, boolean truncated) { this.json = json; this.truncated = truncated; }
    }
    private static final class Quota {
        final WeakHashMap<TraceSnapshot, Boolean> snapshots = new WeakHashMap<>();
        int count;
        boolean limitEmitted;
        long lastSeen;
    }
    private static final class Call {
        final TraceSnapshot snapshot;
        final Object statement;
        final String system, operation, sql, sqlSource;
        final boolean truncated, record;
        final int batchSize;
        final long started = System.nanoTime();
        int depth = 1;
        boolean finished;
        String parametersStatus, parameters, parameterSets;
        boolean parametersTruncated;
        int parameterSetsOmitted;
        Call(TraceSnapshot snapshot, Object statement, String system, String operation, String sql, String source,
             boolean truncated, int batchSize, boolean record) {
            this.snapshot = snapshot; this.statement = statement; this.system = system; this.operation = operation; this.sql = sql;
            this.sqlSource = source; this.truncated = truncated; this.batchSize = batchSize; this.record = record;
        }
    }
    private static final class BatchCall {
        final Object statement;
        final String sql;
        int depth = 1;
        BatchCall(Object statement, String sql) { this.statement = statement; this.sql = sql; }
    }
    private static final class BindingCall {
        final Object statement;
        final int index;
        final Parameter parameter;
        int depth = 1;
        BindingCall(Object statement, int index, Parameter parameter) {
            this.statement = statement; this.index = index; this.parameter = parameter;
        }
    }
    /** Weak identity keys avoid executing JDBC proxy equals/hashCode/toString or retaining statements. */
    private static final class WeakIdentityMap<T> {
        final int limit;
        final ReferenceQueue<Object> queue = new ReferenceQueue<>();
        final Map<Key, T> values = new LinkedHashMap<>();
        WeakIdentityMap(int limit) { this.limit = limit; }
        synchronized T get(Object key) { clean(); return values.get(new Key(key, null)); }
        synchronized boolean put(Object key, T value) {
            clean(); Key reference = new Key(key, queue);
            if (!values.containsKey(reference) && values.size() >= limit) return false;
            values.put(reference, value); return true;
        }
        synchronized void remove(Object key) { clean(); values.remove(new Key(key, null)); }
        synchronized void clear() { values.clear(); clean(); }
        void clean() { Object ref; while ((ref = queue.poll()) != null) values.remove(ref); }
    }
    private static final class Key extends WeakReference<Object> {
        final int hash;
        Key(Object value, ReferenceQueue<Object> queue) { super(value, queue); hash = System.identityHashCode(value); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Key && get() != null && get() == ((Key) other).get();
        }
    }
}
