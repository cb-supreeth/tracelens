package com.chargebee.tracelens.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;

/**
 * Exempts /tracelens/** from authentication. Chargebee services secure every path via
 * cb-ms-security (JWT); a local debug API must be reachable without a token.
 *
 * Composes with the service's own WebSecurityCustomizer (Spring Boot applies all
 * customizer beans to the shared WebSecurity). Only loads when Spring Security is on
 * the classpath and tracelens is enabled — inert in prod by construction.
 *
 * Security note: acceptable because the whole starter is dev-only
 * (tracelens.enabled=true exists only in local/dev profiles). If tracelens is ever
 * enabled in a shared environment, protect or omit this exemption there.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tracelens", name = "enabled", havingValue = "true")
@ConditionalOnExpression("!${tracelens.standalone:false}")
@ConditionalOnClass(WebSecurityCustomizer.class)
public class TraceLensSecurityAutoConfiguration {

    @Bean
    WebSecurityCustomizer traceLensWebSecurityCustomizer() {
        return web -> web.ignoring().requestMatchers("/tracelens/**");
    }
}
