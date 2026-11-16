package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.ClickHouseQueryRuntime;
import java.util.Map;
import net.bytebuddy.asm.Advice;

/** Native v2 query arguments only; QuerySettings and responses are never inspected. */
public final class ClickHouseQueryAdvice {
    private ClickHouseQueryAdvice() { }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object enter(@Advice.Argument(0) String sql, @Advice.Argument(1) Map<?, ?> parameters) {
        return ClickHouseQueryRuntime.enter(sql, parameters);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter Object token, @Advice.Return Object result, @Advice.Thrown Throwable failure) {
        ClickHouseQueryRuntime.exit(token, result, failure);
    }
}
