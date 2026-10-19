package com.chargebee.legacyfixture;

import com.chargebee.tracelens.bridge.TraceContext;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Loaded only from WEB-INF/classes, representing a legacy application's servlet. */
public final class FixtureServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain; charset=UTF-8");
        if (req.getRequestURI().startsWith("/tracelens")) {
            resp.sendError(404);
        } else if (req.getRequestURI().equals("/api/fail")) {
            throw new IllegalStateException("expected fixture failure");
        } else if (req.getRequestURI().equals("/api/async")) {
            final AsyncContext async = req.startAsync();
            async.setTimeout(5000);
            async.start(new Runnable() {
                @Override
                public void run() {
                    async.dispatch("/api/redispatch");
                }
            });
        } else {
            resp.getWriter().write("GET:" + calculate() + ":" + context());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        int length = 0;
        while (req.getReader().read() != -1) length++;
        resp.setContentType("text/plain; charset=UTF-8");
        resp.getWriter().write("POST:" + length + ":" + context());
    }

    private int calculate() {
        int base = 7;
        return base + 5;
    }

    private String context() {
        return TraceContext.currentSegmentId() == null ? "none" : TraceContext.currentSegmentId();
    }
}
