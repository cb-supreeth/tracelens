package com.example.standalone;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/** A plain application servlet; neither its build nor WEB-INF/lib contains TraceLens. */
public final class FixtureServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain; charset=UTF-8");
        if (req.getRequestURI().startsWith("/tracelens")) {
            resp.getWriter().write("APPLICATION");
        } else if (req.getRequestURI().equals("/api/fail") || req.getRequestURI().equals("/api/recovered")) {
            throw new IllegalStateException("expected fixture failure");
        } else if (req.getRequestURI().equals("/api/async")) {
            final AsyncContext async = req.startAsync();
            async.setTimeout(5000);
            async.start(new Runnable() {
                @Override public void run() { async.dispatch("/api/redispatch"); }
            });
        } else if (req.getRequestURI().equals("/api/async-complete")
                || req.getRequestURI().equals("/api/identity/async-complete")) {
            final boolean identityProbe = req.getRequestURI().startsWith("/api/identity/");
            final AsyncContext async = req.startAsync();
            async.setTimeout(5000);
            async.start(new Runnable() {
                @Override public void run() {
                    try {
                        async.getResponse().getWriter().write("ASYNC:" + calculate()
                                + (identityProbe ? ":" + currentUser() : ""));
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    } finally {
                        async.complete();
                    }
                }
            });
        } else if (req.getRequestURI().equals("/probe")) {
            // Test-only reflection verifies ThreadLocal cleanup on the reused container
            // thread without putting any TraceLens class on this app's classpath.
            resp.getWriter().write("CONTEXT:" + currentContext());
        } else {
            resp.getWriter().write("GET:" + calculate() + ":" + resp.getHeader("X-TraceLens-Id"));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        int length = 0;
        java.io.BufferedReader reader = req.getReader();
        while (reader.read() != -1) length++;
        resp.setContentType("text/plain; charset=UTF-8");
        resp.getWriter().write("POST:" + length + ":" + resp.getHeader("X-TraceLens-Id"));
    }

    private int calculate() {
        int base = 7;
        return base + 5;
    }

    private String currentContext() {
        try {
            Class<?> context = Class.forName("com.chargebee.tracelens.bridge.TraceContext", true, null);
            Object segmentId = context.getMethod("currentSegmentId").invoke(null);
            return segmentId == null ? "none" : segmentId.toString();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private String currentUser() {
        try {
            Class<?> context = Class.forName("com.chargebee.tracelens.bridge.TraceContext", true, null);
            Object snapshot = context.getMethod("current").invoke(null);
            return snapshot == null ? "none" : (String) snapshot.getClass().getMethod("getUser").invoke(snapshot);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }
}
