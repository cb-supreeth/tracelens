package com.chargebee.tracelens.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HttpIdentityConfigTest {
    private TraceLensConfig config(TraceLensConfig.HttpIdentityHook hook) {
        return new TraceLensConfig().setApplicationId("fixture").setPackages(List.of("example"))
                .setHttpIdentityHooks(List.of(hook));
    }

    private TraceLensConfig.HttpIdentityHook hook() {
        return new TraceLensConfig.HttpIdentityHook().setId("authenticated")
                .setClassName("example.Authentication").setMethodName("validate")
                .setParameterCount(1).setTargetArgument(0)
                .setRequestPath(List.of("req")).setUserPath(List.of("user", "email"))
                .setSitePath(List.of("site", "id"));
    }

    @Test void remainsOptIn() {
        assertThat(new TraceLensConfig().getHttpIdentityHooks()).isEmpty();
        assertThat(config(hook()).validate()).isEmpty();
    }

    @Test void roundTripsAllFields() throws Exception {
        var original = config(hook().setTrustedUserHeader("service-name")
                .setTrustedUserHeaderValues(List.of("ui-gateway")).setFallbackUser("apicall"));
        var mapper = new ObjectMapper();
        var loaded = mapper.readValue(mapper.writeValueAsBytes(original), TraceLensConfig.class);
        assertThat(loaded.validate()).isEmpty();
        var h = loaded.getHttpIdentityHooks().get(0);
        assertThat(h.getId()).isEqualTo("authenticated");
        assertThat(h.getClassName()).isEqualTo("example.Authentication");
        assertThat(h.getMethodName()).isEqualTo("validate");
        assertThat(h.getParameterCount()).isEqualTo(1);
        assertThat(h.getTargetArgument()).isZero();
        assertThat(h.getRequestPath()).containsExactly("req");
        assertThat(h.getUserPath()).containsExactly("user", "email");
        assertThat(h.getSitePath()).containsExactly("site", "id");
        assertThat(h.getTrustedUserHeader()).isEqualTo("service-name");
        assertThat(h.getTrustedUserHeaderValues()).containsExactly("ui-gateway");
        assertThat(h.getFallbackUser()).isEqualTo("apicall");
    }

    @Test void rejectsUnsafeOrAmbiguousConfiguration() {
        assertThat(config(hook().setTargetArgument(1)).validate()).isNotEmpty();
        assertThat(config(hook().setParameterCount(33)).validate()).isNotEmpty();
        assertThat(config(hook().setRequestPath(List.of("getClass"))).validate()).isNotEmpty();
        assertThat(config(hook().setSitePath(List.of())).validate()).isNotEmpty();
        assertThat(config(hook().setUserPath(List.of())).validate()).isNotEmpty();
        assertThat(config(hook().setClassName("example.*")).validate()).isNotEmpty();
        assertThat(config(hook().setFallbackUser("caller\nInjected")).validate()).isNotEmpty();
        assertThat(config(hook().setTrustedUserHeader("service-name")).validate()).isNotEmpty();
        assertThat(config(hook().setTrustedUserHeaderValues(List.of("ui-gateway"))).validate()).isNotEmpty();
        for (String header : List.of("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie")) {
            assertThat(config(hook().setTrustedUserHeader(header)
                    .setTrustedUserHeaderValues(List.of("secret"))).validate()).isNotEmpty();
        }
        assertThat(config(hook()).setHttpIdentityHooks(List.of(hook(), hook())).validate()).isNotEmpty();
        assertThat(config(hook()).setHttpIdentityHooks(Collections.nCopies(17, hook())).validate()).isNotEmpty();
    }

    @Test void permitsReceiverTargetAndFallbackOnlyIdentity() {
        assertThat(config(hook().setParameterCount(0).setTargetArgument(-1)).validate()).isEmpty();
        assertThat(config(hook().setUserPath(null).setFallbackUser("apicall")).validate()).isEmpty();
        assertThat(config(hook()).setHttpIdentityHooks(null).validate()).isEmpty();
    }
}
