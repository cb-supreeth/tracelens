package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.JdbcQueryRuntime;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/** All advice is suppressed/fail-open. Positional bindings require a separate explicit opt-in. */
public final class JdbcAdvice {
    private JdbcAdvice() { }
    public static final class Prepare {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.Argument(0) String sql,
                                @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object statement) {
            JdbcQueryRuntime.prepared(statement, sql);
        }
    }
    public static final class WithSql {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object enter(@Advice.This Object statement, @Advice.Origin("#t") String owner,
                                   @Advice.Origin("#m") String operation, @Advice.Argument(0) String sql) {
            return JdbcQueryRuntime.enter(statement, owner, operation, sql);
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Thrown Throwable failure) {
            JdbcQueryRuntime.exit(token, failure);
        }
    }
    public static final class WithoutSql {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object enter(@Advice.This Object statement, @Advice.Origin("#t") String owner,
                                   @Advice.Origin("#m") String operation) {
            return JdbcQueryRuntime.enter(statement, owner, operation, null);
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Thrown Throwable failure) {
            JdbcQueryRuntime.exit(token, failure);
        }
    }
    public static final class AddBatchSql {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object enter(@Advice.This Object statement, @Advice.Argument(0) String sql) {
            return JdbcQueryRuntime.enterBatch(statement, sql);
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Thrown Throwable failure) {
            JdbcQueryRuntime.exitBatch(token, failure);
        }
    }
    public static final class AddBatchPrepared {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object enter(@Advice.This Object statement) { return JdbcQueryRuntime.enterBatch(statement, null); }
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Thrown Throwable failure) {
            JdbcQueryRuntime.exitBatch(token, failure);
        }
    }
    public static final class ClearBatch {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.This Object statement) { JdbcQueryRuntime.clearBatch(statement); }
    }
    public static final class Binding {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object enter(@Advice.This Object statement, @Advice.Origin("#m") String setter,
                                   @Advice.Argument(0) int index,
                                   @Advice.Argument(value = 1, typing = Assigner.Typing.DYNAMIC) Object value) {
            return JdbcQueryRuntime.enterBinding(statement, setter, index, value);
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Thrown Throwable failure) {
            JdbcQueryRuntime.exitBinding(token, failure);
        }
    }
    public static final class ClearParameters {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.This Object statement) { JdbcQueryRuntime.clearParameters(statement); }
    }
    public static final class Close {
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.This Object statement) { JdbcQueryRuntime.closed(statement); }
    }
}
