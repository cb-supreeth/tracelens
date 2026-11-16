package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.*;

/** Bounded read-only federation; absence of retained evidence is never non-execution. */
public final class Federation {
    public final Map<String,Service> services=new LinkedHashMap<>();
    public final Map<String,DebugClient> clients=new LinkedHashMap<>();
    private final int readMillis,readRequests,readBytes;
    public Federation(List<Service> registry) {
        this(registry,BoundedReads.MAX_MILLIS,BoundedReads.MAX_REQUESTS,BoundedReads.MAX_BYTES);
    }
    Federation(List<Service> registry,int readMillis,int readRequests,int readBytes) {
        if(registry==null||registry.isEmpty()||registry.size()>32)throw new IllegalArgumentException("registry requires 1–32 uniquely named services");
        this.readMillis=readMillis;this.readRequests=readRequests;this.readBytes=readBytes;
        Set<String> urls=new HashSet<>();
        for(Service s:registry){
            if(services.containsKey(s.name))throw new IllegalArgumentException("registry requires 1–32 uniquely named services");
            if(!urls.add(Service.urlKey(s.url)))throw new IllegalArgumentException("configure each upstream endpoint only once");
            services.put(s.name,s);clients.put(s.name,new DebugClient(s.url));
        }
    }
    private List<Service> selected(String name){
        if(name==null)return new ArrayList<>(services.values());
        Service s=services.get(name);if(s==null)throw new IllegalArgumentException("unknown configured service: "+name);return Collections.singletonList(s);
    }
    private interface Read { ObjectNode run(Service s,DebugClient c,BoundedReads.Budget budget); }
    private static final class Result {Service service;ObjectNode doc,error;Result(Service s,ObjectNode d,ObjectNode e){service=s;doc=d;error=e;}}
    private List<Result> read(Read operation,String name) {
        List<Service> selected=selected(name);long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(readMillis);
        List<Future<Result>> futures=new ArrayList<>();
        try{
            for(int i=0;i<selected.size();i++){
                Service s=selected.get(i);int requests=readRequests/selected.size()+(i<readRequests%selected.size()?1:0);
                int bytes=readBytes/selected.size()+(i<readBytes%selected.size()?1:0);
                try{futures.add(BoundedReads.submit(()->{
                    // Reserve a fair time slice as well as bytes/requests for services queued behind the first eight.
                    long serviceDeadline=Math.min(deadline,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(1,readMillis/((selected.size()+7)/8))));
                    try{return new Result(s,operation.run(s,clients.get(s.name),new BoundedReads.Budget(serviceDeadline,requests,bytes)),null);}
                    catch(RuntimeException e){return new Result(s,null,Json.obj("service",s.name,"error",e.getMessage()));}
                }));}catch(BoundedReads.Limit busy){futures.add(CompletableFuture.completedFuture(new Result(s,null,Json.obj("service",s.name,"error",busy.getMessage()))));}
            }
            List<Result> out=new ArrayList<>();
            for(int i=0;i<futures.size();i++)try{out.add(BoundedReads.await(futures.get(i),deadline));}
                catch(BoundedReads.Limit limit){out.add(new Result(selected.get(i),null,Json.obj("service",selected.get(i).name,"error",limit.getMessage())));}
            return out;
        }finally{for(Future<Result> future:futures)if(!future.isDone())future.cancel(true);}
    }
    private static ObjectNode tag(Service service,JsonNode row){
        ObjectNode r=Json.copy(row);r.put("service",service.name);
        r.put("segmentKey",Json.stringify(Json.arr(service.name,row.path("serviceInstanceId"),row.path("segmentId"))));return r;
    }
    private static JsonNode field(JsonNode row,String name){JsonNode n=row.get(name);return n==null||n.isNull()||n.asText().isEmpty()?row.path("attributes").get(name):n;}
    public ObjectNode traces(ObjectNode filters) {
        if(filters==null)filters=Json.obj();
        Double minutes=Evidence.number(filters.get("since_minutes"));JsonNode limitValue=filters.get("limit");
        if(limitValue!=null&&(!limitValue.isIntegralNumber()||!limitValue.canConvertToInt()))throw new IllegalArgumentException("limit must be between 1 and 10000");
        int limit=limitValue==null?1000:limitValue.intValue();
        if(filters.hasNonNull("since_minutes")&&(minutes==null||minutes>525600))throw new IllegalArgumentException("since_minutes must be between 0 and 525600");
        if(limit<1||limit>10000)throw new IllegalArgumentException("limit must be between 1 and 10000");
        String method=Json.text(filters,"method"),path=Json.text(filters,"path"),user=Json.text(filters,"user");
        List<JsonNode> rows=new ArrayList<>();ArrayNode errors=Json.arr();boolean partial=false,limited=false;
        for(Result r:read((s,c,b)->b.call(c::traces,DebugClient.MAX_TRACE_SUMMARY_BYTES),Json.text(filters,"service"))) {
            if(r.error!=null)errors.add(r.error);
            else{
                partial|=Evidence.truthy(r.doc.get("partial"))||Evidence.truthy(r.doc.get("errors"));limited|=Evidence.truthy(r.doc.get("limited"));
                for(JsonNode row:Json.rows(r.doc,"traces"))if(row.isObject())rows.add(tag(r.service,RequestEvidence.summary(row)));
            }
        }
        double cutoff=minutes==null?Double.NEGATIVE_INFINITY:System.currentTimeMillis()-minutes*60000;
        List<JsonNode> matched=new ArrayList<>();
        for(JsonNode r:rows)if((method==null||method.isEmpty()||method.equalsIgnoreCase(field(r,"httpMethod")==null?"":field(r,"httpMethod").asText()))
                &&(path==null||path.isEmpty()||(field(r,"path")==null?"":field(r,"path").asText("")).toLowerCase(Locale.ROOT).contains(path.toLowerCase(Locale.ROOT)))
                &&(user==null||user.isEmpty()||Evidence.s(r,"user").toLowerCase(Locale.ROOT).contains(user.toLowerCase(Locale.ROOT)))
                &&r.path("startedAtMillis").asDouble(r.path("startedAt").asDouble(0))>=cutoff)matched.add(r);
        matched.sort((a,b)->Double.compare(b.path("lastActivityMillis").asDouble(b.path("startedAtMillis").asDouble(0)),a.path("lastActivityMillis").asDouble(a.path("startedAtMillis").asDouble(0))));
        return Json.obj("traces",Json.node(matched.subList(0,Math.min(limit,matched.size()))),"count",Math.min(limit,matched.size()),"errors",errors,
                "partial",errors.size()>0||partial,"limited",limited||matched.size()>limit,"federated",true);
    }
    public ObjectNode traces() { return traces(Json.obj()); }
    public ObjectNode summary(){
        ArrayNode rows=Json.arr(),errors=Json.arr();long traces=0,events=0;
        for(Result r:read((s,c,b)->b.call(c::summary,65536),null))if(r.error!=null)errors.add(r.error);else{
            ObjectNode row=r.doc.deepCopy();row.put("service",r.service.name);rows.add(row);traces+=row.path("traceCount").asLong();events+=row.path("totalEvents").asLong();
        }
        return Json.obj("federated",true,"readOnly",true,"services",rows,"configuredServices",Json.node(services.keySet()),"errors",errors,"partial",errors.size()>0,"traceCount",traces,"totalEvents",events);
    }
    public ObjectNode findByLensId(String id){
        ObjectNode result=traces(Json.obj("limit",10000));ArrayNode rows=Json.arr();for(JsonNode r:Json.rows(result,"traces"))if(id.equals(Json.text(r,"lensId")))rows.add(r);result.set("traces",rows);result.put("count",rows.size());return result;
    }
    public ObjectNode export(String id,String service,String instance) {
        if(service!=null&&!service.isEmpty()){
            selected(service);ObjectNode row=clients.get(service).export(id);
            if(instance!=null&&!instance.equals(Json.text(row,"serviceInstanceId")))throw new ToolException(409,"service instance changed; refresh the trace list");
            return tag(services.get(service),row);
        }
        ObjectNode found=traces(Json.obj("limit",10000));List<JsonNode> matches=new ArrayList<>();
        for(JsonNode r:Json.rows(found,"traces"))if(id.equals(Json.text(r,"segmentId"))&&(instance==null||instance.equals(Json.text(r,"serviceInstanceId"))))matches.add(r);
        if(matches.size()!=1||found.path("errors").size()>0||Json.bool(found,"partial")||Json.bool(found,"limited"))
            throw new ToolException(409,"segment lookup is ambiguous, missing, or incomplete; specify service and instance");
        JsonNode m=matches.get(0);return export(id,Json.text(m,"service"),Json.text(m,"serviceInstanceId"));
    }
    public String sourceRoot(String service){selected(service);return services.get(service).sourceRoot;}
    public ObjectNode getDistributedTrace(String traceId) {
        if(traceId==null||!traceId.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("invalid trace id");
        ArrayNode segments=Json.arr(),errors=Json.arr();
        for(Result r:read((s,c,budget)->{
            ObjectNode doc;
            try{doc=budget.call(()->c.findByTraceId(traceId),DebugClient.MAX_TRACE_SUMMARY_BYTES);}catch(ToolException e){if(e.status!=404)throw e;doc=budget.call(c::traces,DebugClient.MAX_TRACE_SUMMARY_BYTES);}
            List<JsonNode>matches=new ArrayList<>();for(JsonNode row:Json.rows(doc,"traces"))if(traceId.equals(Json.text(row,"traceId")))matches.add(row);
            ArrayNode ss=Json.arr(),ee=Json.arr();
            if(Evidence.truthy(doc.get("partial"))||Evidence.truthy(doc.get("limited"))||Evidence.truthy(doc.get("errors"))||Evidence.truthy(doc.get("warnings")))
                ee.add(Json.obj("service",s.name,"error","Upstream trace lookup is partial or limited; retained evidence may be missing"));
            JsonNode sourceRows=doc.get("traces");
            if(sourceRows==null||!sourceRows.isArray())ee.add(Json.obj("service",s.name,"error","Malformed upstream trace summaries omitted"));
            else for(JsonNode sourceRow:sourceRows)if(!sourceRow.isObject()){ee.add(Json.obj("service",s.name,"error","Malformed upstream trace summaries omitted"));break;}
            int segmentLimit=512/services.size();
            for(JsonNode row:matches.subList(0,Math.min(matches.size(),segmentLimit)))try{
                ObjectNode segment;
                try { segment=budget.call(()->c.overview(Json.text(row,"segmentId")),1024*1024).deepCopy(); }
                catch(ToolException unsupported) {
                    if(unsupported.status!=404&&unsupported.status!=405)throw unsupported;
                    segment=budget.call(()->c.export(Json.text(row,"segmentId")),8*1024*1024).deepCopy();
                }
                if(!traceId.equals(Json.text(segment,"traceId"))||!Objects.equals(Json.text(row,"segmentId"),Json.text(segment,"segmentId"))||!Objects.equals(Json.text(row,"serviceInstanceId"),Json.text(segment,"serviceInstanceId")))throw new ToolException(409,"trace or service instance changed during lookup");
                // Pre-boundary agents omitted events entirely; Python treated
                // that legacy shape as an empty collection, not truncation.
                segment.setAll(Dependencies.extractBoundaries(segment.has("events")?segment.get("events"):Json.arr(),s.dependencyAliases));
                for(JsonNode call:Json.rows(segment,"httpCalls"))if(call.isObject())((ObjectNode)call).set("destinationName",Json.node(Dependencies.aliasFor(s.dependencyAliases,Json.text(call,"peer"))));
                segment.remove("events");ss.add(tag(s,segment));
            }catch(RuntimeException e){
                ObjectNode summary=RequestEvidence.summary(row);summary.put("detailsAvailable",false);summary.put("partial",true);
                summary.put("detailError","Segment details unavailable; the matched summary is retained.");
                summary.set("httpCalls",Json.arr());summary.set("externalCalls",Json.arr());ss.add(tag(s,summary));
                ee.add(Json.obj("service",s.name,"segmentId",row.path("segmentId"),"error",e.getMessage()));
            }
            if(matches.size()>segmentLimit)ee.add(Json.obj("service",s.name,"error","fair service segment limit reached"));
            return Json.obj("segments",ss,"errors",ee);
        },null))if(r.error!=null)errors.add(r.error);else{segments.addAll((ArrayNode)r.doc.get("segments"));errors.addAll((ArrayNode)r.doc.get("errors"));}
        return joinTrace(traceId,segments,errors);
    }
    public static ObjectNode joinTrace(String traceId,ArrayNode segments,ArrayNode errors) {
        ArrayNode summaries=Json.arr(),calls=Json.arr(),external=Json.arr(),links=Json.arr(),warnings=Json.arr();
        if(segments.size()>512)warnings.add("Distributed segment display limit reached (512); graph is incomplete");
        boolean callsLimited=false,externalLimited=false;Map<String,List<ObjectNode>>byCall=new HashMap<>();
        for(int s=0;s<Math.min(512,segments.size());s++){
            JsonNode segment=segments.get(s);if(!segment.isObject()){warnings.add("Malformed segment omitted");continue;}
            String key=Json.text(segment,"segmentKey","");ObjectNode summary=RequestEvidence.summary(segment);summary.remove(Arrays.asList("events","httpCalls","externalCalls","contextParams"));summaries.add(summary);
            int index=0;
            for(JsonNode call:Json.rows(segment,"httpCalls")) {
                if(calls.size()>=5000){callsLimited=true;break;}
                if(!call.isObject()){index++;continue;}
                ObjectNode tagged=Json.copy(call);tagged.setAll(Json.obj("service",segment.path("service"),"serviceInstanceId",segment.path("serviceInstanceId"),
                        "ownerSegmentKey",key,"callKey",key+":call:"+index++,"downstreamSegmentKeys",Json.arr()));calls.add(tagged);
                String span=Json.text(call,"spanId");if(span!=null&&!span.isEmpty())byCall.computeIfAbsent(span,k->new ArrayList<>()).add(tagged);
            }
            JsonNode boundaries=segment.get("externalCalls");
            if(boundaries==null||boundaries.isNull()){
                ObjectNode extraction=Dependencies.extractBoundaries(segment.has("events")?segment.get("events"):Json.arr());boundaries=extraction.get("externalCalls");
                if(Json.bool(extraction,"externalCallsTruncated"))warnings.add("External boundary extraction limit reached or malformed events: "+key);
            }
            if(!boundaries.isArray()){boundaries=Json.arr();warnings.add("Malformed external boundary summaries: "+key);}
            boolean malformed=false;
            for(int i=0;i<Math.min(boundaries.size(),Dependencies.MAX_EXTERNAL_CALLS);i++){
                if(external.size()>=Dependencies.MAX_EXTERNAL_CALLS){externalLimited=true;break;}
                JsonNode raw=boundaries.get(i);ObjectNode call=Dependencies.sanitizeSummary(raw);if(call==null){malformed=true;continue;}
                JsonNode eventIndex=raw.get("eventIndex");int ei=eventIndex!=null&&eventIndex.isIntegralNumber()&&eventIndex.canConvertToInt()&&eventIndex.intValue()>=0?eventIndex.intValue():i;
                call.setAll(Json.obj("service",segment.path("service"),"serviceInstanceId",segment.path("serviceInstanceId"),"segmentId",segment.path("segmentId"),"ownerSegmentKey",key,"callKey",key+":dependency:"+ei));external.add(call);
            }
            if(malformed)warnings.add("Malformed external boundary summaries omitted: "+key);
            externalLimited|=boundaries.size()>Dependencies.MAX_EXTERNAL_CALLS;
            if(Evidence.truthy(segment.get("truncated")))warnings.add("Truncated segment: "+key);
            if(Evidence.truthy(segment.get("httpCallsTruncated")))warnings.add("HTTP call capture truncated: "+key);
            if(Evidence.truthy(segment.get("externalCallsTruncated")))warnings.add("External boundary extraction limit reached or malformed events: "+key);
            if(Evidence.truthy(segment.get("overviewTruncated")))warnings.add("Compact overview is partial; more retained details are available by paging: "+key);
            if(Evidence.truthy(segment.get("detailTruncated"))||segment.path("omittedDetailCallbacks").asLong()>0)warnings.add("Some detail was not retained; inspect capture mode and drop reasons: "+key);
            if("ACTIVE".equals(Json.text(segment,"status")))warnings.add("Active segment may still produce downstream work: "+key);
        }
        if(callsLimited)warnings.add("Distributed HTTP call display limit reached (5000); graph is incomplete");
        if(externalLimited)warnings.add("Distributed external boundary display limit reached (5000); graph is incomplete");
        if(external.size()>0)warnings.add("Dependency boundaries are local observations only; remote execution is not observed");
        if(summaries.size()==0)warnings.add("No segments observed in reachable configured services; trace may be unrecorded or evicted");
        for(JsonNode segment:summaries){
            String parent=Json.text(segment,"parentSpanId");if(parent==null||parent.isEmpty())continue;
            List<ObjectNode>candidates=byCall.getOrDefault(parent,Collections.emptyList());
            if(candidates.size()==1){ObjectNode call=candidates.get(0);((ArrayNode)call.get("downstreamSegmentKeys")).add(segment.path("segmentKey"));
                links.add(Json.obj("fromCallKey",call.path("callKey"),"fromSegmentKey",call.path("ownerSegmentKey"),"toSegmentKey",segment.path("segmentKey")));
            }else warnings.add((candidates.isEmpty()?"Missing":"Ambiguous")+" parent call for "+Json.text(segment,"segmentKey"));
        }
        for(JsonNode call:calls){boolean observed=call.path("downstreamSegmentKeys").size()>0;((ObjectNode)call).put("downstreamState",observed?"observed":"not-observed");
            if(!observed)warnings.add("Downstream not observed for "+Json.text(call,"callKey")+"; may be uninstrumented, filtered, unconfigured, pending or evicted");}
        boolean partial=errors.size()>0||warnings.size()>0;
        return Json.obj("traceId",traceId,"segments",summaries,"httpCalls",calls,"externalCalls",external,"links",links,"errors",errors.deepCopy(),"warnings",warnings,
                "partial",partial,"completeness",summaries.size()==0?"not-observed":partial?"partial":"observed-only",
                "note","Live retained evidence only; absence does not prove a service was not called. Durations are local; clocks are not assumed synchronized.");
    }
}
