package com.chargebee.tracelens.agent;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.asm.AsmVisitorWrapper;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * Emits {@code TraceRuntime.onLine(fqcn, line)} at every line-number attribute of
 * every matched method. Compiled line-table entries are bytecode-to-source mappings,
 * not necessarily one per source statement or source line. A callback proves that
 * a marker was reached, not that every operation on that line completed. Without
 * line debug metadata, {@link MethodVisitor#visitLineNumber} supplies no line events.
 *
 * The isMethod matcher excludes constructors and class initializers; this visitor
 * is only installed for ordinary methods and eligible lambda bodies.
 */
final class LineEmittingVisitor {

    /**
     * Instrumentable methods: concrete, non-bridge. Synthetic methods are excluded
     * EXCEPT lambda bodies (lambda$...) — lambdas are where modern Java business logic
     * lives (cb-ems controllers return Callable lambdas), and excluding them would
     * blind the tracer exactly where it matters.
     */
    static final ElementMatcher.Junction<MethodDescription> INSTRUMENTABLE_METHOD =
            isMethod().and(not(isAbstract())).and(not(isNative()))
                    .and(not(isBridge()))
                    .and(not(isSynthetic()).or(nameStartsWith("lambda$")));

    static AsmVisitorWrapper.ForDeclaredMethods forMethods() {
        return new AsmVisitorWrapper.ForDeclaredMethods()
                .writerFlags(net.bytebuddy.jar.asm.ClassWriter.COMPUTE_FRAMES)
                .method(INSTRUMENTABLE_METHOD, LineEmittingVisitor::wrap);
    }

    private static MethodVisitor wrap(TypeDescription instrumentedType,
                                      MethodDescription instrumentedMethod,
                                      MethodVisitor methodVisitor,
                                      Implementation.Context implementationContext,
                                      TypePool typePool,
                                      int writerFlags,
                                      int readerFlags) {
        return new LineEmittingMethodVisitor(methodVisitor, instrumentedType.getName());
    }

    private static final class LineEmittingMethodVisitor extends MethodVisitor {

        private static final String RECORDER = "com/chargebee/tracelens/bridge/TraceRuntime";

        private final String fqcn;

        LineEmittingMethodVisitor(MethodVisitor mv, String fqcn) {
            super(Opcodes.ASM9, mv);
            this.fqcn = fqcn;
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            super.visitLineNumber(line, start);
            mv.visitLdcInsn(fqcn);
            mv.visitLdcInsn(line);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "onLine", "(Ljava/lang/String;I)V", false);
        }
    }
}
