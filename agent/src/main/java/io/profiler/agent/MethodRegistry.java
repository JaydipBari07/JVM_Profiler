package io.profiler.agent;

import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Used to generate ID for methods and store it in map name -> ID
 */
public final class MethodRegistry {
    private static final Map<String, Integer> IDS = new HashMap<>();
    private static final List<String> NAMES = new ArrayList<>();

    public static synchronized int register(String owner, String name, String desc) {
        String full = owner.replace('/', '.') + "." + name + "(" + paramNames(desc) + ")";
        Integer id = IDS.get(full);
        if (id == null) {
            id = NAMES.size();
            NAMES.add(full);
            IDS.put(full, id);
        }
        return id;
    }

    public static synchronized String methodName(int id) {
        return id >= 0 && id < NAMES.size() ? NAMES.get(id) : "?";
    }

    private static String paramNames(String desc) {
        Type[] args = Type.getArgumentTypes(desc);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(',');
            String c = args[i].getClassName();
            int dot = Math.max(c.lastIndexOf('.'), c.lastIndexOf('$'));
            sb.append(dot >= 0 ? c.substring(dot + 1) : c);
        }
        return sb.toString();
    }
}
