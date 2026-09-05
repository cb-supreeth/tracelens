package com.chargebee.tracelens.spring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end starter behavior in a real servlet container: labelled request ->
 * trace registered -> debug API exposes and exports it. (No agent attached here —
 * line events need the agent; the trace exists regardless with markers/attributes.)
 */
@SpringBootTest(classes = TraceLensStarterTest.TestApp.class,
        properties = {
                "tracelens.enabled=true",
                "tracelens.application-id=starter-test",
                "tracelens.packages=com.chargebee.tracelens",
                "tracelens.evict-on-export=false",
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraceLensStarterTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @SpringBootApplication
    static class TestApp {

        @RestController
        static class HelloController {

            @GetMapping("/hello")
            Callable<Map<String, String>> hello() {
                return () -> Map.of("hello", "world");
            }
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void labelledRequestIsTracedAndExported() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("tracelens-id", "starter-test-flow");
        headers.add("tracelens-user", "test@chargebee.com");

        ResponseEntity<String> hello = rest.exchange(url("/hello"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(hello.getStatusCode().is2xxSuccessful()).isTrue();
        String traceId = hello.getHeaders().getFirst("X-TraceLens-Id");
        assertThat(traceId).isNotBlank();

        String traces = rest.getForObject(url("/tracelens/traces"), String.class);
        assertThat(traces).contains("starter-test-flow").contains(traceId);

        String exported = rest.getForObject(url("/tracelens/export/" + traceId), String.class);
        assertThat(exported)
                .contains("\"lensId\" : \"starter-test-flow\"")
                .contains("\"entryChannel\" : \"HTTP\"")
                .contains("\"path\" : \"/hello\"")
                .contains("\"httpStatus\" : \"200\"");
    }

    @Test
    void summaryReportsStoreAndAgentStatus() {
        String summary = rest.getForObject(url("/tracelens/summary"), String.class);
        assertThat(summary)
                .contains("\"recordingEnabled\" : true")
                .contains("\"agentActive\" : false") // no -javaagent in this test JVM
                .contains("\"sinkRegistered\" : true");
    }

    @Test
    void debugApiIsNotTracedItself() {
        rest.getForObject(url("/tracelens/summary"), String.class);
        String traces = rest.getForObject(url("/tracelens/traces"), String.class);
        assertThat(traces).doesNotContain("/tracelens/summary");
    }

    @Test
    void disableStopsRecording() {
        rest.postForEntity(url("/tracelens/disable"), null, String.class);
        try {
            rest.getForObject(url("/hello"), String.class);
            String traces = rest.getForObject(url("/tracelens/traces"), String.class);
            assertThat(traces).doesNotContain("auto:GET /hello");
        } finally {
            rest.postForEntity(url("/tracelens/enable"), null, String.class);
        }
    }

    @Test
    void resetClearsTraces() {
        rest.getForObject(url("/hello"), String.class);
        String before = rest.getForObject(url("/tracelens/traces"), String.class);
        assertThat(before).contains("auto:GET /hello");

        rest.postForEntity(url("/tracelens/reset"), null, String.class);

        String after = rest.getForObject(url("/tracelens/traces"), String.class);
        assertThat(after).contains("\"count\" : 0");
    }
}
