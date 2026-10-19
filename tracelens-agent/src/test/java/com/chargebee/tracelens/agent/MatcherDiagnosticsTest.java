package com.chargebee.tracelens.agent;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Test;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static org.assertj.core.api.Assertions.assertThat;

/** Diagnostic: evaluate INSTRUMENTABLE_METHOD against TypePool-loaded method
 *  descriptions (the AgentBuilder path), which can differ from reflection-loaded ones. */
class MatcherDiagnosticsTest {

    @Test
    void ordinaryAndLambdaMethodsMatchUnderTypePoolResolution() {
        TypePool typePool = TypePool.Default.ofSystemLoader();
        TypeDescription type = typePool.describe("com.chargebee.ems.fixture.AgentPathFixture").resolve();

        boolean sawMethod = false;
        boolean sawLambda = false;
        for (MethodDescription method : type.getDeclaredMethods()) {
            boolean matches = LineEmittingVisitor.INSTRUMENTABLE_METHOD.matches(method);
            System.out.println("[matcher] " + method.getName()
                    + " synthetic=" + method.isSynthetic()
                    + " bridge=" + method.isBridge()
                    + " method=" + method.isMethod()
                    + " -> matches=" + matches);
            if (method.getName().startsWith("lambda$")) {
                sawLambda = true;
                assertThat(matches).as("lambda method %s must match", method.getName()).isTrue();
            } else if (method.getName().equals("run")) {
                sawMethod = true;
                assertThat(matches).as("ordinary method %s must match", method.getName()).isTrue();
            }
        }
        assertThat(sawMethod).as("fixture must have an ordinary method").isTrue();
        assertThat(sawLambda).as("fixture must have lambda methods").isTrue();
    }

    @Test
    void constructorsAndStaticInitializersDoNotMatch() {
        TypeDescription type = TypePool.Default.ofSystemLoader()
                .describe("com.chargebee.ems.fixture.AgentPathFixture").resolve();
        MethodDescription constructor = type.getDeclaredMethods().filter(isConstructor()).getOnly();
        // Type initializers are not ordinary declared methods; use Byte Buddy's
        // dedicated description to exercise the same matcher explicitly.
        MethodDescription initializer = new MethodDescription.Latent.TypeInitializer(type);

        assertThat(LineEmittingVisitor.INSTRUMENTABLE_METHOD.matches(constructor))
                .as("constructors must not receive line callbacks").isFalse();
        assertThat(LineEmittingVisitor.INSTRUMENTABLE_METHOD.matches(initializer))
                .as("static initializers must not receive line callbacks").isFalse();
    }
}
