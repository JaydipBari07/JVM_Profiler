package io.profiler.agent.util;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/** Reflection helpers for calling methods on objects whose classes we cannot reference directly (e.g. servlet requests). */
public final class Reflection {
    private Reflection() {
    }

    /**
     * Finds a public method callable on instances of {@code c}. Looks in {@code c} if it is public, otherwise in its
     * interfaces and superclasses (the concrete class is often a non-public container class).
     */
    public static Method findPublicMethod(Class<?> c, String name, Class<?>... params) {
        return find(c, name, params, new HashSet<Class<?>>());
    }

    private static Method find(Class<?> c, String name, Class<?>[] params, Set<Class<?>> seen) {
        if (c == null || !seen.add(c)) return null;
        if (Modifier.isPublic(c.getModifiers())) {
            try {
                return c.getMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
        for (Class<?> i : c.getInterfaces()) {
            Method m = find(i, name, params, seen);
            if (m != null) return m;
        }
        return find(c.getSuperclass(), name, params, seen);
    }
}
