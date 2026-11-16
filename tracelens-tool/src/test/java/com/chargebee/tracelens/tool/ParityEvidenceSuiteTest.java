package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import java.io.InputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden contracts recorded from the Python evidence regression suites.
 * No Python runtime, network, or saved user data is used by these tests.
 */
class ParityEvidenceSuiteTest {
    @TestFactory Collection<DynamicTest> pythonRegressionContracts() throws Exception {
        JsonNode fixtures;
        try (InputStream in = getClass().getResourceAsStream("/parity-evidence-python-suite.json")) {
            assertNotNull(in);
            fixtures = Json.MAPPER.readTree(in);
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode fixture : fixtures) {
            tests.add(DynamicTest.dynamicTest(fixture.path("name").asText(), () -> {
                JsonNode args = fixture.path("arguments"), expected = fixture.get("expected"), actual;
                JsonNode original = args.deepCopy();
                switch (fixture.path("operation").asText()) {
                    case "execution":
                        actual = Execution.build(args.get("trace"), args.path("max_nodes").asInt(),
                                args.path("max_model_bytes").asInt());
                        break;
                    case "visualization": actual = Visualization.build(args.get("journey")); break;
                    case "mermaid": actual = Json.node(Visualization.toMermaid(args.get("model"))); break;
                    case "dependencies":
                        JsonNode result = Dependencies.extractBoundaries(args.get("events"), args.get("aliases"));
                        actual = Json.arr(result.get("externalCalls"), result.get("externalCallsTruncated"));
                        break;
                    case "boundary": actual = Json.node(Dependencies.boundarySummary(args.get("event"))); break;
                    case "sanitize": actual = Json.node(Dependencies.sanitizeSummary(args.get("row"))); break;
                    case "errors": actual = ErrorGroups.build(args.get("doc"), args.get("filters"), args.path("now_ms").asDouble()); break;
                    case "join":
                        actual = Federation.joinTrace(args.path("trace_id").asText(),
                                (ArrayNode) args.get("segments"), (ArrayNode) args.get("errors"));
                        break;
                    default: throw new AssertionError("Unknown operation " + fixture.get("operation"));
                }
                sameJson(expected, actual, "$");
                assertEquals(original, args, "Evidence reconstruction must not mutate its inputs");
            }));
        }
        return tests;
    }

    private static void sameJson(JsonNode expected, JsonNode actual, String path) {
        assertNotNull(actual, path);
        if (expected.isNumber() && actual.isNumber()) {
            assertEquals(expected.doubleValue(), actual.doubleValue(), path);
        } else if (expected.isObject()) {
            assertTrue(actual.isObject(), path);
            Set<String> keys = new TreeSet<>(), other = new TreeSet<>();
            expected.fieldNames().forEachRemaining(keys::add);
            actual.fieldNames().forEachRemaining(other::add);
            assertEquals(keys, other, path + " keys");
            for (String key : keys) sameJson(expected.get(key), actual.get(key), path + "." + key);
        } else if (expected.isArray()) {
            assertTrue(actual.isArray(), path);
            assertEquals(expected.size(), actual.size(), path + " length");
            for (int i = 0; i < expected.size(); i++) sameJson(expected.get(i), actual.get(i), path + "[" + i + "]");
        } else assertEquals(expected, actual, path);
    }
}
