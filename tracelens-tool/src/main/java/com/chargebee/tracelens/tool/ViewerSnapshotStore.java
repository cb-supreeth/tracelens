package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static com.chargebee.tracelens.tool.Json.*;

/** Full explicit viewer pins, distinct from payload-free CLI captures. */
public final class ViewerSnapshotStore extends CaptureStore {
    public static final int MAX_BYTES=5*1024*1024,MAX_COUNT=20,MAX_TOTAL_BYTES=20*1024*1024;
    private static final Pattern CONTROLS=Pattern.compile("[\\x00-\\x1f\\x7f-\\x9f\\u202a-\\u202e\\u2066-\\u2069]");
    final ChunkedSnapshotStore chunked;
    public ViewerSnapshotStore(Path root,String namespace){super(namespaceRoot(root,namespace));chunked=new ChunkedSnapshotStore(this.root);}
    static Path namespaceRoot(Path root,String namespace){if(namespace==null||!namespace.matches("[0-9a-f]{64}"))throw Captures.invalid("Invalid viewer namespace.");return root.resolve(namespace);}
    public static Path defaultDirectory(){return Paths.get(System.getProperty("user.home"),".tracelens","viewer-snapshots");}
    public static ObjectNode limits(){return obj("maxCount",MAX_COUNT,"maxBytes",MAX_BYTES,"maxTotalBytes",MAX_TOTAL_BYTES);}
    @Override protected String idField(){return "id";}
    @Override protected void validate(JsonNode doc){validateSnapshot(doc);}
    static String cleanName(JsonNode value){
        if(value==null||!value.isTextual())throw Captures.invalid("Enter a name for the snapshot.");
        String result=Captures.strip(CONTROLS.matcher(value.textValue()).replaceAll(""));if(result.isEmpty()||result.codePointCount(0,result.length())>100)throw Captures.invalid("Snapshot names must contain 1–100 characters.");return result;
    }
    static String checkedText(JsonNode value,int limit,boolean empty){if(value==null||!value.isTextual())throw Captures.invalid("Invalid snapshot identity or text field.");String s=value.textValue();if(s.codePointCount(0,s.length())>limit||!empty&&Captures.strip(s).isEmpty()||CONTROLS.matcher(s).find())throw Captures.invalid("Invalid snapshot identity or text field.");return s;}
    static JsonNode rows(JsonNode values,int maximum,String label){if(values==null||!values.isArray()||values.size()>maximum)throw Captures.invalid("Invalid snapshot "+label+".");for(JsonNode row:values)if(!row.isObject())throw Captures.invalid("Invalid snapshot "+label+".");return values;}
    static String identity(JsonNode row){
        String service=checkedText(row.hasNonNull("service")&&!row.path("service").asText().isEmpty()?row.get("service"):row.get("applicationId"),256,false);
        String instance=checkedText(row.hasNonNull("serviceInstanceId")?row.get("serviceInstanceId"):node(""),256,true),segment=checkedText(row.get("segmentId"),256,false);
        ArrayNode identity=arr(service,instance,segment);
        if(row.hasNonNull("segmentKey")){String key=checkedText(row.get("segmentKey"),4096,false);try{if(!Json.parse(key).equals(identity))throw Captures.invalid("Conflicting snapshot segment identity.");}catch(RuntimeException e){throw Captures.invalid("Invalid snapshot segment identity.");}}
        return Captures.canonical(identity);
    }
    public static void validateSnapshot(JsonNode d){
        if(d==null||!d.isObject()||Captures.integer(d.get("schemaVersion"),1,1)==null||!"viewer".equals(Captures.text(d,"storage")))throw Captures.invalid("Invalid or unsupported snapshot format.");
        if(Captures.match(d.get("id"),Captures.UUID_RE)==null)throw Captures.invalid("Invalid snapshot ID.");if(!cleanName(d.get("name")).equals(Captures.text(d,"name")))throw Captures.invalid("Invalid snapshot name.");
        for(String f:Arrays.asList("capturedAt","savedAt")){JsonNode v=d.get(f);if(v==null||!v.isNumber()||!Double.isFinite(v.doubleValue())||v.doubleValue()<0||v.doubleValue()>9007199254740991d)throw Captures.invalid("Invalid snapshot timestamp.");}
        JsonNode trace=d.get("traceId");if(trace==null)trace=node(null);if(!trace.isNull())checkedText(trace,256,false);if(!d.path("partial").isBoolean())throw Captures.invalid("Invalid snapshot partial flag.");
        JsonNode warnings=d.get("warnings");if(warnings==null||!warnings.isArray()||warnings.size()>1024)throw Captures.invalid("Invalid snapshot warnings.");for(JsonNode w:warnings)if(!w.isTextual()||w.textValue().codePointCount(0,w.textValue().length())>16384)throw Captures.invalid("Invalid snapshot warning.");
        JsonNode request=d.path("request"),journey=d.path("journey");if(!request.isObject()||!trace.equals(request.has("traceId")?request.get("traceId"):node(null)))throw Captures.invalid("Snapshot request does not match its trace.");
        JsonNode services=request.has("services")?request.get("services"):arr();if(!services.isArray()||services.size()>1024)throw Captures.invalid("Invalid snapshot request services.");for(JsonNode service:services)if(!service.isTextual())throw Captures.invalid("Invalid snapshot request services.");
        if(!journey.isObject()||!trace.isNull()&&!trace.equals(journey.path("traceId")))throw Captures.invalid("Snapshot journey does not match its trace.");
        Set<String>identities=new HashSet<>();for(JsonNode row:rows(journey.get("segments"),1024,"journey segments")){if(!identities.add(identity(row)))throw Captures.invalid("Duplicate snapshot segment identity.");if(!trace.isNull()&&row.has("traceId")&&!trace.equals(row.get("traceId")))throw Captures.invalid("Conflicting snapshot trace identity.");}
        JsonNode exports=rows(d.get("segments"),64,"segment exports");if(exports.size()==0)throw Captures.invalid("Snapshot needs at least one saved segment export.");
        Set<String>exported=new HashSet<>();for(JsonNode row:exports){String key=identity(row);if(!identities.contains(key)||!exported.add(key))throw Captures.invalid("Saved segment is duplicated or absent from the journey.");if(!trace.isNull()&&row.has("traceId")&&!trace.equals(row.get("traceId")))throw Captures.invalid("Conflicting snapshot trace identity.");rows(row.get("events"),500000,"events");}
        for(String f:Arrays.asList("links","httpCalls","externalCalls","errors"))rows(journey.has(f)?journey.get(f):arr(),8192,f);
        if(journey.has("visualization")){JsonNode graph=journey.get("visualization");if(!graph.isObject()||Captures.integer(graph.get("schemaVersion"),1,1)==null||!"request-flow".equals(Captures.text(graph,"kind"))||!trace.isNull()&&!trace.equals(graph.path("traceId")))throw Captures.invalid("Invalid saved request-flow graph.");
            Set<String>ids=new HashSet<>();for(JsonNode n:rows(graph.get("nodes"),2048,"graph nodes")){String id=checkedText(n.get("id"),4096,false),kind=Captures.text(n,"kind");if(!ids.add(id)||!Arrays.asList("segment","http-call","dependency-call").contains(kind))throw Captures.invalid("Invalid or duplicate saved graph node.");if("segment".equals(kind)&&!identities.contains(identity(n)))throw Captures.invalid("Saved graph segment is absent from the journey.");}
            for(JsonNode e:rows(graph.get("edges"),8192,"graph edges"))if(!ids.contains(Captures.text(e,"from"))||!ids.contains(Captures.text(e,"to")))throw Captures.invalid("Saved graph edge has a missing endpoint.");
            JsonNode roots=graph.has("roots")?graph.get("roots"):arr();if(!roots.isArray())throw Captures.invalid("Invalid saved graph roots.");for(JsonNode r:roots)if(!r.isTextual()||!ids.contains(r.textValue()))throw Captures.invalid("Invalid saved graph roots.");
        }
        Captures.bytes(d); // Reject non-finite values even in full-export extension fields.
    }
    private ObjectNode summary(JsonNode d,int size){
        ObjectNode r=Captures.select(d,"id","name","traceId","capturedAt","savedAt","partial","warnings","storage","schemaVersion");ObjectNode request=obj();d.path("request").fields().forEachRemaining(e->{if(e.getValue().isValueNode())request.set(e.getKey(),e.getValue());});request.set("services",d.path("request").has("services")?d.path("request").get("services"):arr());RequestEvidence.removePayload(request);r.set("request",request);r.put("segmentCount",d.path("segments").size());r.put("sizeBytes",size);return r;
    }
    @Override public ObjectNode save(JsonNode input){
        if(input==null||!input.isObject())throw Captures.invalid("Supply a full snapshot object.");long now=System.currentTimeMillis();
        ObjectNode d=obj("schemaVersion",1,"storage","viewer","id",UUID.randomUUID().toString(),"name",cleanName(input.get("name")),"traceId",input.get("traceId"),
            "capturedAt",input.has("capturedAt")?input.get("capturedAt"):node(now),"savedAt",now,"partial",input.has("partial")?input.get("partial"):node(false),
            "warnings",input.has("warnings")?input.get("warnings"):arr(),"request",input.get("request"),"journey",input.get("journey"),"segments",input.get("segments"));
        byte[]raw=Captures.bytes(d);if(raw.length>MAX_BYTES)throw new ToolException(413,"Snapshot exceeds the 5 MiB limit; nothing was saved.");final ObjectNode detached=strictJson(raw);validateSnapshot(detached);locked(true,()->{publish(detached,false);return null;});return detached;
    }
    @Override public ObjectNode list(){
        List<JsonNode>records=new ArrayList<>();long[]usage={0,0};try{locked(false,()->{long[]pending=pendingUsage();usage[0]=pending[0];usage[1]=pending[1];for(String n:names()){Record r=read(n);records.add(summary(r.doc,r.size));usage[1]+=r.size;}return null;});}catch(Missing ignored){}
        ObjectNode large=chunked.list();for(JsonNode row:large.path("snapshots"))records.add(row);
        records.sort(Comparator.comparingDouble((JsonNode r)->r.path("savedAt").asDouble()).thenComparing(r->Captures.text(r,"id")).reversed());
        ArrayNode warnings=usage[0]>0?arr("Interrupted snapshot files count toward quota; review them manually. No files were automatically deleted."):arr();for(JsonNode warning:large.path("warnings"))warnings.add(warning);
        return obj("snapshots",records,"count",records.size(),"limits",limits(),"chunkedLimits",large.get("limits"),"totalBytes",usage[1]+large.path("totalBytes").asLong(),"warnings",warnings);
    }
    private static Set<String> entries(Path dir,String pattern){
        Set<String> names=new HashSet<>();
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(dir)){for(Path path:stream){String name=path.getFileName().toString();if(name.matches(pattern))names.add(name.substring(0,36));}}
        catch(java.io.IOException|SecurityException ignored){}
        return names;
    }
    /** IDs already in this namespace: its own files, plus the source IDs of large snapshots copied in. */
    private Set<String> presentIds(){
        Set<String> ids=entries(root,Captures.UUID_RE+"\\.json");
        try{for(String id:largeIds(this)){String source=Captures.text(chunked.get(id),"sourceSnapshotId");if(source!=null)ids.add(source);}}catch(RuntimeException ignored){}
        return ids;
    }
    /** How many snapshots sit under other namespaces, from file names alone; cheap enough for every listing. */
    public ObjectNode earlierSummary(){
        int configurations=0,total=0;Set<String> here=null;
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(root.getParent())){
            for(Path path:stream){
                if(!path.getFileName().toString().matches("[0-9a-f]{64}")||path.equals(root)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))continue;
                Set<String> ids=entries(path,Captures.UUID_RE+"\\.json");ids.addAll(entries(path.resolve("chunked-v2"),Captures.UUID_RE));
                if(ids.isEmpty())continue;
                if(here==null)here=presentIds();
                ids.removeAll(here);int count=ids.size();
                if(count>0){configurations++;total+=count;}
            }
        }catch(java.io.IOException|SecurityException ignored){}
        return obj("snapshotCount",total,"configurations",configurations);
    }
    /** Sibling namespaces to report; each is read fully, so the scan is bounded. */
    static final int MAX_OTHER_CONFIGURATIONS=8;
    private static Set<String> largeIds(ViewerSnapshotStore store){Set<String> ids=new HashSet<>();for(JsonNode row:store.chunked.list().path("snapshots"))ids.add(Captures.text(row,"id"));return ids;}
    /**
     * Snapshots saved under other namespaces of the same directory. A namespace is a hash of the
     * service mappings, so a changed port, service list or checkout path starts a new, empty one;
     * this read-only report lets the viewer say where the earlier snapshots are. Snapshots that
     * are already in this namespace are left out, so the report empties once everything is copied.
     */
    public ObjectNode otherConfigurations(){
        Path base=root.getParent();List<String> namespaces=new ArrayList<>();List<ObjectNode> groups=new ArrayList<>();ArrayNode warnings=arr();
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(base)){
            for(Path path:stream){String name=path.getFileName().toString();if(name.matches("[0-9a-f]{64}")&&!path.equals(root)&&Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))namespaces.add(name);}
        }catch(NoSuchFileException e){return obj("configurations",arr(),"snapshotCount",0,"omittedConfigurations",0,"warnings",warnings);}
        catch(java.io.IOException|SecurityException e){return obj("configurations",arr(),"snapshotCount",0,"omittedConfigurations",0,"warnings",arr("Earlier snapshot folders could not be read."));}
        Collections.sort(namespaces);int total=0,unreadable=0;Set<String> here=namespaces.isEmpty()?Collections.<String>emptySet():presentIds();
        for(String namespace:namespaces){
            try{
                ViewerSnapshotStore other=new ViewerSnapshotStore(base,namespace);Set<String> large=largeIds(other);ArrayNode rows=arr();double latest=0;
                for(JsonNode row:other.list().path("snapshots")){if(here.contains(Captures.text(row,"id")))continue;latest=Math.max(latest,row.path("savedAt").asDouble());rows.add(obj("id",row.get("id"),"name",row.get("name"),"savedAt",row.get("savedAt"),"large",large.contains(Captures.text(row,"id"))));}
                if(rows.size()>0){groups.add(obj("namespace",namespace,"count",rows.size(),"latestSavedAt",latest,"snapshots",rows));total+=rows.size();}
            }catch(RuntimeException e){unreadable++;}
        }
        if(unreadable>0)warnings.add(unreadable+" earlier snapshot "+(unreadable==1?"folder":"folders")+" could not be read and "+(unreadable==1?"was":"were")+" skipped. No files were changed.");
        groups.sort(Comparator.comparingDouble((ObjectNode group)->group.path("latestSavedAt").asDouble()).reversed());
        int omitted=Math.max(0,groups.size()-MAX_OTHER_CONFIGURATIONS);ArrayNode shown=arr();for(ObjectNode group:groups.subList(0,groups.size()-omitted))shown.add(group);
        return obj("configurations",shown,"snapshotCount",total,"omittedConfigurations",omitted,"warnings",warnings);
    }
    /**
     * Copies another namespace's snapshots into this one. The source is only read; nothing is
     * overwritten here, and this store's own count and byte limits decide what fits.
     */
    public ObjectNode copyFrom(String namespace){
        Path base=root.getParent(),source=namespaceRoot(base,namespace);
        if(source.equals(root))throw Captures.invalid("Those snapshots are already in this configuration.");
        if(!Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS))throw new ToolException(404,"No snapshots are saved under that configuration.");
        ViewerSnapshotStore other=new ViewerSnapshotStore(base,namespace);Set<String> large=largeIds(other);
        Set<String> importedLarge=new HashSet<>();for(String id:largeIds(this))importedLarge.add(Captures.text(chunked.get(id),"sourceSnapshotId"));
        ArrayNode copied=arr(),skipped=arr();
        for(JsonNode row:other.list().path("snapshots")){
            String id=Captures.text(row,"id");ObjectNode entry=obj("id",row.get("id"),"name",row.get("name"));
            try{
                if(large.contains(id)){
                    if(importedLarge.contains(id)){skipped.add(entry.put("reason","Already copied into this configuration."));continue;}
                    Path archive=Files.createTempFile("tracelens-copy-",".zip");
                    try{
                        try(java.io.OutputStream out=Files.newOutputStream(archive)){other.chunked.download(id,out);}
                        try(java.io.InputStream in=Files.newInputStream(archive)){chunked.importArchive(in);}
                    }finally{Files.deleteIfExists(archive);}
                }else{
                    boolean present;try{locked(false,()->read(id+".json"));present=true;}catch(Missing e){present=false;}
                    if(present){skipped.add(entry.put("reason","Already in this configuration."));continue;}
                    ObjectNode doc=other.get(id);validateSnapshot(doc);locked(true,()->{publish(doc,false);return null;});
                }
                copied.add(entry);
            }catch(ToolException e){skipped.add(entry.put("reason",e.getMessage()));}
            catch(java.io.IOException|RuntimeException e){skipped.add(entry.put("reason","This snapshot could not be copied."));}
        }
        return obj("namespace",namespace,"copied",copied,"skipped",skipped);
    }
    private void validId(String id){if(Captures.match(id,Captures.UUID_RE)==null)throw new ToolException(404,"Snapshot ID must be a canonical UUID, not a file path.");}
    @Override public ObjectNode get(String id){validId(id);try{return locked(false,()->read(id+".json").doc);}catch(Missing e){return chunked.get(id);}}
    ObjectNode projection(String id){ObjectNode doc=get(id);return doc.path("schemaVersion").asInt()==2?chunked.projection(id):doc;}
    public ObjectNode rename(String id,String name){validId(id);String cleaned=cleanName(node(name));try{return locked(false,true,()->{ObjectNode d=read(id+".json").doc;d.put("name",cleaned);return summary(d,publish(d,true));});}catch(Missing e){return chunked.rename(id,cleaned);}}
    public ObjectNode delete(String id){validId(id);try{return locked(false,true,()->{read(id+".json");Files.delete(root.resolve(id+".json"));syncDirectory();return obj("deleted",true,"id",id);});}catch(Missing e){return chunked.delete(id);}}
}
