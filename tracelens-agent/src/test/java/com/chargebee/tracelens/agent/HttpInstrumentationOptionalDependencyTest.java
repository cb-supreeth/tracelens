package com.chargebee.tracelens.agent;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Test;
import static net.bytebuddy.matcher.ElementMatchers.*;
import static org.assertj.core.api.Assertions.*;

/** Missing optional logging integrations must not be counted as failed HTTP instrumentation. */
class HttpInstrumentationOptionalDependencyTest {
    private final ElementMatcher.Junction<TypeDescription> servletHierarchy =
            hasSuperType(named("javax.servlet.Filter"));

    @Test void loggingCoreIsSkippedBeforeResolvingAbsentOptionalParent() {
        TypeDescription optional = missingParent("org.apache.logging.log4j.core.async.OptionalDisruptorPlugin");
        // Reproduce the previous matcher failure without installing optional jars.
        assertThatThrownBy(() -> servletHierarchy.matches(optional))
                .isInstanceOf(TypePool.Resolution.NoSuchTypeException.class);
        assertThat(HttpInstrumentation.ignoredTypes().matches(optional)).isTrue();
        // AgentBuilder's ignore matcher is applied before its servlet type matcher.
        assertThat(not(HttpInstrumentation.ignoredTypes()).and(servletHierarchy).matches(optional)).isFalse();
    }

    @Test void genuineApplicationHierarchyFailuresAreNotSuppressed() {
        TypeDescription application = missingParent("com.chargebee.app.filters.BrokenApplicationFilter");
        assertThat(HttpInstrumentation.ignoredTypes().matches(application)).isFalse();
        assertThatThrownBy(() -> not(HttpInstrumentation.ignoredTypes()).and(servletHierarchy).matches(application))
                .isInstanceOf(TypePool.Resolution.NoSuchTypeException.class);
    }

    @Test void log4jWebServletIntegrationIsNotExcludedWithLoggingCore() {
        TypeDescription web = missingParent("org.apache.logging.log4j.web.Log4jServletFilter");
        assertThat(HttpInstrumentation.ignoredTypes().matches(web)).isFalse();
        assertThatThrownBy(() -> not(HttpInstrumentation.ignoredTypes()).and(servletHierarchy).matches(web))
                .isInstanceOf(TypePool.Resolution.NoSuchTypeException.class);
    }

    private static TypeDescription missingParent(String name) {
        ClassWriter bytes = new ClassWriter(0);
        bytes.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null,
                "tracelens/fixture/absent/OptionalDependency", null);
        bytes.visitEnd();
        ClassFileLocator locator = new ClassFileLocator.Compound(
                ClassFileLocator.Simple.of(name, bytes.toByteArray()), ClassFileLocator.ForClassLoader.ofSystemLoader());
        return TypePool.Default.of(locator).describe(name).resolve();
    }
}
