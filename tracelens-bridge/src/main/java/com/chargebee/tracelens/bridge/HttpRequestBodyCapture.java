package com.chargebee.tracelens.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;

/** Bootstrap-safe passive observer for Tomcat InputBuffer reads (javax and jakarta). */
public final class HttpRequestBodyCapture {
    private static final Map<Object, HttpRequestBodyCapture> BUFFERS =
            Collections.synchronizedMap(new WeakHashMap<Object, HttpRequestBodyCapture>());
    private static final ThreadLocal<Read> ACTIVE = new ThreadLocal<>();
    private final HttpTraceRuntime.Listener owner;
    private final String segmentId;
    private final int limit;
    private final long length;
    private final String format;
    private Charset charset;
    private ErasableBytes bytes = new ErasableBytes();
    private StringBuilder chars = new StringBuilder();
    private WeakReference<Object> buffer;
    private String status;
    private boolean eof, closed, byteMode, charMode, tomcatContainer;

    HttpRequestBodyCapture(HttpTraceRuntime.Listener owner, String contentType, String encoding,
                           String contentEncoding, long length, boolean tomcatContainer) {
        this(owner, contentType, encoding, contentEncoding, length, tomcatContainer, null);
    }

    HttpRequestBodyCapture(HttpTraceRuntime.Listener owner, String contentType, String encoding,
                           String contentEncoding, long length, boolean tomcatContainer, String segmentId) {
        this.owner = owner;
        this.segmentId = segmentId;
        this.tomcatContainer = tomcatContainer;
        this.limit = Math.max(1, Math.min(16384, owner.maxHttpPayloadLength()));
        this.length = length;
        String media = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        format = "application/json".equals(media) || media.endsWith("+json") ? "json"
                : "application/x-www-form-urlencoded".equals(media) ? "form" : null;
        if (format == null) status = "unsupported-content-type";
        if (contentEncoding != null && !contentEncoding.isEmpty() && !"identity".equalsIgnoreCase(contentEncoding))
            status = "unsupported-encoding";
        Charset decoded;
        try {
            decoded = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            // Do not guess for legacy, compressed or binary encodings.
            if (!decoded.equals(StandardCharsets.UTF_8) && !decoded.equals(StandardCharsets.ISO_8859_1)
                    && !decoded.equals(StandardCharsets.US_ASCII)) status = "unsupported-encoding";
        } catch (RuntimeException unsupported) { decoded = StandardCharsets.UTF_8; status = "unsupported-encoding"; }
        charset = decoded;
        if ("form".equals(format) && !charset.equals(StandardCharsets.UTF_8)) status = "unsupported-encoding";
        if (length > limit) status = "omitted-too-large";
    }

    synchronized void bind(Object value, String encoding) {
        if (closed || status != null || value == null) return;
        tomcatContainer = true;
        if (buffer == null && encoding != null) {
            try {
                Charset applicationCharset = Charset.forName(encoding);
                if (!applicationCharset.equals(StandardCharsets.UTF_8) && !applicationCharset.equals(StandardCharsets.ISO_8859_1)
                        && !applicationCharset.equals(StandardCharsets.US_ASCII)) { invalidate("unsupported-encoding"); return; }
                if ("form".equals(format) && !applicationCharset.equals(StandardCharsets.UTF_8)) { invalidate("unsupported-encoding"); return; }
                charset = applicationCharset;
            } catch (RuntimeException unsupported) { invalidate("unsupported-encoding"); return; }
        }
        if (buffer != null && buffer.get() != value) { invalidate("incomplete"); return; }
        synchronized (BUFFERS) {
            if (!BUFFERS.containsKey(value) && BUFFERS.size() >= 1024) { invalidate("omitted-capacity"); return; }
            buffer = new WeakReference<>(value);
            BUFFERS.put(value, this);
        }
    }

    public static Object beginRead(Object buffer) {
        HttpRequestBodyCapture capture = BUFFERS.get(buffer);
        if (capture == null) return null;
        Read previous = ACTIVE.get();
        Read read = new Read(capture, previous, previous == null || previous.capture != capture);
        ACTIVE.set(read);
        return read;
    }

