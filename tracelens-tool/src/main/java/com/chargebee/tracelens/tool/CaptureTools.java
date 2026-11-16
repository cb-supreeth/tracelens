package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import static com.chargebee.tracelens.tool.Json.*;

/** Offline tools over explicitly persisted evidence. Never contact a service. */
public final class CaptureTools {
    private static final int MAX_RESULTS=100,MAX_SEARCH_BYTES=128*1024,MAX_SEARCH_RECORDS=40,MAX_SEARCH_EVENTS=200000;
    private static final List<String> CATEGORIES=Arrays.asList("class","method","exception","destination");
    private static final List<String> LIMITATIONS=Arrays.asList(
        "Only explicitly saved viewer snapshots and CLI captures are searched; no live service or source tree is read.",
        "A match is retained observed evidence, not coverage, a complete call graph, or proof that a change is safe.",
        "Results omit raw source, payloads, headers, user/context fields and exception messages. Code identifiers, paths, destinations and developer labels can still be sensitive.",
        "Duration is a local recorded sample including waits and instrumentation, not a performance benchmark.");
    private CaptureTools(){}
    public static ObjectNode projectViewerSnapshot(JsonNode snapshot,String label,String revision){return projectViewerSnapshot(snapshot,label,revision,Captures.MAX_EVENTS);}
    public static ObjectNode projectViewerSnapshot(JsonNode snapshot,String label,String revision,int maxEvents){
        return projectViewerSnapshot(snapshot,label,revision,maxEvents,Execution.MODEL_MAX_BYTES);
    }
    static ObjectNode projectViewerSnapshot(JsonNode snapshot,String label,String revision,int maxEvents,int nestingModelBytes){
        ViewerSnapshotStore.validateSnapshot(snapshot);if(Captures.bytes(snapshot).length>ViewerSnapshotStore.MAX_BYTES)throw Captures.invalid("Viewer snapshot exceeds the projection byte limit.");
        String name=label==null?Captures.text(snapshot,"name"):label;if(label==null&&name.codePointCount(0,name.length())>80)name=name.substring(0,name.offsetByCodePoints(0,80));
        Captures.validateCaptureInputs(Captures.text(snapshot,"traceId"),name,revision);if(maxEvents<0||maxEvents>Captures.MAX_EVENTS)throw Captures.invalid("Invalid projection event budget.");
        String created;try{double millis=snapshot.path("capturedAt").doubleValue();long seconds=(long)Math.floor(millis/1000);long micros=Math.round((millis-seconds*1000d)*1000);created=Captures.timestamp(Instant.ofEpochSecond(seconds,micros*1000));}catch(RuntimeException e){throw Captures.invalid("Snapshot capture time cannot be represented in a CLI capture.");}
        ObjectNode result=Captures.base(Captures.text(snapshot,"id"),Captures.text(snapshot,"traceId"),name,revision,created);ArrayNode segments=(ArrayNode)result.get("segments");Set<String>warnings=new TreeSet<>();
        if(bool(snapshot,"partial"))warnings.add("viewer_snapshot_partial");if(snapshot.path("warnings").size()>0)warnings.add("viewer_snapshot_warnings_present");
        if(snapshot.path("journey").path("segments").size()!=snapshot.path("segments").size())warnings.add("viewer_snapshot_missing_exports");if(snapshot.path("segments").size()>Captures.MAX_SEGMENTS)warnings.add("segment_limit");
        Map<String,ArrayNode> savedHttp=savedHttpCalls(snapshot,warnings);int remaining=maxEvents;
        int perSegmentEvents=maxEvents/Math.max(1,Math.min(snapshot.path("segments").size(),Captures.MAX_SEGMENTS));
        for(JsonNode row:Captures.slice(snapshot.path("segments"),Captures.MAX_SEGMENTS)){
            String service=Captures.text(row,"service");if(service==null||service.isEmpty())service=Captures.text(row,"applicationId");
            if(Captures.match(service,Captures.SERVICE_RE)==null||Captures.match(row.get("segmentId"),Captures.UUID_RE)==null||Captures.match(row.get("serviceInstanceId"),Captures.ID_RE)==null)
                throw Captures.invalid("Saved segment identities are unsupported by the CLI capture format; no live data was substituted.");
            ObjectNode input=copy(row);if(!input.has("httpCalls"))input.set("httpCalls",savedHttp.getOrDefault(segmentKey(row),arr()));
            ObjectNode s=Captures.projectSegment(service,input,Math.min(remaining,perSegmentEvents),nestingModelBytes);remaining-=s.path("eventsProcessed").asInt();segments.add(s);
            if(row.has("analysisCoverage"))s.set("analysisCoverage",row.get("analysisCoverage"));
            if(Captures.bytes(result).length>Captures.MAX_CAPTURE_BYTES-16384){segments.remove(segments.size()-1);warnings.add("capture_byte_limit");break;}
        }
        Captures.finish(result,warnings);Captures.validateCapture(result);return result;
    }
    private static String segmentKey(JsonNode row){return row.hasNonNull("segmentKey")?Captures.text(row,"segmentKey"):ViewerSnapshotStore.identity(row);}
    private static Map<String,ArrayNode> savedHttpCalls(JsonNode snapshot,Set<String>warnings){
        Map<String,List<JsonNode>> owners=new HashMap<>();Map<String,ArrayNode> result=new HashMap<>();Map<String,Integer> callKeys=new HashMap<>();
        for(JsonNode row:snapshot.path("segments"))owners.computeIfAbsent(segmentKey(row),key->new ArrayList<>()).add(row);
        for(JsonNode call:snapshot.path("journey").path("httpCalls")){String key=Captures.text(call,"callKey");if(key!=null)callKeys.put(key,callKeys.getOrDefault(key,0)+1);}
        for(JsonNode call:snapshot.path("journey").path("httpCalls")){
            String owner=Captures.text(call,"ownerSegmentKey"),callKey=Captures.text(call,"callKey");List<JsonNode> rows=owners.get(owner);
            if(rows==null||rows.size()!=1){warnings.add("snapshot_http_owner_unavailable_or_ambiguous");continue;}
            JsonNode row=rows.get(0);String service=Captures.text(row,"service");if(service==null||service.isEmpty())service=Captures.text(row,"applicationId");
            if(call.hasNonNull("service")&&!Objects.equals(service,Captures.text(call,"service"))||call.hasNonNull("serviceInstanceId")&&!Objects.equals(Captures.text(row,"serviceInstanceId"),Captures.text(call,"serviceInstanceId"))
                ||call.hasNonNull("segmentId")&&!Objects.equals(Captures.text(row,"segmentId"),Captures.text(call,"segmentId"))||call.hasNonNull("traceId")&&!Objects.equals(Captures.text(snapshot,"traceId"),Captures.text(call,"traceId"))){warnings.add("snapshot_http_owner_unavailable_or_ambiguous");continue;}
            if(callKey!=null&&callKeys.get(callKey)>1){warnings.add("snapshot_http_call_identity_ambiguous");continue;}
            // V1 exports already carry their calls; v2 keeps them in the saved journey instead.
            if(!row.has("httpCalls"))result.computeIfAbsent(owner,key->arr()).add(call);
        }
        return result;
    }
    public static ObjectNode compareSavedSnapshots(ViewerSnapshotStore store,String before,String after){
        return compareSavedSnapshots(store,before,after,Collections.emptyList());
    }
    public static ObjectNode compareSavedSnapshots(ViewerSnapshotStore store,String before,String after,List<Service> services){
        Map<String,JsonNode> aliases=new HashMap<>();for(Service service:services){if(aliases.containsKey(service.name))aliases.put(service.name,null);else aliases.put(service.name,service.dependencyAliases);}
        ObjectNode beforeSnapshot=store.projection(before),afterSnapshot=store.projection(after);
        ObjectNode first=projectViewerSnapshot(beforeSnapshot,null,null),second=projectViewerSnapshot(afterSnapshot,null,null),result=Captures.compareCaptures(first,second);
        compareRecordingManifests(first,second,(ArrayNode)result.get("warnings"));
        result.set("sourceSnapshots",obj("before",obj("snapshotId",before,"name",first.get("label")),"after",obj("snapshotId",after,"name",second.get("label"))));
        result.put("projection","Saved exports only; source and raw runtime context are excluded.");int behaviorBytes=behaviorBudget(result);
        // Grouping uses the existing viewer budget; legacy captures and raw deltas keep their original budget.
        if(behaviorBytes>=1024)result.set("behavior",BehaviorComparison.build(withExpandedNesting(first,projectViewerSnapshot(beforeSnapshot,null,null,Captures.MAX_EVENTS,Execution.VIEWER_MODEL_MAX_BYTES)),withExpandedNesting(second,projectViewerSnapshot(afterSnapshot,null,null,Captures.MAX_EVENTS,Execution.VIEWER_MODEL_MAX_BYTES)),before,after,behaviorBytes,aliases));
        else ((ArrayNode)result.get("warnings")).add("behavior_comparison_metadata_limit");
        Captures.pruneComparison(result,Captures.MAX_COMPARISON_BYTES,false);
        if(behaviorBytes<1024||result.path("behavior").path("limited").asBoolean())result.put("limited",true);return result;
    }
    private static void compareRecordingManifests(JsonNode before,JsonNode after,ArrayNode warnings){
        for(String key:Arrays.asList("configurationFingerprint","agentBuildId")){
            Map<String,Set<String>> left=new HashMap<>(),right=new HashMap<>();
            List<Map<String,Set<String>>> maps=Arrays.asList(left,right);int side=0;
            for(JsonNode capture:Arrays.asList(before,after)){
                Map<String,Set<String>> values=maps.get(side++);
                for(JsonNode segment:capture.path("segments")){
                    String value=Json.text(segment.path("recordingManifest"),key);
                    if(value!=null)values.computeIfAbsent(Json.text(segment,"service"),ignored->new TreeSet<>()).add(value);
                }
            }
            for(String service:left.keySet())if(right.containsKey(service)&&!left.get(service).equals(right.get(service))){
                warnings.add("configurationFingerprint".equals(key)?"capture_configuration_differs":"agent_build_differs");break;
            }
        }
    }
    static int behaviorBudget(ObjectNode comparison){ObjectNode metadata=copy(comparison);for(String category:Captures.CATEGORIES)for(String delta:Captures.DELTAS)((ArrayNode)metadata.path(category).path(delta)).removeAll();Captures.finishComparison(metadata);
        return Math.min(BehaviorComparison.MAX_BYTES,Captures.MAX_COMPARISON_BYTES-Captures.bytes(metadata).length-512);
    }
    static ObjectNode withExpandedNesting(JsonNode compact,JsonNode expanded){ObjectNode result=copy(compact);Map<String,List<JsonNode>> segments=new HashMap<>();Map<String,Integer> counts=new HashMap<>();
        for(JsonNode segment:expanded.path("segments"))segments.computeIfAbsent(Captures.canonical(Captures.select(segment,"service","segmentId","serviceInstanceId")),key->new ArrayList<>()).add(segment);
        for(JsonNode segment:compact.path("segments")){String key=Captures.canonical(Captures.select(segment,"service","segmentId","serviceInstanceId"));counts.put(key,counts.getOrDefault(key,0)+1);}
        // A larger nesting model can hit the capture-byte cutoff earlier; never replace its observation scope.
        for(JsonNode segment:result.path("segments")){String key=Captures.canonical(Captures.select(segment,"service","segmentId","serviceInstanceId"));List<JsonNode> matches=segments.get(key);if(counts.get(key)!=1||matches==null||matches.size()!=1)continue;JsonNode match=matches.get(0);
            if(segment.path("eventsProcessed").equals(match.path("eventsProcessed"))&&segment.path("methods").equals(match.path("methods")))((ObjectNode)segment).set("nesting",match.path("nesting").deepCopy());
        }return result;
    }
    public static ObjectNode exportToCaptureStore(ViewerSnapshotStore snapshots,CaptureStore store,String id,String label,String revision){
        if(label!=null||revision!=null)Captures.validateCaptureInputs("validation",label==null?"snapshot":label,revision);
        ObjectNode capture=projectViewerSnapshot(snapshots.projection(id),label,revision);capture.put("captureId",UUID.randomUUID().toString());ObjectNode metadata=store.save(capture);
        return obj("schemaVersion",1,"status","saved","saved",true,"sourceSnapshotId",id,"capture",metadata,"warnings",capture.get("warnings"),
            "segmentWarnings",segmentWarnings(capture),"limitations",capture.get("limitations"));
    }
    static ArrayNode segmentWarnings(JsonNode capture){ArrayNode result=arr();for(JsonNode s:capture.path("segments"))if(s.path("warnings").size()>0)result.add(Captures.select(s,"service","segmentId","warnings"));return result;}
    private static ArrayNode identities(JsonNode segment,String category,boolean includePath){
        ArrayNode result=arr();
        if("class".equals(category)){Map<String,Long>classes=new TreeMap<>(),lines=new TreeMap<>();for(JsonNode r:segment.path("methods"))classes.put(Captures.text(r,"className"),classes.getOrDefault(Captures.text(r,"className"),0L)+r.path("count").asLong());
            for(Map.Entry<String,Long>e:classes.entrySet())result.add(obj("className",e.getKey(),"count",e.getValue(),"evidenceKind","method-entry"));
            for(JsonNode r:segment.path("lines"))if(!classes.containsKey(Captures.text(r,"className")))lines.put(Captures.text(r,"className"),lines.getOrDefault(Captures.text(r,"className"),0L)+r.path("count").asLong());
            for(Map.Entry<String,Long>e:lines.entrySet())result.add(obj("className",e.getKey(),"count",e.getValue(),"evidenceKind","line-observation"));
        }else if("method".equals(category)||"exception".equals(category)){for(JsonNode r:segment.path("method".equals(category)?"methods":"exceptions")){ObjectNode identity=copy(r);identity.put("evidenceKind","method".equals(category)?"method-entry":"exception-observation");result.add(identity);}}
        else for(JsonNode r:segment.path("dependencies"))if(r.hasNonNull("destination")||r.hasNonNull("system")||r.hasNonNull("operation")){ObjectNode identity=Captures.select(r,"destination","count","boundaryKind","system","direction","operation","status","evidenceKind");if(includePath&&r.hasNonNull("path"))identity.set("path",r.get("path"));result.add(identity);}
        return result;
    }
    private static class Source{String storage,id;CaptureStore store;Source(String storage,String id,CaptureStore store){this.storage=storage;this.id=id;this.store=store;}}
    public static ObjectNode searchSavedEvidence(ViewerSnapshotStore snapshots,CaptureStore captures,String query,String category,int limit){
        return searchSavedEvidence(snapshots,captures,query,category,limit,null);
    }
    public static ObjectNode searchSavedEvidence(ViewerSnapshotStore snapshots,CaptureStore captures,String query,String category,int limit,JsonNode snapshotIds){
        if(query==null||query.codePointCount(0,query.length())<1||query.codePointCount(0,query.length())>128||query.trim().isEmpty()||!Captures.printable(query))throw Captures.invalid("Search query must contain 1–128 printable characters.");
        if(category!=null&&!"all".equals(category)&&!CATEGORIES.contains(category))throw Captures.invalid("Search category must be class, method, exception or destination.");
        if(limit<1||limit>MAX_RESULTS)throw Captures.invalid("Search limit must be between 1 and 100.");
        if(snapshotIds!=null&&(snapshots==null||!snapshotIds.isArray()||snapshotIds.size()!=2||Captures.match(snapshotIds.get(0),Captures.UUID_RE)==null||Captures.match(snapshotIds.get(1),Captures.UUID_RE)==null||snapshotIds.get(0).equals(snapshotIds.get(1))))throw Captures.invalid("snapshotIds must contain exactly two distinct saved viewer snapshot IDs.");
        List<String>categories=category==null||"all".equals(category)?CATEGORIES:Collections.singletonList(category);String needle=Captures.casefold(query.trim());
        ObjectNode result=obj("schemaVersion",1,"kind","saved-evidence-search","query",query.trim(),"category",category==null?"all":category,"results",arr(),"returned",0,"matched",0,"recordsScanned",0,"recordsAvailable",0,"eventsProcessed",0,"limited",false,"warnings",arr(),
            "limits",obj("maxResults",limit,"maxRecords",MAX_SEARCH_RECORDS,"maxEvents",MAX_SEARCH_EVENTS,"maxBytes",MAX_SEARCH_BYTES),"limitations",LIMITATIONS);
        Set<String>warnings=new TreeSet<>();List<Source>sources=new ArrayList<>();
        if(snapshotIds!=null){for(JsonNode id:snapshotIds)sources.add(new Source("viewer",id.asText(),snapshots));result.put("recordsAvailable",2);result.put("scope","selected-snapshots");result.set("snapshotIds",snapshotIds.deepCopy());}
        else for(CaptureStore store:Arrays.asList(snapshots,captures)){if(store==null)continue;String storage=store==snapshots?"viewer":"capture",key=store==snapshots?"snapshots":"captures";
            try{JsonNode rows=store.list().path(key);result.put("recordsAvailable",result.path("recordsAvailable").asInt()+rows.size());for(JsonNode r:rows)sources.add(new Source(storage,Captures.text(r,store==snapshots?"id":"captureId"),store));}
            catch(RuntimeException e){warnings.add(storage+"_store_unavailable");result.put("limited",true);}
        }
        if(sources.size()>MAX_SEARCH_RECORDS){warnings.add("record_scan_limit");result.put("limited",true);}int remaining=MAX_SEARCH_EVENTS;ArrayNode matches=(ArrayNode)result.get("results");
        for(Source source:sources.subList(0,Math.min(sources.size(),MAX_SEARCH_RECORDS))){
            if(source.storage.equals("viewer")&&remaining<=0){warnings.add("viewer_event_scan_limit");result.put("limited",true);continue;}
            ObjectNode capture;try{capture=source.storage.equals("viewer")?snapshots.projection(source.id):source.store.get(source.id);if(source.storage.equals("viewer")){capture=projectViewerSnapshot(capture,null,null,Math.min(remaining,Captures.MAX_EVENTS));int processed=0;for(JsonNode s:capture.path("segments"))processed+=s.path("eventsProcessed").asInt();remaining-=processed;result.put("eventsProcessed",result.path("eventsProcessed").asInt()+processed);}}
            catch(RuntimeException e){warnings.add(source.storage+"_record_unavailable_or_unsupported");result.put("limited",true);continue;}
            result.put("recordsScanned",result.path("recordsScanned").asInt()+1);if(bool(capture,"partial"))warnings.add("partial_saved_evidence");
            ObjectNode reference=obj("storage",source.storage,source.storage.equals("viewer")?"snapshotId":"captureId",source.id,"label",capture.get("label"),"traceId",capture.get("traceId"),"createdAt",capture.get("createdAt"),"partial",capture.get("partial"));
            for(JsonNode segment:capture.path("segments")){
                for(JsonNode warning:segment.path("warnings"))if("event_processing_limit".equals(warning.asText())){warnings.add("viewer_event_scan_limit");result.put("limited",true);}
                for(String selected:categories)for(JsonNode identity:identities(segment,selected,snapshotIds!=null)){
                    StringBuilder haystack=new StringBuilder();for(String key:Arrays.asList("className","methodName","methodDescriptor","exceptionType","destination","system","operation")){String value=Captures.text(identity,key);if(value!=null)haystack.append(' ').append(value);}
                    if(snapshotIds!=null)for(String value:Arrays.asList(Captures.text(identity,"path"),Captures.text(segment,"service"),Captures.text(segment.path("request"),"path")))if(value!=null)haystack.append(' ').append(value);
                    if(!Captures.casefold(haystack.toString()).contains(needle))continue;result.put("matched",result.path("matched").asInt()+1);
                    if(matches.size()<limit){ObjectNode match=reference.deepCopy();match.put("category",selected);match.set("service",segment.get("service"));match.set("segmentId",segment.get("segmentId"));if(snapshotIds!=null)match.set("serviceInstanceId",segment.get("serviceInstanceId"));match.set("request",segment.get("request"));match.setAll((ObjectNode)identity);matches.add(match);}
                }
            }
        }
        result.set("warnings",node(warnings));result.put("returned",matches.size());if(result.path("matched").asInt()>matches.size())result.put("limited",true);
        while(Captures.bytes(result).length>MAX_SEARCH_BYTES&&matches.size()>0){matches.remove(matches.size()-1);result.put("returned",matches.size());result.put("limited",true);}return result;
    }
}
