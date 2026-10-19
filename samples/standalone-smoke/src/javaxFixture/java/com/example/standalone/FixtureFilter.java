package com.example.standalone;

import com.example.authentication.AuthBoundary;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;

/** A normal application router/security filter, with no TraceLens API dependency. */
public final class FixtureFilter implements Filter {
    @Override public void init(FilterConfig config) {}
    @Override public void destroy() {}

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        if (req.getRequestURI().startsWith("/api/identity/")) {
            try {
                new AuthBoundary().validate(new AuthBoundary.Model(req));
            } catch (SecurityException denied) {
                res.setStatus(401);
                res.getWriter().write("AUTH_DENIED");
                return;
            }
        }
        if ("/api/filtered".equals(req.getRequestURI())) {
            res.setContentType("text/plain");
            res.getWriter().write("FILTER:" + res.getHeader("X-TraceLens-Id"));
            return;
        }
        if ("/api/principal".equals(req.getRequestURI())) {
            request = new HttpServletRequestWrapper(req) {
                @Override public Principal getUserPrincipal() {
                    return new Principal() {
                        @Override public String getName() { return "fixture-developer"; }
                    };
                }
            };
        }
        try {
            chain.doFilter(request, response);
        } catch (IllegalStateException failure) {
            if (!"/api/recovered".equals(req.getRequestURI())) throw failure;
            // Application error recovery controls the actual response status.
            res.getWriter().write("RECOVERED");
        }
    }
}
