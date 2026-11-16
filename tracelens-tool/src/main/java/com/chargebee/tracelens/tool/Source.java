package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coordinate-based current-checkout reads. Never executes editor links. */
public final class Source {
    public static final Path DEFAULT_SOURCE_ROOT = Paths.get(System.getProperty("user.home"), "work", "cb-ems");
    static final int MAX_OPERATION_FILES = 64, MAX_OPERATION_BYTES = 8 * 1024 * 1024, MAX_OPERATION_MILLIS = 2000;
    private final Path root;
    interface Reader { List<String> read(Path path, int maxBytes, long deadline); }
    private final Reader reader;
    public Source(Path root) { this(root, null); }
    Source(Path root, Reader reader) { this.root = root; this.reader = reader == null ? this::lines : reader; }
    public static Path defaultRoot() {
        String env = System.getenv("TRACELENS_SOURCE_ROOT");
        return env == null ? DEFAULT_SOURCE_ROOT : Paths.get(env);
    }
    private Path resolve(String fqcn) {
        if (root == null || fqcn == null || fqcn.length() > 256
                || !fqcn.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) return null;
        try {
            Path realRoot = root.toRealPath();
            List<Path> bases = new ArrayList<>();
            bases.add(realRoot);
            try (DirectoryStream<Path> modules = Files.newDirectoryStream(realRoot)) {
                for (Path item : modules) if (!item.getFileName().toString().startsWith(".") && Files.isDirectory(item)) {
                    if (bases.size() >= 512) break;
                    bases.add(item);
                }
            }
            if (bases.size() > 1) Collections.sort(bases.subList(1, bases.size()));
            LinkedHashSet<Path> dirs = new LinkedHashSet<>();
            for (Path base : bases) for (String set : new String[]{
                    "src/main/java", "src/main/kotlin", "src/main/scala", "src/main/groovy", "src"}) {
                Path candidate = base.resolve(set);
                if (Files.isDirectory(candidate)) dirs.add(candidate);
            }
            dirs.add(realRoot);
            String[] parts = fqcn.split("\\$", 2)[0].split("\\.");
            for (int end = parts.length; end > 0; end--) for (Path dir : dirs) {
                Path candidate = dir;
                for (int i = 0; i < end; i++) candidate = candidate.resolve(parts[i] + (i == end - 1 ? ".java" : ""));
                if (!Files.isRegularFile(candidate)) continue;
                Path real = candidate.toRealPath();
                if (real.startsWith(realRoot) && !real.equals(realRoot)) return real;
            }
        } catch (IOException | SecurityException ignored) { }
        return null;
    }
    private List<String> lines(Path path) {
        return lines(path,4*1024*1024,System.nanoTime()+5_000_000_000L);
    }
    private List<String> lines(Path path,int maxBytes,long deadline) {
        if (path == null) return Collections.emptyList();
        try {
            if (!path.toRealPath().startsWith(root.toRealPath()) || Files.size(path) > maxBytes)
                return Collections.emptyList();
            byte[] bytes;
            try (java.io.InputStream input = Files.newInputStream(path)) {
                bytes = DebugClient.readBounded(input, maxBytes, deadline);
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.isEmpty()) return Collections.emptyList();
            List<String> result = new ArrayList<>(java.util.Arrays.asList(text.split("\\r\\n|\\n|\\r", -1)));
            if (result.get(result.size() - 1).isEmpty()) result.remove(result.size() - 1);
            return result;
        } catch (IOException | ToolException ignored) { return Collections.emptyList(); }
    }
    private final class Cache {
        final long deadline=System.nanoTime()+MAX_OPERATION_MILLIS*1_000_000L;
        final Map<String,List<String>> classes=new LinkedHashMap<>();
        final Map<Path,List<String>> files=new LinkedHashMap<>();
        int remaining=MAX_OPERATION_BYTES;boolean limited;
        String line(String name,int line) {
            if(line<1)return null;
            List<String> content=classes.get(name);
            if(content==null) {
                if(classes.size()>=256||System.nanoTime()>=deadline||Thread.currentThread().isInterrupted()){limited=true;return null;}
                Path path=resolve(name);content=files.get(path);
                if(content==null) {
                    content=Collections.emptyList();
                    if(path!=null)try {
                        long size=Files.size(path);
                        if(files.size()>=MAX_OPERATION_FILES||size>Math.min(4*1024*1024,remaining)||System.nanoTime()>=deadline)limited=true;
                        else {
                            int maximum=(int)Math.min(size+1,Math.min(4*1024*1024,remaining));remaining-=maximum;
                            content=reader.read(path,maximum,deadline);
                            if(System.nanoTime()>=deadline){limited=true;content=Collections.emptyList();}
                        }
                    }catch(IOException|SecurityException unavailable) { }
                    if(path!=null&&files.size()<MAX_OPERATION_FILES)files.put(path,content);
                }
                classes.put(name,content);
            }
            return line<=content.size()?content.get(line-1).trim():null;
        }
    }
    public String sourceLine(String fqcn, int line) {
        if (line < 1) return null;
        List<String> content = lines(resolve(fqcn));
        return line <= content.size() ? content.get(line - 1).trim() : null;
    }
    public ObjectNode location(String fqcn, int line) {
        if (line < 1 || line > 1_000_000) throw new IllegalArgumentException("Source line must be between 1 and 1000000");
        Path file = resolve(fqcn);
        if (file == null || line > lines(file).size()) return null;
        try {
            String encoded = new URI(null, null, file.toString(), null).toASCIIString();
            return Json.obj("className", fqcn, "line", line, "path", file.toString(),
                    "relativePath", root.toRealPath().relativize(file).toString(),
                    "sourceCorrespondence", "unverified",
                    "note", "Current local checkout; correspondence with the captured running build is not verified.",
                    "editors", Json.obj("vscode", "vscode://file" + encoded + ":" + line,
                            "cursor", "cursor://file" + encoded + ":" + line,
                            "idea", "idea://open?file=" + DebugClient.encode(file.toString()) + "&line=" + line));
        } catch (Exception exc) { return null; }
    }
    public ObjectNode enrich(JsonNode trace) {
        ObjectNode result = Json.copy(trace);
        ArrayNode events = Json.arr();Cache cache=new Cache();
        for (JsonNode event : Json.rows(trace, "events")) {
            ObjectNode item = Json.copy(event);
            if ("LINE".equals(Json.text(event, "type")) && event.path("line").isIntegralNumber()
                    && event.path("line").canConvertToInt()) {
                String text = cache.line(Json.text(event, "className"), event.path("line").asInt());
                if (text != null && !text.isEmpty()) item.put("sourceLine", text);
            }
            events.add(item);
        }
        result.set("events", events);
        if(cache.limited)sourceLimited(result);
        return result;
    }
    /** Pin checkout text only within this page's byte budget; saved reads never re-resolve it. */
    public ObjectNode enrichBounded(JsonNode trace, int maxBytes) {
        ObjectNode result=Json.copy(trace);ArrayNode events=Json.arr();result.set("events",events);
        int bytes=Captures.bytes(trace).length+512;boolean omitted=false;Cache cache=new Cache();
        for(JsonNode event:Json.rows(trace,"events")) {
            ObjectNode item=Json.copy(event);
            if("LINE".equals(Json.text(event,"type"))&&event.path("line").isIntegralNumber()&&event.path("line").canConvertToInt()) {
                String text=cache.line(Json.text(event,"className"),event.path("line").asInt());
                if(text!=null) {
                    if(!text.isEmpty()) {
                        if(text.length()>4096){omitted=true;}
                        else {
                            ObjectNode enriched=item.deepCopy();enriched.put("sourceLine",text);
                            int extra=Captures.bytes(enriched).length-Captures.bytes(item).length;
                            if(bytes+extra<=maxBytes){item=enriched;bytes+=extra;}else omitted=true;
                        }
                    }
                }
            }
            events.add(item);
        }
        result.put("sourceCorrespondence","unverified");
        if(omitted||cache.limited)sourceLimited(result);
        if(Captures.bytes(result).length>maxBytes)throw new ToolException(413,"Source-enriched snapshot page exceeds its byte budget.");
        return result;
    }
    private static void sourceLimited(ObjectNode result) {
        result.put("sourcePartial",true);ArrayNode warnings=result.path("warnings").isArray()?(ArrayNode)result.get("warnings"):result.putArray("warnings");
        warnings.add("Some checkout source text was omitted to preserve the source-read or page byte budget; recorded events are unchanged.");
    }
}
