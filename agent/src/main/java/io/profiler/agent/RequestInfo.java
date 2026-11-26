package io.profiler.agent;

import static io.profiler.agent.util.Reflection.findPublicMethod;

import io.profiler.agent.util.UriMasker;

import java.lang.reflect.Method;

/**
 * This provides reflection info of required methods of any HttpRequest/HttpResponse
 */
final class RequestInfo {
    private static final ClassValue<RequestInfo> REQ = new ClassValue<RequestInfo>() {
        @Override
        protected RequestInfo computeValue(Class<?> c) {
            return new RequestInfo(c, true);
        }
    };
    private static final ClassValue<RequestInfo> RESP = new ClassValue<RequestInfo>() {
        @Override
        protected RequestInfo computeValue(Class<?> c) {
            return new RequestInfo(c, false);
        }
    };

    private Method getMethod, getUri, getHeader, getStatus;

    private RequestInfo(Class<?> c, boolean request) {
        if (request) {
            getMethod = findPublicMethod(c, "getMethod");
            getUri = findPublicMethod(c, "getRequestURI");
            getHeader = findPublicMethod(c, "getHeader", String.class);
        } else {
            getStatus = findPublicMethod(c, "getStatus");
            if (getStatus == null)
                getStatus = findPublicMethod(c, "getStatusCode"); // Spring-style wrappers
            if (getStatus == null)
                getStatus = findPublicMethod(c, "getCode");       // JAX-RS Response
        }
    }

    //Fills the trace from RequestInfo
    static void fillFromRequest(Trace t, Object req) {
        String method = null, uri = null, rid = null;
        try {
            if (req != null) {
                RequestInfo ri = REQ.get(req.getClass());
                if (ri.getMethod != null)
                    method = (String) ri.getMethod.invoke(req);
                if (ri.getUri != null)
                    uri = (String) ri.getUri.invoke(req);
                if (ri.getHeader != null) {
                    for (String h : Config.idHeaders) {
                        Object v = ri.getHeader.invoke(req, h);
                        if (v != null) {
                            rid = String.valueOf(v);
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        t.method = method == null ? "REQUEST" : method;
        t.uri = uri == null ? "" : UriMasker.mask(uri, Config.maskNumeric);
        t.rid = rid;
    }

    static int responseStatus(Object resp) {
        try {
            if (resp != null) {
                RequestInfo ri = RESP.get(resp.getClass());
                if (ri.getStatus != null)
                    return (Integer) ri.getStatus.invoke(resp);
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

}
