package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Deterministic, bounded request-flow projection. Only explicit links establish causality. */
public final class Visualization {
    public static final int MAX_SEGMENTS=128,MAX_CALLS=128,MAX_DEPENDENCIES=512,MAX_EDGES=512,
            MAX_INPUT_ROWS=8192,MAX_WARNINGS=128,MAX_ERRORS=64;
    public static final String NOTE="Observed evidence only; absence does not prove a service was not called. "+
            "Durations are local milliseconds; clocks are not assumed synchronized. "+
            "Arrows show explicit boundary direction or observed HTTP call links, not responses. "+
            "Dependency boundaries do not establish remote execution or message receipt.";
    private Visualization(){}
    private static List<JsonNode> rows(JsonNode doc,String name,Set<String>warnings) {
        JsonNode source=doc.path(name);List<JsonNode> rows=new ArrayList<>();
        if(source.isMissingNode())return rows;
        if(!source.isArray()){warnings.add("Malformed "+name+": expected a list; no "+name+" inferred");return rows;}
        if(source.size()>MAX_INPUT_ROWS)warnings.add("Input "+name+" limit reached (8192); evidence is incomplete");
        for(int i=0;i<Math.min(source.size(),MAX_INPUT_ROWS);i++){JsonNode r=source.get(i);if(r.isObject())rows.add(r);else warnings.add("Malformed "+name+" entry omitted");}
        return rows;
    }
    private static ObjectNode base(JsonNode row,String kind) {
        Long status=Evidence.integer(row.get("httpStatus"));if(status==null)status=Evidence.integer(row.path("attributes").get("httpStatus"));
        Double duration=Evidence.number(row.get("durationMs")), nanos=Evidence.number(row.get("durationNanos"));
        if("http-call".equals(kind)&&nanos!=null)duration=nanos/1000000;
        return Json.obj("kind",kind,"label","","service",Evidence.text(row.get("service"),256),
                "serviceInstanceId",Evidence.text(row.get("serviceInstanceId"),256),"segmentId",Evidence.text(row.get("segmentId"),256),
                "segmentKey",Evidence.text(row.get("segmentKey"),4096),"method",Evidence.fieldText(row,"httpMethod",32),
                "path",Evidence.fieldText(row,"path",1024),"status",Evidence.text(row.get("status"),256),
                "httpStatus",status!=null&&status>=100&&status<=599?status:null,"durationMs",duration,
                "entryChannel",Evidence.fieldText(row,"entryChannel",256),"eventCount",Evidence.integer(row.get("eventCount")),
                "truncated",row.path("truncated").isBoolean()&&row.path("truncated").booleanValue());
    }
    private static String label(ObjectNode n) {
        String kind=Json.text(n,"kind");
        if("segment".equals(kind)){
            String route=join(" ",first(Json.text(n,"method"),Json.text(n,"entryChannel")),first(Json.text(n,"path"),Json.text(n,"jobType")));
            return Evidence.text(first(Json.text(n,"service"),"Unknown service")+" · "+first(route,"Segment"),180);
        }
        if("dependency-call".equals(kind)){
            String boundary=Json.text(n,"boundaryKind"),evidence=Json.text(n,"evidenceKind"),prefix;
            if(Dependencies.sqlExecution(n))prefix="SQL query";
            else if(Dependencies.sqlLimit(n))prefix="SQL query limit";
            else if("database-route".equals(boundary))prefix="Database route";
            else if("wrapper-entry".equals(evidence))prefix="cache".equals(boundary)?"Cache access":"Wrapper entry";
            else if("client-boundary".equals(evidence)&&Evidence.oneOf(boundary,"database","cache"))prefix="database".equals(boundary)?"Database call":"Cache call";
            else prefix="messaging".equals(boundary)?"Messaging":"External boundary";
            return Evidence.text(join(" · ",prefix,join(" ",Json.text(n,"system"),first(Json.text(n,"operation"),Json.text(n,"direction"))),
                    first(Json.text(n,"destinationName"),Json.text(n,"destination"))),180);
        }
        String destination=Evidence.s(n,"peer")+Evidence.s(n,"path");
        if(Json.text(n,"destinationName")!=null)destination=Json.text(n,"destinationName")+" · "+destination;
        return Evidence.text(("HTTP "+first(Json.text(n,"method"),"call")+" "+destination).trim(),180);
    }
    private static String first(String... s){for(String v:s)if(v!=null&&!v.isEmpty())return v;return null;}
    private static String join(String sep,String...s){List<String>a=new ArrayList<>();for(String v:s)if(v!=null&&!v.isEmpty())a.add(v);return String.join(sep,a);}
    private static final class Record {String key,identity,signature;JsonNode source;ObjectNode node;}
    private static final class Prepared {List<Record> records=new ArrayList<>();Map<String,ObjectNode> lookup=new HashMap<>();}
    private static Prepared prepare(List<JsonNode> rows,String kind,String field,int limit,Set<String>warnings) {
        Map<String,Integer> counts=new HashMap<>();for(JsonNode r:rows){String k=Evidence.key(r.get(field));counts.put(k,counts.getOrDefault(k,0)+1);}
        List<Record> all=new ArrayList<>();
        for(JsonNode row:rows){
            Record r=new Record();r.key=Evidence.key(row.get(field));r.source=row;
            if("dependency-call".equals(kind)){
                ObjectNode boundary=Dependencies.sanitizeSummary(row);
                r.node=base(Json.obj("service",row.path("service"),"serviceInstanceId",row.path("serviceInstanceId"),"status",boundary.path("status")),kind);
                r.node.setAll(boundary);
            }else r.node=base(row,kind);
            ObjectNode n=r.node;
            if("segment".equals(kind)){
                n.set("parentSpanId",Json.node(Evidence.text(row.get("parentSpanId"),256)));
                if("SCHEDULER".equals(Json.text(n,"entryChannel")))for(String k:new String[]{"jobType","jobId","jobOutcome","jobAuditStatus","jobFailureType","scheduler"})
                    n.set(k,Json.node(Evidence.fieldText(row,k,256)));
            }else{
                n.setAll(Json.obj("callKey",Evidence.text(row.get("callKey"),4096),"ownerId",null,"segmentId",null,
                        "segmentKey",Evidence.text(row.get("ownerSegmentKey"),4096),"downstreamState","not-observed"));
                if("http-call".equals(kind)){
                    n.setAll(Json.obj("peer",Evidence.text(row.get("peer"),256),
                            "propagationStatus",Evidence.text(row.get("propagationStatus"),256),"destinationName",Evidence.text(row.get("destinationName"),128)));
                    // Caller-local ordering evidence, never cross-service clock correlation or a join key.
                    Long started=Evidence.integer(row.has("startedAtMillis")?row.get("startedAtMillis"):row.get("startedAt"));
                    if(started!=null&&started<=9007199254740991L)n.put("startedAtMillis",started);
                    String timing=Evidence.text(row.get("timingBoundary"),256);
                    if(timing!=null)n.put("timingBoundary",timing);
                }
            }
            n.set("label",Json.node(label(n)));
            r.signature=Evidence.canonical("segment".equals(kind)?n:Json.arr(n,Evidence.key(row.get("ownerSegmentKey"))));
            if(r.key==null){warnings.add("Missing or malformed "+field+"; node cannot resolve links by that key");r.identity="missing:"+r.signature;}
            else if(counts.get(r.key)>1){warnings.add("Duplicate "+field+": "+Evidence.text(r.key,256)+"; ambiguous links omitted");r.identity="duplicate:"+Evidence.canonical(Json.arr(r.key,r.signature));}
            else r.identity="key:"+r.key;
            all.add(r);
        }
        all.sort(Comparator.comparing((Record r)->r.key==null?"":r.key).thenComparing(r->r.signature));
        if(all.size()>limit)warnings.add("Visualization "+kind+" limit reached ("+limit+"); evidence is incomplete");
        Prepared out=new Prepared();Map<String,Integer>instances=new HashMap<>();
        for(Record r:all.subList(0,Math.min(limit,all.size()))){
            String id="n_"+("segment".equals(kind)?"s":"http-call".equals(kind)?"c":"d")+"_"+Evidence.hash(r.identity).substring(0,24);
            int count=instances.getOrDefault(id,0)+1;instances.put(id,count);r.node.put("id",id+(count==1?"":"_"+count));out.records.add(r);
            if(r.key!=null&&counts.get(r.key)==1)out.lookup.put(r.key,r.node);
        }return out;
    }
    private static final class Edge implements Comparable<Edge>{
        final String from,to,kind;Edge(String f,String t,String k){from=f;to=t;kind=k;}
        public int compareTo(Edge e){int c=from.compareTo(e.from);if(c==0)c=to.compareTo(e.to);if(c==0)c=kind.compareTo(e.kind);return c;}
        public boolean equals(Object o){return o instanceof Edge&&compareTo((Edge)o)==0;}
        public int hashCode(){return Objects.hash(from,to,kind);}
    }
    public static ObjectNode build(JsonNode journey) {
        Set<String>warnings=new HashSet<>();
        if(journey==null||!journey.isObject()){warnings.add("Malformed journey: expected an object");journey=Json.obj();}
        JsonNode upstream=journey.path("warnings");
        if(upstream.isArray()){
            for(int i=0;i<Math.min(upstream.size(),MAX_INPUT_ROWS);i++){String v=Evidence.text(upstream.get(i),512);warnings.add(v==null?"Malformed upstream warning omitted":v);}
            if(upstream.size()>MAX_INPUT_ROWS)warnings.add("Upstream warning input limit reached; evidence is incomplete");
        }else if(!upstream.isMissingNode())warnings.add("Malformed upstream warnings omitted");
        List<JsonNode>errors=new ArrayList<>();
        for(JsonNode e:rows(journey,"errors",warnings)){
            ObjectNode item=Json.obj("service",Evidence.text(e.get("service"),256),"error",first(Evidence.text(e.get("error"),512),"Unknown upstream error"));
            for(String k:new String[]{"segmentId","serviceInstanceId"}){String v=Evidence.text(e.get(k),256);if(v!=null)item.put(k,v);}errors.add(item);
        }
        errors.sort(Comparator.comparing(Evidence::canonical));
        if(errors.size()>MAX_ERRORS)warnings.add("Error display limit reached (64); additional failures omitted");
        errors=errors.subList(0,Math.min(errors.size(),MAX_ERRORS));
        Prepared segments=prepare(rows(journey,"segments",warnings),"segment","segmentKey",MAX_SEGMENTS,warnings),
                calls=prepare(rows(journey,"httpCalls",warnings),"http-call","callKey",MAX_CALLS,warnings),
                dependencies=prepare(rows(journey,"externalCalls",warnings),"dependency-call","callKey",MAX_DEPENDENCIES,warnings);
        ArrayNode nodes=Json.arr();for(Prepared p:Arrays.asList(segments,calls,dependencies))for(Record r:p.records)nodes.add(r.node);
        if(segments.records.isEmpty())warnings.add("No retained service segments observed; trace may be unrecorded, unavailable or evicted");
        for(Record r:segments.records){
            String id=Json.text(r.node,"id");if(Json.bool(r.node,"truncated"))warnings.add("Truncated segment: "+id);
            if(r.source.path("httpCallsTruncated").isBoolean()&&r.source.path("httpCallsTruncated").booleanValue())warnings.add("HTTP call capture truncated: "+id);
            if(r.source.path("externalCallsTruncated").isBoolean()&&r.source.path("externalCallsTruncated").booleanValue())warnings.add("External boundary extraction limit reached or malformed events: "+id);
            if("ACTIVE".equals(Json.text(r.node,"status")))warnings.add("Active segment may still produce downstream work: "+id);
        }
        Set<Edge> edges=new TreeSet<>();Map<String,String>ownerKeys=new HashMap<>();
        for(Record r:calls.records){
            String ownerKey=Evidence.key(r.source.get("ownerSegmentKey"));ObjectNode owner=segments.lookup.get(ownerKey);String id=Json.text(r.node,"id");
            if(owner==null)warnings.add("Missing or ambiguous owner for HTTP call: "+id+"; ownership not inferred");
            else{r.node.set("ownerId",owner.get("id"));r.node.set("segmentId",owner.get("segmentId"));edges.add(new Edge(Json.text(owner,"id"),id,"outbound"));}
            if(r.key!=null&&calls.lookup.get(r.key)==r.node)ownerKeys.put(r.key,ownerKey);
        }
        for(Record r:dependencies.records){
            if(Dependencies.sqlLimit(r.node))warnings.add((Dependencies.jdbcLimit(r.node)?"JDBC":"Native ClickHouse")+" query capture limit reached; additional query executions were not retained");
            if(Dependencies.sqlExecution(r.node)&&r.node.hasNonNull("sql"))warnings.add("Raw local-development SQL may contain sensitive literals; review before sharing or exporting");
            ObjectNode owner=segments.lookup.get(Evidence.key(r.source.get("ownerSegmentKey")));String id=Json.text(r.node,"id");
            if(owner==null){warnings.add("Missing or ambiguous owner for dependency boundary: "+id+"; ownership not inferred");continue;}
            r.node.set("ownerId",owner.get("id"));r.node.set("segmentId",owner.get("segmentId"));String dir=Json.text(r.node,"direction");
            if("consume".equals(dir))edges.add(new Edge(id,Json.text(owner,"id"),"inbound"));
            else if(Evidence.oneOf(dir,"publish","outbound"))edges.add(new Edge(Json.text(owner,"id"),id,"outbound"));
            else warnings.add("Missing or malformed dependency direction: "+id+"; direction not inferred");
        }
        if(!dependencies.records.isEmpty())warnings.add("Dependency boundaries are local observations only; remote execution is not observed");
        Set<Edge>observed=new TreeSet<>();Map<String,Set<String>>parents=new HashMap<>();
        for(JsonNode link:rows(journey,"links",warnings)){
            String ck=Evidence.key(link.get("fromCallKey"));ObjectNode call=calls.lookup.get(ck),target=segments.lookup.get(Evidence.key(link.get("toSegmentKey")));
            if(call==null||target==null){warnings.add("Observed link has a missing, omitted or ambiguous call/segment target; link omitted");continue;}
            String owner=Evidence.key(link.get("fromSegmentKey"));
            if(!call.hasNonNull("ownerId")||owner==null||!owner.equals(ownerKeys.get(ck))){warnings.add("Observed link has missing or conflicting ownership; link omitted");continue;}
            Edge edge=new Edge(Json.text(call,"id"),Json.text(target,"id"),"observed-call");observed.add(edge);parents.computeIfAbsent(edge.to,k->new HashSet<>()).add(edge.from);
        }
        for(Edge edge:observed)if(parents.get(edge.to).size()>1)warnings.add("Ambiguous parent calls for segment: "+edge.to+"; observed links omitted");else edges.add(edge);
        List<Edge>sorted=new ArrayList<>(edges);sorted.sort(Comparator.comparing((Edge e)->!Evidence.oneOf(e.kind,"outbound","inbound")).thenComparing(e->e));
        if(sorted.size()>MAX_EDGES)warnings.add("Visualization edge limit reached (512); evidence is incomplete");
        sorted=sorted.subList(0,Math.min(sorted.size(),MAX_EDGES));
        Set<String>linkedCalls=new HashSet<>(),linkedSegments=new HashSet<>();
        for(Edge e:sorted)if("observed-call".equals(e.kind)){linkedCalls.add(e.from);linkedSegments.add(e.to);}
        for(Record r:segments.records)if(r.node.hasNonNull("parentSpanId")&&!linkedSegments.contains(Json.text(r.node,"id")))
            warnings.add("Parent call not resolved for segment: "+Json.text(r.node,"id")+"; parentSpanId alone is not an edge");
        for(Record r:calls.records)if(linkedCalls.contains(Json.text(r.node,"id")))r.node.put("downstreamState","observed");else
            warnings.add("Downstream not observed for HTTP call: "+Json.text(r.node,"id")+"; reachability is unknown");
        Map<String,Integer>incoming=new TreeMap<>();Map<String,List<String>>outgoing=new HashMap<>();
        for(JsonNode n:nodes)incoming.put(Json.text(n,"id"),0);
        for(Edge e:sorted){incoming.put(e.to,incoming.get(e.to)+1);outgoing.computeIfAbsent(e.from,k->new ArrayList<>()).add(e.to);}
        List<String>roots=new ArrayList<>();for(Map.Entry<String,Integer> e:incoming.entrySet())if(e.getValue()==0)roots.add(e.getKey());
        Deque<String>queue=new ArrayDeque<>(roots);int visited=0;
        while(!queue.isEmpty()){String source=queue.removeFirst();visited++;for(String target:outgoing.getOrDefault(source,Collections.emptyList())){int count=incoming.get(target)-1;incoming.put(target,count);if(count==0)queue.add(target);}}
        if(visited!=nodes.size())warnings.add("Cyclic retained call links detected; no causal ordering inferred for the cycle");
        boolean partial=Evidence.truthy(journey.get("partial"))||Evidence.oneOf(Json.text(journey,"completeness"),"partial","not-observed")||!warnings.isEmpty()||!errors.isEmpty();
        List<String>messages=new ArrayList<>(warnings);messages.sort(Comparator.comparing((String s)->!s.toLowerCase(Locale.ROOT).contains("limit reached")).thenComparing(s->s.startsWith("Downstream not observed")).thenComparing(s->s));
        if(messages.size()>MAX_WARNINGS){int omitted=messages.size()-(MAX_WARNINGS-1);messages=new ArrayList<>(messages.subList(0,MAX_WARNINGS-1));messages.add("Warning display limit reached; "+omitted+" additional warnings omitted");}
        ArrayNode edgeNodes=Json.arr();for(Edge e:sorted)edgeNodes.add(Json.obj("id","e_"+Evidence.hash(Evidence.canonical(Json.arr(e.from,e.to,e.kind))).substring(0,24),"from",e.from,"to",e.to,"kind",e.kind));
        return Json.obj("schemaVersion",1,"kind","request-flow","traceId",Evidence.text(journey.get("traceId"),256),"partial",partial,
                "completeness",segments.records.isEmpty()?"not-observed":partial?"partial":"observed-only","note",NOTE,"warnings",Json.node(messages),
                "errors",Json.node(errors),"nodes",nodes,"edges",edgeNodes,"roots",Json.node(roots));
    }
    private static String mermaidText(String value){
        String text=first(Evidence.text(value,308),"Unknown").replace('<','‹').replace('>','›').replace('&','＆');StringBuilder b=new StringBuilder();
        text.codePoints().forEach(c->{if(Character.isLetterOrDigit(c)||" /._-·…".indexOf(c)>=0)b.appendCodePoint(c);else b.append('#').append(c).append(';');});return b.toString();
    }
    public static String toMermaid(JsonNode model){
        StringBuilder b=new StringBuilder("flowchart LR\n  %% Retained evidence only; durations are local, not a synchronized timeline.");Set<String>known=new HashSet<>();int count=0;
        for(JsonNode node:Json.rows(model,"nodes")){
            if(count++>=MAX_SEGMENTS+MAX_CALLS+MAX_DEPENDENCIES)break;String id=Json.text(node,"id");
            if(id==null||!id.matches("n_[scd]_[0-9a-f]{24}(?:_[0-9]+)?")||!known.add(id))continue;
            String label=first(Evidence.text(node.get("label"),180),"Unknown"),status=Evidence.text(node.get("status"),32);
            if(status!=null)label+=" | "+status;Long http=Evidence.integer(node.get("httpStatus"));if(http!=null)label+=" | HTTP "+http;
            Double duration=Evidence.number(node.get("durationMs"));if(duration!=null)label+=" | "+String.format(Locale.ROOT,"%.6g",duration).replaceAll("\\.?0+(?=e|$)","")+" ms local";
            if("http-call".equals(Json.text(node,"kind"))&&!"observed".equals(Json.text(node,"downstreamState")))label+=" | downstream not observed";
            if("dependency-call".equals(Json.text(node,"kind")))label+=" | "+first(Evidence.text(node.get("evidenceKind"),32),"boundary evidence unknown")+" | remote execution not observed";
            if(node.path("truncated").isBoolean()&&node.path("truncated").booleanValue())label+=" | TRUNCATED";
            b.append("\n  ").append(id).append("[\"").append(mermaidText(label)).append("\"]");
        }
        count=0;for(JsonNode edge:Json.rows(model,"edges")){
            if(count++>=MAX_EDGES)break;String from=Json.text(edge,"from"),to=Json.text(edge,"to"),kind=Json.text(edge,"kind");
            if(known.contains(from)&&known.contains(to)&&Evidence.oneOf(kind,"outbound","inbound","observed-call"))
                b.append("\n  ").append(from).append(" -->|").append("observed-call".equals(kind)?"observed call":kind).append("| ").append(to);
        }return b.toString();
    }
}
