package io.profiler.agent.instrument;

import io.profiler.agent.Config;
import io.profiler.agent.MethodRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The JVM calls {@link #transform} with the raw bytes of every class it loads. We return changed bytes for the classes
 * we want to measure (see {@link MethodWrapper} for what the change looks like), or null to leave a class untouched.
 * <p>
 * Three kinds of classes are changed:
 * <ul>
 * <li>classes matching the {@code include} setting: all non-trivial methods are measured,</li>
 * <li>servlet entry points (Tomcat filter chain, HttpServlet): only the request entry method, to start a request trace,</li>
 * <li>JDBC statement implementations: only the {@code execute*} methods, to time SQL calls.</li>
 * </ul>
 */
public final class Transformer implements ClassFileTransformer {
    private static final String OWN_PACKAGE = "io/profiler/";
    private static final String TOMCAT_FILTER_CHAIN = "org/apache/catalina/core/ApplicationFilterChain";
    private static final String JAVAX_SERVLET = "javax/servlet/http/HttpServlet";
    private static final String JAKARTA_SERVLET = "jakarta/servlet/http/HttpServlet";
    private static final Set<String> ENTRY_CLASSES = new HashSet<String>(Arrays.asList(TOMCAT_FILTER_CHAIN, JAVAX_SERVLET, JAKARTA_SERVLET));
    private static final Set<String> JDBC_INTERFACES = new HashSet<String>(
            Arrays.asList("java/sql/Statement", "java/sql/PreparedStatement", "java/sql/CallableStatement", "java/sql/Connection", "org/hibernate/ejb/HibernateEntityManagerImplementor", "javax/persistence/EntityManager"));
    private static final String JAVAX_FILTER_DESC = "(Ljavax/servlet/ServletRequest;Ljavax/servlet/ServletResponse;)V";
    private static final String JAKARTA_FILTER_DESC = "(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;)V";
    private static final String JAVAX_SERVICE_DESC = "(Ljavax/servlet/http/HttpServletRequest;Ljavax/servlet/http/HttpServletResponse;)V";
    private static final String JAKARTA_SERVICE_DESC = "(Ljakarta/servlet/http/HttpServletRequest;Ljakarta/servlet/http/HttpServletResponse;)V";
    /** Class-file major version offset in the header (bytes 6-7). */
    private static final int VERSION_OFFSET = 6;

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain pd, byte[] classBytes) {
        if (loader == null || name == null || name.startsWith(OWN_PACKAGE)) return null;
        try {
            boolean entryClass = ENTRY_CLASSES.contains(name);
            boolean included = Config.included(name);
            ClassReader reader = new ClassReader(classBytes);
            // Entry classes must always be instrumented — e.g. Tomcat 6's ApplicationFilterChain is Java-5 bytecode.
            if (!entryClass && reader.readUnsignedShort(VERSION_OFFSET) < Opcodes.V1_6) return null;
            boolean jdbc = !entryClass && !included && Config.jdbc && implementsJdbc(reader);
            if (!entryClass && !included && !jdbc) return null;
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            Visitor visitor = new Visitor(writer, entryClass && !included, jdbc);
            reader.accept(visitor, 0);
            return visitor.changed ? writer.toByteArray() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean implementsJdbc(ClassReader reader) {
        for (String iface : reader.getInterfaces()) {
            if (JDBC_INTERFACES.contains(iface)) return true;
        }
        return false;
    }

    private static final class Visitor extends ClassVisitor {
        final boolean entryOnly, jdbcOnly;
        String owner;
        int version;
        boolean changed;

        Visitor(ClassVisitor cv, boolean entryOnly, boolean jdbcOnly) {
            super(Opcodes.ASM9, cv);
            this.entryOnly = entryOnly;
            this.jdbcOnly = jdbcOnly;
        }

        @Override
        public void visit(int version, int access, String name, String sig, String superName, String[] ifaces) {
            this.version = version;
            this.owner = name;
            super.visit(version, access, name, sig, superName, ifaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
            MethodVisitor mv = super.visitMethod(access, name, desc, sig, exc);
            if (mv == null) return null;
            boolean isHttpRequestEntry = isHttpRequestEntry(owner, name, desc);
            if (!isHttpRequestEntry && !isInstrumentable(access, name, desc))
                return mv;
            changed = true;
            boolean captureSql = jdbcOnly && desc.startsWith("(Ljava/lang/String;")
                    && (name.startsWith("execute") || name.equals("prepareStatement") || name.equals("prepareCall") || name.equals("createNativeQuery") || name.equals("createQuery")) ;
            return new MethodWrapper(mv, version, MethodRegistry.register(owner, name, desc), isHttpRequestEntry, captureSql);
        }

        private boolean isInstrumentable(int access, String name, String desc) {
            if (entryOnly) return false;
            if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0)
                return false;
            if (name.charAt(0) == '<') return false;
            if (jdbcOnly)
                return name.startsWith("execute") || name.equals("prepareStatement") || name.equals("prepareCall") || name.equals("createNativeQuery") || name.equals("createQuery");
            if (!Config.skipTrivial) return true;
            switch (name) {
                case "toString":
                case "hashCode":
                case "equals":
                    return false;
                default:
            }
            boolean noArgs = desc.startsWith("()");
            if (noArgs && (name.startsWith("get") || name.startsWith("is")) && !desc.endsWith(")V")) return false;
            return !(name.startsWith("set") && desc.endsWith(")V") && Type.getArgumentTypes(desc).length == 1);
        }

        private static boolean isHttpRequestEntry(String owner, String name, String desc) {
            if (owner.equals(TOMCAT_FILTER_CHAIN)) {
                return name.equals("doFilter") && (desc.equals(JAVAX_FILTER_DESC) || desc.equals(JAKARTA_FILTER_DESC));
            }
            if (owner.equals(JAVAX_SERVLET)) return name.equals("service") && desc.equals(JAVAX_SERVICE_DESC);
            if (owner.equals(JAKARTA_SERVLET)) return name.equals("service") && desc.equals(JAKARTA_SERVICE_DESC);
            return false;
        }
    }
}
