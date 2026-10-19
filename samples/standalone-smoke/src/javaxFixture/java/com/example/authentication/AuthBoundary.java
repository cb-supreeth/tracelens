package com.example.authentication;

import javax.servlet.http.HttpServletRequest;

/** Application authentication code, deliberately outside the configured line-capture scope. */
public final class AuthBoundary {
    public void validate(Model model) {
        // Some authentication frameworks populate the site before rejecting a token.
        // A failed boundary must not publish this provisional identity to a trace.
        model.site = new Site(42);
        if (model.req().getRequestURI().endsWith("/denied")) {
            throw new SecurityException("expected authentication rejection");
        }
        model.validated = true;
    }

    public static final class Model {
        private final HttpServletRequest request;
        private Site site;
        private boolean validated;

        public Model(HttpServletRequest request) { this.request = request; }
        public HttpServletRequest req() { return request; }
        public Site site() { return site; }
        public User getUser() {
            if (!validated) throw new IllegalStateException("identity read before authentication completed");
            String claimedUser = request.getHeader("X-Fixture-User");
            if ("none".equals(claimedUser)) return null;
            return new User(claimedUser == null ? "alice" : claimedUser);
        }
    }

    public static final class User {
        private final String email;
        public User(String email) { this.email = email; }
        public String email() { return email; }
    }

    public static final class Site {
        private final long id;
        public Site(long id) { this.id = id; }
        public long id() { return id; }
    }
}
