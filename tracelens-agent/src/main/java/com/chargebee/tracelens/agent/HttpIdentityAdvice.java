package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.HttpIdentityRuntime;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.ElementType;
import net.bytebuddy.asm.Advice;

/** Successful-exit-only callback; no authentication logic or host types are linked here. */
public final class HttpIdentityAdvice {
    private HttpIdentityAdvice() { }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface HookId { }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@HookId String hookId,
                            @Advice.This(optional = true) Object receiver,
                            @Advice.AllArguments Object[] arguments) {
        HttpIdentityRuntime.onSuccessfulExit(hookId, receiver, arguments);
    }
}
