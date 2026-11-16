package com.chargebee.tracelens.agent;

import java.lang.instrument.Instrumentation;
import java.nio.ByteBuffer;
import com.chargebee.tracelens.bridge.AgentStatus;
import com.chargebee.tracelens.bridge.HttpTraceRuntime;
import com.chargebee.tracelens.bridge.HttpRequestBodyCapture;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.utility.JavaModule;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Passive Tomcat 7/9/10 input observation; installed only when explicitly enabled. */
public final class HttpRequestBodyInstrumentation {
    private HttpRequestBodyInstrumentation() { }
    public static void install(Instrumentation instrumentation) {
        new AgentBuilder.Default().disableClassFormatChanges()
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(named("org.apache.catalina.connector.Request"))
                .transform((builder, type, loader, module, domain) -> builder.visit(Advice.to(Bind.class).on(
                        namedOneOf("getInputStream", "getReader", "getParameter", "getParameterMap",
                                "getParameterNames", "getParameterValues").and(not(isAbstract())))))
                .type(named("org.apache.catalina.connector.InputBuffer"))
                .transform((builder, type, loader, module, domain) -> builder
                        .visit(Advice.to(Bytes.class).on(named("read").and(takesArguments(byte[].class, int.class, int.class))))
                        .visit(Advice.to(Chars.class).on(named("read").and(takesArguments(char[].class, int.class, int.class))))
                        .visit(Advice.to(Characters.class).on(named("read").and(takesArguments(char[].class))))
                        .visit(Advice.to(SingleByte.class).on(named("readByte").and(takesArguments(0))))
                        .visit(Advice.to(SingleChar.class).on(named("read").and(takesArguments(0))))
                        .visit(Advice.to(Buffer.class).on(named("read").and(takesArguments(ByteBuffer.class))))
                        .visit(Advice.to(Invalidate.class).on(namedOneOf("skip", "reset", "recycle"))))
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override public void onError(String name, ClassLoader loader, JavaModule module,
                                                  boolean loaded, Throwable failure) {
                        AgentStatus.noteFailedClass();
                        System.err.println("[tracelens] HTTP body instrumentation failed for " + name + ": " + failure.getClass().getName());
                    }
                }).installOn(instrumentation);
    }
    public static class Bind {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.This Object request, @Advice.FieldValue("inputBuffer") Object buffer) {
            HttpTraceRuntime.bindRequestBody(request, buffer);
        }
    }
    public static class Bytes {
        @Advice.OnMethodEnter(suppress = Throwable.class) public static Object enter(@Advice.This Object buffer) { return HttpRequestBodyCapture.beginRead(buffer); }
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Argument(0) byte[] value, @Advice.Argument(1) int offset,
                                @Advice.Return int count, @Advice.Thrown Throwable failure) { HttpRequestBodyCapture.endBytes(token, value, offset, count, failure); }
    }
    public static class Chars {
        @Advice.OnMethodEnter(suppress = Throwable.class) public static Object enter(@Advice.This Object buffer) { return HttpRequestBodyCapture.beginRead(buffer); }
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Argument(0) char[] value, @Advice.Argument(1) int offset,
                                @Advice.Return int count, @Advice.Thrown Throwable failure) { HttpRequestBodyCapture.endChars(token, value, offset, count, failure); }
    }
    public static class Characters {
        @Advice.OnMethodEnter(suppress = Throwable.class) public static Object enter(@Advice.This Object buffer) { return HttpRequestBodyCapture.beginRead(buffer); }
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Argument(0) char[] value,
                                @Advice.Return int count, @Advice.Thrown Throwable failure) { HttpRequestBodyCapture.endChars(token, value, 0, count, failure); }
    }
    public static class SingleByte {
        @Advice.OnMethodEnter(suppress = Throwable.class) public static Object enter(@Advice.This Object buffer) { return HttpRequestBodyCapture.beginRead(buffer); }
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Return int value, @Advice.Thrown Throwable failure) { HttpRequestBodyCapture.endSingle(token, value, false, failure); }
    }
    public static class SingleChar {
        @Advice.OnMethodEnter(suppress = Throwable.class) public static Object enter(@Advice.This Object buffer) { return HttpRequestBodyCapture.beginRead(buffer); }
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Return int value, @Advice.Thrown Throwable failure) { HttpRequestBodyCapture.endSingle(token, value, true, failure); }
    }
    public static class Buffer {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object enter(@Advice.This Object buffer, @Advice.Argument(0) ByteBuffer value, @Advice.Local("position") int position) {
            position = value.position(); return HttpRequestBodyCapture.beginRead(buffer);
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token, @Advice.Argument(0) ByteBuffer value, @Advice.Local("position") int position,
                                @Advice.Return int count, @Advice.Thrown Throwable failure) { HttpRequestBodyCapture.endBuffer(token, value, position, count, failure); }
    }
    public static class Invalidate {
        @Advice.OnMethodEnter(suppress = Throwable.class) public static void enter(@Advice.This Object buffer) { HttpRequestBodyCapture.invalidateBuffer(buffer); }
    }
}
