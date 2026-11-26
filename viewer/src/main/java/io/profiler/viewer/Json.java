package io.profiler.viewer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON parser/writer (maps, lists, strings, numbers, booleans, null). */
final class Json {
    private final String s;
    private int p;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String s) {
        Json j = new Json(s);
        j.skipWhitespace();
        Object v = j.readValue();
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String s) {
        return (Map<String, Object>) parse(s);
    }

    private void skipWhitespace() {
        while (p < s.length() && s.charAt(p) <= ' ') p++;
    }

    private Object readValue() {
        char c = s.charAt(p);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': p += 4; return Boolean.TRUE;
            case 'f': p += 5; return Boolean.FALSE;
            case 'n': p += 4; return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        p++;
        skipWhitespace();
        if (s.charAt(p) == '}') {
            p++;
            return m;
        }
        while (true) {
            skipWhitespace();
            String k = string();
            skipWhitespace();
            p++;
            skipWhitespace();
            m.put(k, readValue());
            skipWhitespace();
            if (s.charAt(p++) == '}') return m;
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        p++;
        skipWhitespace();
        if (s.charAt(p) == ']') {
            p++;
            return l;
        }
        while (true) {
            skipWhitespace();
            l.add(readValue());
            skipWhitespace();
            if (s.charAt(p++) == ']') return l;
        }
    }

    private String string() {
        int start = ++p;
        StringBuilder sb = null;
        while (true) {
            char c = s.charAt(p);
            if (c == '"') {
                String r = sb == null ? s.substring(start, p) : sb.append(s, start, p).toString();
                p++;
                return r;
            }
            if (c == '\\') {
                if (sb == null) sb = new StringBuilder();
                sb.append(s, start, p);
                char e = s.charAt(p + 1);
                p += 2;
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(p, p + 4), 16)); p += 4; break;
                    default: sb.append(e);
                }
                start = p;
            } else p++;
        }
    }

    private Object number() {
        int start = p;
        boolean dbl = false;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == '.' || c == 'e' || c == 'E') dbl = true;
            else if (!(c == '-' || c == '+' || (c >= '0' && c <= '9'))) break;
            p++;
        }
        String n = s.substring(start, p);
        return dbl ? (Object) Double.valueOf(n) : (Object) Long.valueOf(n);
    }

    static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static void write(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof String) writeEscapedString(sb, (String) o);
        else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            sb.append(Double.isNaN(d) || Double.isInfinite(d) ? "0" : String.valueOf(d));
        } else if (o instanceof Number || o instanceof Boolean) sb.append(o);
        else if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeEscapedString(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object x : (Iterable<Object>) o) {
                if (!first) sb.append(',');
                first = false;
                write(sb, x);
            }
            sb.append(']');
        } else writeEscapedString(sb, o.toString());
    }

    private static void writeEscapedString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
