package io.profiler.agent.instrument;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites ONE method's bytecode so that it reports to {@link Profiler}.
 * <p>
 * ASM calls the visit* methods below while it reads the original method, in order. Whatever we pass on to {@code mv}
 * ends up in the new class file, so we can add our own instructions before, during and after the original ones.
 * <p>
 * A method like
 * <pre>
 *   void foo() { ...body... }
 * </pre>
 * effectively becomes
 * <pre>
 *   void foo() {
 *       Profiler.enter(id);
 *       try { ...body... (Profiler.exit(id) before every return) }
 *       catch (Throwable t) { Profiler.exitError(t, id); throw t; }
 *   }
 * </pre>
 * HTTP request entry points use enterHttpRequest/exitHttpRequest, which additionally receive the request and response objects
 * (parameters 1 and 2 of the entry method).
 */
final class MethodWrapper extends MethodVisitor {
    private static final String PROFILER = "io/profiler/agent/Profiler";
    private static final int REQUEST_VAR = 1;
    private static final int RESPONSE_VAR = 2;
    /** For JDBC execute methods: the SQL string is the first parameter (slot 0 is {@code this}). */
    private static final int SQL_VAR = 1;

    private final int classVersion, methodId;
    private final boolean httpEntryPoint, captureSql;
    private final Label tryStart = new Label(), tryEnd = new Label(), handler = new Label();

    MethodWrapper(MethodVisitor mv, int classVersion, int methodId, boolean httpEntryPoint, boolean captureSql) {
        super(Opcodes.ASM9, mv);
        this.classVersion = classVersion;
        this.methodId = methodId;
        this.httpEntryPoint = httpEntryPoint;
        this.captureSql = captureSql;
    }

    /** Called once at the very start of the method: insert the "enter" call, then open the try block. */
    @Override
    public void visitCode() {
        super.visitCode();
        pushMethodId();
        if (httpEntryPoint) {
            mv.visitVarInsn(Opcodes.ALOAD, REQUEST_VAR);
            mv.visitVarInsn(Opcodes.ALOAD, RESPONSE_VAR);
            callProfiler("enterHttpRequest", "(ILjava/lang/Object;Ljava/lang/Object;)V");
        } else if (captureSql) {
            mv.visitVarInsn(Opcodes.ALOAD, SQL_VAR);
            callProfiler("enterSql", "(ILjava/lang/String;)V");
        } else {
            callProfiler("enter", "(I)V");
        }
        mv.visitLabel(tryStart);
    }

    /** Called for every simple instruction: right before each return instruction, insert the "exit" call. */
    @Override
    public void visitInsn(int opcode) {
        if (isReturn(opcode)) {
            pushMethodId();
            if (httpEntryPoint) {
                mv.visitVarInsn(Opcodes.ALOAD, RESPONSE_VAR);
                callProfiler("exitHttpRequest", "(ILjava/lang/Object;)V");
            } else {
                callProfiler("exit", "(I)V");
            }
        }
        super.visitInsn(opcode);
    }

    /** Called once at the very end: close the try block and add the catch handler that reports exceptions. */
    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        mv.visitLabel(tryEnd);
        mv.visitTryCatchBlock(tryStart, tryEnd, handler, null);
        mv.visitLabel(handler);
        // Declare zero locals: all covered frames' locals are assignable to top, so this frame is always valid.
        if (classVersion >= Opcodes.V1_6)
            mv.visitFrame(Opcodes.F_FULL, 0, new Object[0], 1, new Object[]{"java/lang/Throwable"});
        mv.visitInsn(Opcodes.DUP);
        pushMethodId();
        callProfiler("exitError", "(Ljava/lang/Throwable;I)V");
        mv.visitInsn(Opcodes.ATHROW);
        super.visitMaxs(maxStack, maxLocals);
    }

    private static boolean isReturn(int opcode) {
        return opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN;
    }

    private void callProfiler(String method, String descriptor) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PROFILER, method, descriptor, false);
    }

    /** Pushes the method id as a constant using the smallest suitable instruction. */
    private void pushMethodId() {
        if (methodId >= Byte.MIN_VALUE && methodId <= Byte.MAX_VALUE) mv.visitIntInsn(Opcodes.BIPUSH, methodId);
        else if (methodId >= Short.MIN_VALUE && methodId <= Short.MAX_VALUE) mv.visitIntInsn(Opcodes.SIPUSH, methodId);
        else mv.visitLdcInsn(Integer.valueOf(methodId));
    }
}
