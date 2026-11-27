package io.profiler.agent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Typed, forgiving readers for {@link Properties}: a missing or malformed value falls back to the default. */
public final class PropertyParser {
    private PropertyParser() {
    }

    public static boolean parseBool(Properties p, String key, boolean defaultValue) {
        String v = p.getProperty(key);
        return v == null ? defaultValue : Boolean.parseBoolean(v.trim());
    }

    public static double parseDouble(Properties p, String key, double defaultValue) {
        String v = p.getProperty(key);
        try {
            return v == null ? defaultValue : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static long parseLong(Properties p, String key, long defaultValue) {
        String v = p.getProperty(key);
        if (v == null) return defaultValue;
        try {
            return (long) Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Splits on comma or semicolon, trims, and drops empty items. A missing property returns {@code defaultValue}. */
    public static String[] parseList(String value, String[] defaultValue) {
        if (value == null) return defaultValue;
        List<String> out = new ArrayList<String>();
        for (String s : value.split("[,;]"))
            if (!s.trim().isEmpty())
                out.add(s.trim());
        return out.toArray(new String[0]);
    }
}
