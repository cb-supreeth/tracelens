package com.chargebee.tracelens.core.http;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HttpPayloadSanitizerTest {
    @Test void preservesExactDecimalAndLargeIntegerValues() {
        String body = "{\"amount\":1234567890.12345678901234567890,\"id\":9007199254740993}";
        assertThat(HttpPayloadSanitizer.sanitize(body, "json", false, 4096).get("httpRequestBody")).isEqualTo(body);
    }
    @Test void redactsNestedJsonAndKeepsUsefulValues() {
        Map<String, String> result = HttpPayloadSanitizer.sanitize(
                "{\"feature\":\"xero\",\"count\":2,\"nested\":[{\"access_token\":\"secret-a\",\"Password\":\"secret-b\"}],\"api-key\":\"secret-c\",\"card\":{\"number\":\"4111111111111111\",\"cvv\":\"123\"}}",
                "json", false, 4096);
        assertThat(result).containsEntry("httpRequestBodyStatus", "captured")
                .containsEntry("httpPayloadRedaction", "enabled").containsEntry("httpPayloadRedacted", "true");
        assertThat(result.get("httpRequestBody")).contains("xero", "\"count\":2", "[REDACTED]").doesNotContain("secret-", "4111111111111111", "123");
    }
    @Test void formQueryDecodingPreservesRepeatedParametersAndRedactsBracketedCredentials() {
        Map<String, String> result = HttpPayloadSanitizer.sanitize("name=first+item&name=second&api_key=secret&customer%5Bpassword%5D=secret2", "form", true, 4096);
        assertThat(result).containsEntry("httpQueryParametersStatus", "captured").containsEntry("httpPayloadRedacted", "true");
        assertThat(result.get("httpQueryParameters")).contains("\"name\":[\"first item\",\"second\"]").doesNotContain("secret");
    }
    @Test void explicitlyDisabledRedactionRetainsNestedJsonFieldsAndExactNumbers() {
        String body = "{\"username\":\"synthetic-user\",\"nested\":[{\"password\":\"fake-password\","
                + "\"api_key\":\"fake-key\",\"credentials\":{\"token\":\"fake-token\"}}],"
                + "\"count\":9007199254740993,\"amount\":1234567890.12345678901234567890}";
        Map<String, String> result = HttpPayloadSanitizer.sanitize(body, "json", false, 4096, false);
        assertThat(result).containsEntry("httpRequestBodyStatus", "captured")
                .containsEntry("httpPayloadRedaction", "disabled").containsEntry("httpRequestBody", body)
                .doesNotContainKey("httpPayloadRedacted");
    }
    @Test void explicitlyDisabledRedactionRetainsRepeatedQueryAndFormFields() {
        String input = "name=first+item&api_key=fake-key-one&api_key=fake-key-two&customer%5Bpassword%5D=fake%2Bpassword";
        String expected = "{\"name\":[\"first item\"],\"api_key\":[\"fake-key-one\",\"fake-key-two\"],"
                + "\"customer[password]\":[\"fake+password\"]}";
        for (boolean query : new boolean[]{true, false}) {
            String field = query ? "httpQueryParameters" : "httpRequestBody";
            assertThat(HttpPayloadSanitizer.sanitize(input, "form", query, 4096, false))
                    .containsEntry(field + "Status", "captured").containsEntry(field, expected)
                    .containsEntry("httpPayloadRedaction", "disabled").doesNotContainKey("httpPayloadRedacted");
        }
    }
    @Test void redactedFlagRequiresChangedRetainedFields() {
        for (String body : new String[]{"{\"name\":\"test\"}", "{\"password\":\"[REDACTED]\"}"}) {
            assertThat(HttpPayloadSanitizer.sanitize(body, "json", false, 4096))
                    .containsEntry("httpPayloadRedaction", "enabled").containsEntry("httpRequestBody", body)
                    .doesNotContainKey("httpPayloadRedacted");
        }
        assertThat(HttpPayloadSanitizer.sanitize("password=x", "form", false, 12))
                .containsEntry("httpPayloadRedaction", "enabled").containsEntry("httpRequestBodyStatus", "omitted-too-large")
                .doesNotContainKey("httpPayloadRedacted").doesNotContainKey("httpRequestBody");
    }
    @Test void disablingRedactionDoesNotBypassMalformedFormatDepthOrSizeLimits() {
        for (String value : new String[]{null, "{\"password\":\"fake\"", "{} {\"x\":1}", "\"fake\"", "{\"a\":1,\"a\":2}"}) {
            assertThat(HttpPayloadSanitizer.sanitize(value, "json", false, 4096, false))
                    .containsEntry("httpPayloadRedaction", "disabled").containsEntry("httpRequestBodyStatus", "omitted-invalid")
                    .doesNotContainKey("httpRequestBody").doesNotContainKey("httpPayloadRedacted");
        }
        for (String value : new String[]{"api_key=%QZfake", "name=%C3%28"}) {
            assertThat(HttpPayloadSanitizer.sanitize(value, "form", true, 4096, false))
                    .containsEntry("httpPayloadRedaction", "disabled").containsEntry("httpQueryParametersStatus", "omitted-invalid")
                    .doesNotContainKey("httpQueryParameters");
        }
        assertThat(HttpPayloadSanitizer.sanitize("fake-password", "text", false, 4096, false))
                .containsEntry("httpPayloadRedaction", "disabled").containsEntry("httpRequestBodyStatus", "omitted-invalid")
                .doesNotContainKey("httpRequestBody");
        assertThat(HttpPayloadSanitizer.sanitize("{\"password\":\"fake\"}", "json", false, 4, false))
                .containsEntry("httpPayloadRedaction", "disabled").containsEntry("httpRequestBodyStatus", "omitted-too-large")
                .doesNotContainKey("httpRequestBody");
        assertThat(HttpPayloadSanitizer.sanitize("x=a", "form", true, 4, false))
                .containsEntry("httpPayloadRedaction", "disabled").containsEntry("httpQueryParametersStatus", "omitted-too-large")
                .doesNotContainKey("httpQueryParameters");
        String deep = "{}";
        for (int i = 0; i < 40; i++) deep = "{\"child\":" + deep + "}";
        assertThat(HttpPayloadSanitizer.sanitize(deep, "json", false, 4096, false))
                .containsEntry("httpPayloadRedaction", "disabled").containsEntry("httpRequestBodyStatus", "omitted-invalid")
                .doesNotContainKey("httpRequestBody");
        assertThat(HttpPayloadSanitizer.sanitize(new String(new char[16385]), "json", false, Integer.MAX_VALUE, false))
                .containsEntry("httpPayloadRedaction", "disabled").containsEntry("httpRequestBodyStatus", "omitted-too-large")
                .doesNotContainKey("httpRequestBody");
    }
    @Test void malformedOversizedScalarAndTrailingJsonAreOmittedEntirely() {
        for (String value : new String[]{"{\"password\":\"secret\"", "{} {\"x\":1}", "\"secret\"", "{\"a\":1,\"a\":2}"}) {
            assertThat(HttpPayloadSanitizer.sanitize(value, "json", false, 4096))
                    .containsEntry("httpRequestBodyStatus", "omitted-invalid").doesNotContainKey("httpRequestBody");
        }
        assertThat(HttpPayloadSanitizer.sanitize("api_key=%QZsecret", "form", true, 4096))
                .containsEntry("httpQueryParametersStatus", "omitted-invalid").doesNotContainKey("httpQueryParameters");
        assertThat(HttpPayloadSanitizer.sanitize("name=%C3%28", "form", true, 4096))
                .containsEntry("httpQueryParametersStatus", "omitted-invalid").doesNotContainKey("httpQueryParameters");
        assertThat(HttpPayloadSanitizer.sanitize("{\"long\":\"value\"}", "json", false, 4))
                .containsEntry("httpRequestBodyStatus", "omitted-too-large").doesNotContainKey("httpRequestBody");
        assertThat(HttpPayloadSanitizer.sanitize("x=a", "form", true, 4))
                .containsEntry("httpQueryParametersStatus", "omitted-too-large").doesNotContainKey("httpQueryParameters");
    }
    @Test void deepJsonAndHardLimitCannotBypassBounds() {
        String input = "{}";
        for (int i = 0; i < 40; i++) input = "{\"child\":" + input + "}";
        assertThat(HttpPayloadSanitizer.sanitize(input, "json", false, 4096)).containsEntry("httpRequestBodyStatus", "omitted-invalid");
        assertThat(HttpPayloadSanitizer.sanitize(new String(new char[16385]), "json", false, Integer.MAX_VALUE))
                .containsEntry("httpRequestBodyStatus", "omitted-too-large");
    }
}
