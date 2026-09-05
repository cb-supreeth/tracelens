package com.chargebee.tracelens.agent;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Diagnostic: evaluate INSTRUMENTABLE_METHOD against TypePool-loaded method
 *  descriptions (the AgentBuilder path), which can differ from reflection-loaded ones. */
class MatcherDiagnosticsTest {

    @Test
    void lambdaMethodsMatchUnderTypePoolResolution() {
        TypePool typePool = TypePool.Default.ofSystemLoader();
        TypeDescription type = typePool.describe("com.chargebee.ems.fixture.AgentPathFixture").resolve();

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
            }
        }
        assertThat(sawLambda).as("fixture must have lambda methods").isTrue();
    }
}
