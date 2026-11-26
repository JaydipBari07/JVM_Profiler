package io.profiler.agent.util;

/** Normalises request URIs so that /orders/42 and /orders/43 are grouped together as /orders/{id}. */
public final class UriMasker {
    private UriMasker() {
    }

    public static String mask(String uri, boolean maskNumericSegments) {
        int semi = uri.indexOf(';');
        if (semi >= 0) uri = uri.substring(0, semi);
        if (!maskNumericSegments) return uri;
        StringBuilder sb = new StringBuilder(uri.length());
        int i = 0, n = uri.length();
        while (i < n) {
            if (uri.charAt(i) == '/') {
                sb.append('/');
                i++;
                continue;
            }
            int j = uri.indexOf('/', i);
            if (j < 0) j = n;
            sb.append(looksLikeId(uri, i, j) ? "{id}" : uri.substring(i, j));
            i = j;
        }
        return sb.toString();
    }

    /** True for all-digit segments and for long hex/UUID-like segments. */
    private static boolean looksLikeId(String s, int from, int to) {
        int len = to - from;
        if (len == 0) return false;
        boolean digits = true, hex = true;
        int dashes = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            boolean d = c >= '0' && c <= '9';
            if (!d) digits = false;
            if (c == '-') dashes++;
            else if (!d && !((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) hex = false;
        }
        return digits || (hex && len >= 16 && dashes <= 4);
    }
}
