package com.chargebee.tracelens.agent;

import com.chargebee.tracelens.bridge.AgentStatus;
import com.chargebee.tracelens.bridge.HttpClientTraceRuntime;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** JDK 8/21 HttpURLConnection and HTTPS delegates; independent of line-capture scope. */
public final class HttpClientInstrumentation {
    private static final String JDK_HTTP = "sun.net.www.protocol.http.HttpURLConnection";
    private HttpClientInstrumentation() { }

    public static void install(Instrumentation instrumentation) {
        try {
            // Header access is needed only for propagation. Its failure must not
            // disable metadata-only observation on an otherwise supported client.
            try { openJdkHeaders(instrumentation); }
            catch (Throwable failure) {
                HttpClientTraceRuntime.disableRedirectGuard();
                System.err.println("[tracelens] HTTP propagation disabled: JDK header access unavailable: " + failure);
            }
            Class.forName(JDK_HTTP, false, null);
            AtomicBoolean guardWoven = new AtomicBoolean();
            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .ignore(nameStartsWith("net.bytebuddy.").or(nameStartsWith("com.chargebee.tracelens.")))
                    .type(named(JDK_HTTP).or(named("java.net.HttpURLConnection")))
                    .transform((builder, type, loader, module, domain) -> {
                        DynamicType.Builder<?> result = builder.visit(Advice.to(HttpClientAdvice.Boundary.class)
                                .on(named("connect").or(named("getOutputStream")).or(named("getInputStream"))
                                        .or(named("getResponseCode")).or(named("disconnect"))
                                        .and(takesArguments(0)).and(not(isAbstract()))));
                        if (type.getName().equals(JDK_HTTP)) {
                            boolean hasGuard = type.getDeclaredMethods().filter(named("writeRequests").and(takesArguments(0))).size() == 1;
                            guardWoven.set(hasGuard);
                            if (hasGuard) {
                                result = result.visit(Advice.to(HttpClientAdvice.WriteGuard.class)
                                        .on(named("writeRequests").and(takesArguments(0))));
                            } else {
                                HttpClientTraceRuntime.disableRedirectGuard();
                                System.err.println("[tracelens] HTTP propagation disabled: JDK serialization guard unavailable; observing metadata only");
                            }
                        }
                        return result;
                    })
                    .with(new AgentBuilder.Listener.Adapter() {
                        @Override public void onTransformation(TypeDescription type, ClassLoader loader,
                                                               JavaModule module, boolean loaded, DynamicType dynamicType) {
                            if (JDK_HTTP.equals(type.getName()) && guardWoven.get()) {
                                try { HttpClientTraceRuntime.enableRedirectGuard(); }
                                catch (Exception failure) {
                                    HttpClientTraceRuntime.disableRedirectGuard();
                                    System.err.println("[tracelens] HTTP propagation disabled: redirect guard unavailable: " + failure);
                                }
                            }
                        }
                        @Override public void onError(String name, ClassLoader loader, JavaModule module,
                                                      boolean loaded, Throwable failure) {
                            if (JDK_HTTP.equals(name)) HttpClientTraceRuntime.disableRedirectGuard();
                            AgentStatus.noteFailedClass();
                            System.err.println("[tracelens] HTTP client instrumentation failed for " + name + ": " + failure);
                        }
                    })
                    .assureReadEdgeFromAndTo(instrumentation, HttpClientTraceRuntime.class)
                    .installOn(instrumentation);
        } catch (Throwable failure) {
            HttpClientTraceRuntime.disableRedirectGuard();
            System.err.println("[tracelens] HTTP client instrumentation unavailable (continuing application): " + failure);
        }
    }

    /** Reflection keeps the delivered agent Java-8-loadable while opening only the required JDK package on 9+. */
    private static void openJdkHeaders(Instrumentation instrumentation) throws Exception {
        Method getModule;
        try { getModule = Class.class.getMethod("getModule"); }
        catch (NoSuchMethodException java8) { return; }
        Object javaBase = getModule.invoke(Object.class);
        Object bridgeModule = getModule.invoke(HttpClientTraceRuntime.class);
        Class<?> module = Class.forName("java.lang.Module");
        Method redefine = Instrumentation.class.getMethod("redefineModule", module,
                Set.class, Map.class, Map.class, Set.class, Map.class);
        redefine.invoke(instrumentation, javaBase, Collections.singleton(bridgeModule), Collections.emptyMap(),
                Collections.singletonMap("sun.net.www", Collections.singleton(bridgeModule)),
                Collections.emptySet(), Collections.emptyMap());
    }
}