    public static void endBytes(Object token, byte[] value, int offset, int count, Throwable failure) {
        Read read = closeRead(token);
        if (read != null && read.outer) read.capture.appendBytes(value, offset, count, failure);
    }
    public static void endChars(Object token, char[] value, int offset, int count, Throwable failure) {
        Read read = closeRead(token);
        if (read != null && read.outer) read.capture.appendChars(value, offset, count, failure);
    }
    public static void endSingle(Object token, int value, boolean character, Throwable failure) {
        if (character) endChars(token, value < 0 ? null : new char[]{(char) value}, 0, value < 0 ? -1 : 1, failure);
        else endBytes(token, value < 0 ? null : new byte[]{(byte) value}, 0, value < 0 ? -1 : 1, failure);
    }
    public static void endBuffer(Object token, ByteBuffer value, int position, int count, Throwable failure) {
        Read read = closeRead(token);
        if (read == null || !read.outer) return;
        if (count <= 0 || failure != null) { read.capture.appendBytes(null, 0, count, failure); return; }
        if (count > read.capture.limit) { read.capture.invalidate("omitted-too-large"); return; }
        try {
            ByteBuffer copy = value.duplicate();
            copy.position(position); copy.limit(position + count);
            byte[] bytes = new byte[count]; copy.get(bytes);
            read.capture.appendBytes(bytes, 0, count, null);
        } catch (RuntimeException unsupported) { read.capture.invalidate("incomplete"); }
    }
    /** A skipped/reset body cannot truthfully be represented as one consumed payload. */
    public static void invalidateBuffer(Object buffer) {
        HttpRequestBodyCapture capture = BUFFERS.get(buffer);
        if (capture != null) capture.invalidate("incomplete");
    }
    private static Read closeRead(Object token) {
        if (!(token instanceof Read)) return null;
        Read read = (Read) token;
        if (read.previous == null) ACTIVE.remove(); else ACTIVE.set(read.previous);
        return read;
    }

    private synchronized void appendBytes(byte[] value, int offset, int count, Throwable failure) {
        if (closed || status != null) return;
        if (segmentId != null && !owner.captureHttpDetails(segmentId)) { invalidate("omitted-not-retained"); return; }
        if (failure != null) { invalidate("incomplete"); return; }
        if (count < 0) { eof = true; return; }
        if (count == 0) return;
        if (charMode) { invalidate("incomplete"); return; }
        if (count > limit - bytes.size()) { invalidate("omitted-too-large"); return; }
        byteMode = true;
        bytes.write(value, offset, count);
    }
    private synchronized void appendChars(char[] value, int offset, int count, Throwable failure) {
        if (closed || status != null) return;
        if (segmentId != null && !owner.captureHttpDetails(segmentId)) { invalidate("omitted-not-retained"); return; }
        if (failure != null) { invalidate("incomplete"); return; }
        if (count < 0) { eof = true; return; }
        if (count == 0) return;
        if (byteMode) { invalidate("incomplete"); return; }
        if (count > limit - chars.length()) { invalidate("omitted-too-large"); return; }
        charMode = true;
        chars.append(value, offset, count);
    }
    private synchronized void invalidate(String reason) {
        if (closed) return;
        status = reason;
        eraseBuffers();
    }

    synchronized Map<String, String> finish() {
        closed = true;
        if (buffer != null) { Object value = buffer.get(); if (value != null) BUFFERS.remove(value); buffer = null; }
        Map<String, String> attributes = new LinkedHashMap<>();
        if (format != null) attributes.put("httpRequestBodyFormat", format);
        try {
            if (status != null) attributes.put("httpRequestBodyStatus", status);
            else if (!byteMode && !charMode) attributes.put("httpRequestBodyStatus", tomcatContainer ? "not-consumed" : "unsupported-container");
            else {
                String input = charMode ? chars.toString() : decode(bytes.toByteArray());
                int actualLength = charMode ? input.getBytes(charset).length : bytes.size();
                if (!eof && (length < 0 || actualLength != length)) attributes.put("httpRequestBodyStatus", "incomplete");
                else attributes.putAll(owner.sanitizeHttpPayload(input, format, false));
            }
        } catch (Throwable invalid) { attributes.put("httpRequestBodyStatus", "omitted-invalid"); }
        finally { eraseBuffers(); bytes = null; chars = null; }
        return attributes;
    }
    private void eraseBuffers() {
        if (bytes != null) bytes.erase();
        if (chars != null) { for (int i = 0; i < chars.length(); i++) chars.setCharAt(i, '\0'); chars.setLength(0); }
    }
    private static final class ErasableBytes extends ByteArrayOutputStream {
        void erase() { java.util.Arrays.fill(buf, (byte) 0); reset(); }
    }
    private String decode(byte[] bytes) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static final class Read {
        final HttpRequestBodyCapture capture;
        final Read previous;
        final boolean outer;
        Read(HttpRequestBodyCapture capture, Read previous, boolean outer) {
            this.capture = capture; this.previous = previous; this.outer = outer;
        }
    }
}
