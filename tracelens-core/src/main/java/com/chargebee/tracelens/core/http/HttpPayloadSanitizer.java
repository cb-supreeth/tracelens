package com.chargebee.tracelens.core.http;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded opt-in HTTP payload projection. Never returns raw malformed input. */
public final class HttpPayloadSanitizer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REDACTED = "[REDACTED]";
    static {
        JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(32).maxStringLength(16384).maxNumberLength(128).build());
        JSON.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JSON.enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        JSON.setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
        JSON.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
    private HttpPayloadSanitizer() { }

    public static Map<String, String> sanitize(String input, String format, boolean query, int configuredLimit) {
        return sanitize(input, format, query, configuredLimit, true);
    }

    public static Map<String, String> sanitize(String input, String format, boolean query, int configuredLimit,
                                              boolean redactSensitiveFields) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("httpPayloadRedaction", redactSensitiveFields ? "enabled" : "disabled");
        String field = query ? "httpQueryParameters" : "httpRequestBody";
        int limit = Math.max(1, Math.min(16384, configuredLimit));
        if (!query) result.put("httpRequestBodyFormat", format);
        if (input == null || input.length() > limit) {
            result.put(field + "Status", input == null ? "omitted-invalid" : "omitted-too-large");
            return result;
        }
        try {
            JsonNode value;
            if ("json".equals(format)) {
                value = JSON.readTree(input);
                if (value == null || (!value.isObject() && !value.isArray())) throw new IllegalArgumentException();
            } else if ("form".equals(format)) {
                ObjectNode object = JSON.createObjectNode();
                if (!input.isEmpty()) for (String pair : input.split("&", -1)) {
                    int equals = pair.indexOf('=');
                    String key = decodeForm(equals < 0 ? pair : pair.substring(0, equals));
                    String content = decodeForm(equals < 0 ? "" : pair.substring(equals + 1));
                    JsonNode existing = object.get(key);
                    ArrayNode values = existing == null ? object.putArray(key) : (ArrayNode) existing;
                    values.add(content);
                }
                value = object;
            } else throw new IllegalArgumentException();
            boolean redacted = redactSensitiveFields && redact(value);
            String encoded = JSON.writeValueAsString(value);
            if (encoded.length() > limit) {
                result.put(field + "Status", "omitted-too-large");
            } else {
                result.put(field, encoded);
                result.put(field + "Status", "captured");
                if (redacted) result.put("httpPayloadRedacted", "true");
            }
        } catch (Exception invalid) {
            result.put(field + "Status", "omitted-invalid");
        }
        return result;
    }

    private static String decodeForm(String value) throws Exception {
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < value.length();) {
            char ch = value.charAt(i++);
            if (ch == '+') decoded.append(' ');
            else if (ch == '%') {
                ByteArrayOutputStream escaped = new ByteArrayOutputStream();
                do {
                    if (i + 1 >= value.length()) throw new IllegalArgumentException();
                    int high = Character.digit(value.charAt(i++), 16), low = Character.digit(value.charAt(i++), 16);
                    if (high < 0 || low < 0) throw new IllegalArgumentException();
                    escaped.write((high << 4) | low);
                    if (i >= value.length() || value.charAt(i) != '%') break;
                    i++;
                } while (true);
                decoded.append(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(escaped.toByteArray())));
            } else decoded.append(ch);
        }
        return decoded.toString();
    }

    private static boolean redact(JsonNode value) {
        boolean changed = false;
        if (value.isObject()) {
            ObjectNode object = (ObjectNode) value;
            Iterator<Map.Entry<String, JsonNode>> entries = object.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                if (sensitive(entry.getKey())) {
                    if (!entry.getValue().isTextual() || !REDACTED.equals(entry.getValue().textValue())) {
                        object.put(entry.getKey(), REDACTED);
                        changed = true;
                    }
                }
                else changed |= redact(entry.getValue());
            }
        } else if (value.isArray()) {
            for (JsonNode child : value) changed |= redact(child);
        }
        return changed;
    }

    private static boolean sensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("password") || normalized.contains("passwd") || normalized.contains("secret")
                || normalized.contains("token") || normalized.contains("authorization") || normalized.contains("credential")
                || normalized.contains("apikey") || normalized.contains("accesskey") || normalized.contains("privatekey")
                || normalized.contains("cookie") || normalized.contains("sessionid") || normalized.contains("signature")
                || normalized.equals("auth") || normalized.equals("pwd") || normalized.equals("cvv")
                || normalized.equals("cvc") || normalized.equals("card") || normalized.contains("cardnumber");
    }
}
