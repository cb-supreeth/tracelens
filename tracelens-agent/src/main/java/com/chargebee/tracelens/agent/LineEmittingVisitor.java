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
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * Emits {@code TraceRuntime.onLine(fqcn, line)} at every line-number attribute of
 * every matched method. This is the core of line-level capture: javac records one
 * line-number entry per source line that produced bytecode, so hooking
 * {@link MethodVisitor#visitLineNumber} gives us exact executed-line events.
 *
 * Constructor hazard: the first line number of a constructor precedes the
 * super()/this() call, and the verifier rejects arbitrary calls before it. We
 * therefore suppress injection in <init> until the delegate constructor call is seen.
 */
final class LineEmittingVisitor {

    static final ElementMatcher.Junction<MethodDescription> INSTRUMENTABLE_METHOD =
            isMethod().and(not(isAbstract())).and(not(isNative()))
                    .and(not(isSynthetic())).and(not(isBridge()));

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
        return new LineEmittingMethodVisitor(methodVisitor, instrumentedType.getName(),
                instrumentedMethod.isConstructor());
    }

    private static final class LineEmittingMethodVisitor extends MethodVisitor {

        private static final String RECORDER = "com/chargebee/tracelens/bridge/TraceRuntime";

        private final String fqcn;
        private boolean injectionAllowed;

        LineEmittingMethodVisitor(MethodVisitor mv, String fqcn, boolean constructor) {
            super(Opcodes.ASM9, mv);
            this.fqcn = fqcn;
            this.injectionAllowed = !constructor;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (!injectionAllowed && opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)) {
                injectionAllowed = true;
            }
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            super.visitLineNumber(line, start);
            if (!injectionAllowed) {
                return;
            }
            mv.visitLdcInsn(fqcn);
            mv.visitLdcInsn(line);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "onLine", "(Ljava/lang/String;I)V", false);
        }
    }
}
