package com.chargebee.tracelens.spring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** With tracelens.enabled absent (prod default), nothing is wired at all. */
@SpringBootTest(classes = TraceLensDisabledTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceLensDisabledTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ApplicationContext context;

    @SpringBootApplication
    static class TestApp {
    }

    @Test
    void noBeansWhenDisabled() {
        assertThat(context.getBeanNamesForType(TraceLensAutoConfiguration.class)).isEmpty();
        assertThat(context.getBeanNamesForType(com.chargebee.tracelens.core.store.TraceStore.class)).isEmpty();
    }

    @Test
    void debugApiAbsentWhenDisabled() {
        var response = rest.getForEntity("http://localhost:" + port + "/tracelens/summary", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
