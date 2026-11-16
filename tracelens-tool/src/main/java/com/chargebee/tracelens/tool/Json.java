package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Shared strict JSON and bounded-model helpers. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(128).maxStringLength(8 * 1024 * 1024)
                    .maxNumberLength(128).build()).build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private Json() {}

    public static ObjectNode obj(Object... values) {
        if (values.length % 2 != 0) throw new IllegalArgumentException("JSON object needs key/value pairs");
        ObjectNode result = MAPPER.createObjectNode();
        for (int i = 0; i < values.length; i += 2) result.set((String) values[i], node(values[i + 1]));
        return result;
    }
    public static ArrayNode arr(Object... values) {
        ArrayNode result = MAPPER.createArrayNode();
        for (Object value : values) result.add(node(value));
        return result;
    }
    public static JsonNode node(Object value) {
        return value instanceof JsonNode ? (JsonNode) value : MAPPER.valueToTree(value);
    }
    public static JsonNode parse(String raw) {
        try {
            JsonNode value = MAPPER.readTree(raw);
            if (value == null) throw new IllegalArgumentException("Empty JSON");
            finite(value);
            return value;
        } catch (IOException | StackOverflowError exc) {
            throw new IllegalArgumentException("Invalid JSON");
        }
    }
    public static JsonNode parse(byte[] raw) {
        try {
            return parse(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString());
        } catch (CharacterCodingException exc) {
            throw new IllegalArgumentException("Invalid UTF-8 JSON");
        }
    }
    private static void finite(JsonNode value) {
        if (value.isFloatingPointNumber() && !Double.isFinite(value.doubleValue()))
            throw new IllegalArgumentException("Non-finite JSON number");
        if (value.isContainerNode()) for (JsonNode item : value) finite(item);
    }
    public static String stringify(JsonNode value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (IOException exc) { throw new IllegalArgumentException("Cannot encode JSON"); }
    }
    public static String text(JsonNode value, String key) {
        JsonNode child = value == null ? null : value.get(key);
        return child != null && child.isTextual() ? child.textValue() : null;
    }
    public static String text(JsonNode value, String key, String fallback) {
        String result = text(value, key);
        return result == null ? fallback : result;
    }
    public static List<JsonNode> rows(JsonNode value, String key) {
        JsonNode child = value == null ? null : value.get(key);
        if (child == null || !child.isArray()) return Collections.emptyList();
        List<JsonNode> result = new ArrayList<>();
        child.forEach(result::add);
        return result;
    }
    public static boolean bool(JsonNode value, String key) {
        return value != null && value.path(key).isBoolean() && value.path(key).booleanValue();
    }
    public static double num(JsonNode value, String key, double fallback) {
        JsonNode child = value == null ? null : value.get(key);
        return child != null && child.isNumber() && Double.isFinite(child.doubleValue())
                ? child.doubleValue() : fallback;
    }
    public static ObjectNode copy(JsonNode value) {
        return value != null && value.isObject() ? (ObjectNode) value.deepCopy() : obj();
    }
    public static String canonical(JsonNode value) {
        if (value.isObject()) {
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            StringBuilder out = new StringBuilder("{");
            for (String name : names) {
                if (out.length() > 1) out.append(',');
                out.append(ascii(stringify(node(name)))).append(':').append(canonical(value.get(name)));
            }
            return out.append('}').toString();
        }
        if (value.isArray()) {
            StringBuilder out = new StringBuilder("[");
            for (JsonNode item : value) {
                if (out.length() > 1) out.append(',');
                out.append(canonical(item));
            }
            return out.append(']').toString();
        }
        return ascii(stringify(value));
    }
    private static String ascii(String input) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch > 127) out.append(String.format("\\u%04x", (int) ch));
            else out.append(ch);
        }
        return out.toString();
    }
    public static String sha256(String input) {
        try {
            StringBuilder out = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)))
                out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (Exception exc) { throw new IllegalStateException("SHA-256 unavailable", exc); }
    }
}
