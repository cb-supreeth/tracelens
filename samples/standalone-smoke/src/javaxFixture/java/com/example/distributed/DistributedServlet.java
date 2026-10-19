package com.example.distributed;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;

/** Ordinary application HTTP client/server code with no TraceLens dependency. */
public final class DistributedServlet extends HttpServlet {
    @Override protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException { handle(req, res); }
    @Override protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException { handle(req, res); }

    private void handle(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setContentType("text/plain; charset=UTF-8");
        String path = req.getRequestURI();
        if (path.equals("/probe")) {
            res.getWriter().write(context());
        } else if (path.startsWith("/api/gateway/")) {
            forward(req, res, path.substring("/api/gateway/".length()));
        } else if (path.equals("/api/receiver/redirect")) {
            res.sendRedirect(System.getProperty("fixture.redirectTarget"));
        } else {
            if (path.endsWith("/slow")) {
                try { Thread.sleep(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            if (path.endsWith("/fail")) res.setStatus(500);
            byte[] payload = read(req.getInputStream());
            res.getWriter().write("method=" + req.getMethod() + "\nbodyLength=" + payload.length
                    + "\ntraceparent=" + value(req.getHeader("traceparent"))
                    + "\ntracestate=" + value(req.getHeader("tracestate"))
                    + "\nuser=" + value(req.getHeader("tracelens-user"))
                    + "\naction=" + value(req.getHeader("tracelens-action-id"))
                    + "\nsession=" + value(req.getHeader("tracelens-session-id"))
                    + "\nlabel=" + value(req.getHeader("tracelens-id")));
        }
    }

    private void forward(HttpServletRequest req, HttpServletResponse res, String operation) throws IOException {
        String destination = operation.equals("disallowed") ? System.getProperty("fixture.redirectTarget")
                : System.getProperty("fixture.downstream") + "/api/receiver/" + operation;
        // Query values belong to the application, but must not appear in exported call metadata.
        HttpURLConnection conn = (HttpURLConnection) new URL(destination + "?secret=outbound-query-secret").openConnection();
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(operation.equals("slow") ? 100 : 5000);
        conn.setRequestMethod(req.getMethod());
        if (req.getHeader("X-Fixture-Existing") != null) {
            conn.setRequestProperty("traceparent", req.getHeader("X-Fixture-Existing"));
            conn.setRequestProperty("tracestate", "fixture=preserved");
        }
        try {
            if (req.getMethod().equals("POST")) {
                byte[] bytes = read(req.getInputStream());
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = conn.getOutputStream()) { output.write(bytes); }
            }
            int status = conn.getResponseCode();
            res.setStatus(status);
            String downstreamSegment = conn.getHeaderField("X-TraceLens-Id");
            if (downstreamSegment != null) res.setHeader("X-Fixture-Downstream-Segment", downstreamSegment);
            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] bytes = stream == null ? new byte[0] : read(stream);
            res.getOutputStream().write(bytes);
        } catch (SocketTimeoutException timeout) {
            res.setStatus(504);
            res.getWriter().write("TIMEOUT");
        } finally {
            conn.disconnect();
        }
    }

    private static String value(String text) { return text == null ? "none" : text; }

    private static byte[] read(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = in.read(buffer)) != -1) bytes.write(buffer, 0, length);
            return bytes.toByteArray();
        }
    }

    private static String context() {
        try {
            Class<?> bridge = Class.forName("com.chargebee.tracelens.bridge.TraceContext", true, null);
            Object traceId = bridge.getMethod("currentTraceId").invoke(null);
            return traceId == null ? "none" : traceId.toString();
        } catch (ClassNotFoundException noAgent) {
            return "none";
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
