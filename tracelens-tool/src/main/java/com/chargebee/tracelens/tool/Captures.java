package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.chargebee.tracelens.tool.Json.*;

/** Explicit payload-free evidence. Never infer coverage from absence. */
public final class Captures {
    public static final int MAX_CAPTURES=20, MAX_CAPTURE_BYTES=5*1024*1024, MAX_STORE_BYTES=20*1024*1024,
        MAX_SEGMENTS=16, MAX_EVENTS=100000, MAX_METHODS=2000, MAX_LINES=10000, MAX_DEPENDENCIES=500,
        MAX_COMPARISON_BYTES=64*1024;
    static final String UUID_RE="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    static final String ID_RE="[A-Za-z0-9_-]{1,128}", IDENTIFIER_RE="[A-Za-z_$][A-Za-z0-9_.$]{0,255}",
        SERVICE_RE="[A-Za-z0-9][A-Za-z0-9_.-]{0,63}", SPAN_RE="[0-9a-f]{16}";
    public static final List<String> LIMITATIONS=Collections.unmodifiableList(Arrays.asList(
        "Observed retained evidence only; uninstrumented code, sampling, eviction and silent global event drops may be absent without a flag. Completeness is never proven.",
        "Methods lack descriptors/invocation IDs; overloads can be combined. Lines are class-level compiled coordinates; line shifts are not code deletion.",
        "Nesting is a bounded observed prefix using thread names, not a complete/direct call graph or inferred asynchronous causality.",
        "Only explicit local snapshots persist. They are not atomic across services and do not provide historical coverage or prove dead code or a safe change.",
        "Payloads, headers, raw exception messages, source text, user/context fields and thread names are excluded. Paths, destinations, code identifiers and developer labels can still be sensitive.",
        "Revision is a developer-supplied label, not verified runtime build identity. Use the same scenario, inputs, scope and recording settings for a meaningful comparison.",
        "Local duration samples include waits and instrumentation; one before/after request is not a performance benchmark."));
    private static final List<String> IDENTITY_LIMITATIONS=identityLimitations();
    private static List<String> identityLimitations(){List<String> values=new ArrayList<>(LIMITATIONS);
        values.set(1,"Captured JVM descriptors distinguish overloads; legacy method names remain less precise. Invocation IDs are absent. Lines are class-level compiled coordinates; line shifts are not code deletion.");
        values.set(2,"Nesting is a bounded observed prefix using runtime thread IDs when captured, otherwise thread names; neither proves a complete/direct call graph or asynchronous causality.");
        return Collections.unmodifiableList(values);}
    private Captures() {}
    private static final Map<Integer,String> CASE_FOLD=caseFoldMappings();
    private static Map<Integer,String> caseFoldMappings() {
        try(java.io.InputStream input=Captures.class.getResourceAsStream("/unicode-casefold.json")){
            if(input==null)throw new IllegalStateException("Missing Unicode case-fold data");
            Map<Integer,String> mappings=new HashMap<>();
            MAPPER.readTree(input).path("mappings").fields().forEachRemaining(e->mappings.put(Integer.valueOf(e.getKey()),e.getValue().asText()));
            return Collections.unmodifiableMap(mappings);
        }catch(java.io.IOException e){throw new IllegalStateException("Invalid Unicode case-fold data",e);}
    }
    /** Unicode full folding used by Python's literal text filters, not a regex. */
    static String casefold(String value) {
        StringBuilder folded=new StringBuilder();
        for(int offset=0;offset<value.length();){
            int cp=value.codePointAt(offset);offset+=Character.charCount(cp);
            String replacement=CASE_FOLD.get(cp);
            folded.append(replacement==null?new String(Character.toChars(cp)).toLowerCase(Locale.ROOT):replacement);
        }
        return folded.toString();
    }
    static ToolException invalid(String message) { return new ToolException(400,message); }
    static String match(JsonNode value,String regex) { return value!=null&&value.isTextual()&&value.textValue().matches(regex)?value.textValue():null; }
    static String match(String value,String regex) { return value!=null&&value.matches(regex)?value:null; }
    static Long integer(JsonNode value,long min,long max) { return value!=null&&value.isIntegralNumber()&&value.canConvertToLong()&&value.longValue()>=min&&value.longValue()<=max?value.longValue():null; }
    static String methodDescriptor(JsonNode value) {
        if(value==null||!value.isTextual())return null;String descriptor=value.textValue();int length=descriptor.length();
        if(length<3||length>2048||descriptor.charAt(0)!='('||!printable(descriptor))return null;int position=1;
        while(position<length&&descriptor.charAt(position)!=')'){position=descriptorType(descriptor,position);if(position<0)return null;}
        if(position>=length-1)return null;position++;
        position=descriptor.charAt(position)=='V'?position+1:descriptorType(descriptor,position);
        return position==length?descriptor:null;
    }
    private static int descriptorType(String value,int position) {
        int dimensions=0;while(position<value.length()&&value.charAt(position)=='['){position++;if(++dimensions>255)return -1;}
        if(position>=value.length())return -1;char kind=value.charAt(position++);if("BCDFIJSZ".indexOf(kind)>=0)return position;
        if(kind!='L')return -1;boolean first=true;
        while(position<value.length()){int cp=value.codePointAt(position);position+=Character.charCount(cp);
            if(cp==';')return first?-1:position;
            if(cp=='/'){if(first)return -1;first=true;continue;}
            if(first?!Character.isJavaIdentifierStart(cp):!Character.isJavaIdentifierPart(cp))return -1;first=false;
        }return -1;
    }
    static boolean printable(String value) {
        if(value==null) return false;
        for(int i=0;i<value.length();) { int c=value.codePointAt(i),type=Character.getType(c); i+=Character.charCount(c);
            if(Character.isISOControl(c)||type==Character.FORMAT||type==Character.SURROGATE||type==Character.PRIVATE_USE||type==Character.UNASSIGNED||type==Character.LINE_SEPARATOR||type==Character.PARAGRAPH_SEPARATOR||(type==Character.SPACE_SEPARATOR&&c!=' ')) return false;
        } return true;
    }
    static String strip(String value) {
        int first=0,last=value.length();
        while(first<last){int cp=value.codePointAt(first);if(!Character.isWhitespace(cp)&&!Character.isSpaceChar(cp))break;first+=Character.charCount(cp);}
        while(last>first){int cp=value.codePointBefore(last);if(!Character.isWhitespace(cp)&&!Character.isSpaceChar(cp))break;last-=Character.charCount(cp);}
        return value.substring(first,last);
    }
    public static void validateCaptureInputs(String traceId,String label,String revision) {
        if(match(traceId,ID_RE)==null) throw invalid("trace_id must contain 1–128 ASCII letters, digits, underscores or hyphens");
        if(label==null||label.codePointCount(0,label.length())<1||label.codePointCount(0,label.length())>80||label.trim().isEmpty()||!printable(label)) throw invalid("capture label must contain 1–80 printable characters and not be blank");
        if(revision!=null&&match(revision,"[A-Za-z0-9][A-Za-z0-9_./-]{0,127}")==null) throw invalid("revision must contain 1–128 ASCII letters, digits, dots, slashes, underscores or hyphens and start with a letter or digit");
    }
    /** Stable ASCII canonical serialization for quotas and counter identities. */
    static byte[] bytes(JsonNode value) {
        StringBuilder out=new StringBuilder(); canonical(value,out); return out.toString().getBytes(StandardCharsets.US_ASCII);
    }
    static String canonical(JsonNode value) { return new String(bytes(value),StandardCharsets.US_ASCII); }
    private static void canonical(JsonNode value,StringBuilder out) {
        if(value==null||value.isNull()) { out.append("null"); return; }
        if(value.isObject()) { out.append('{'); List<String> keys=new ArrayList<>(); value.fieldNames().forEachRemaining(keys::add); Collections.sort(keys);
            boolean first=true; for(String key:keys) { if(!first)out.append(','); first=false; quote(key,out);out.append(':');canonical(value.get(key),out); } out.append('}');
        } else if(value.isArray()) { out.append('['); boolean first=true; for(JsonNode child:value) { if(!first)out.append(',');first=false;canonical(child,out); }out.append(']'); }
        else if(value.isTextual()) quote(value.textValue(),out);
        else if(value.isNumber()) { if(!Double.isFinite(value.doubleValue()))throw invalid("JSON must contain finite numeric data");out.append(value); }
        else if(value.isBoolean())out.append(value.booleanValue()?"true":"false");
        else throw invalid("Unsupported JSON value");
    }
    private static void quote(String value,StringBuilder out) {
        out.append('"'); for(int i=0;i<value.length();i++) { char c=value.charAt(i);
            if(c=='"'||c=='\\')out.append('\\').append(c);
            else if(c<32||c>126)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);
        }out.append('"');
    }
    static ObjectNode select(JsonNode value,String...keys) { ObjectNode result=obj();for(String key:keys)result.set(key,value==null?node(null):value.path(key).isMissingNode()?node(null):value.get(key));return result; }
    static String path(JsonNode value) {
        if(value==null||!value.isTextual())return null;String s=value.textValue();if(s.length()>2048||!s.startsWith("/"))return null;
        s=s.split("[?#]",2)[0]; if(s.length()>512||!s.matches("[!-~]*"))return null;return s;
    }
    static JsonNode statusCode(JsonNode value) { if(value!=null&&value.isTextual()&&value.textValue().matches("[1-5][0-9]{2}"))return node(Integer.valueOf(value.textValue())); Long n=integer(value,100,599);return n==null?node(null):node(n.intValue()); }
    static JsonNode first(JsonNode row,JsonNode attrs,String key) { JsonNode v=row.get(key);return truthy(v)?v:attrs.get(key); }
    static ObjectNode request(JsonNode row) {
        JsonNode attrs=row.path("attributes");String method=text(first(row,attrs,"httpMethod"));String status=text(row,"status");
        JsonNode d=row.get("durationMs"); Object duration=d!=null&&d.isNumber()&&Double.isFinite(d.doubleValue())&&d.doubleValue()>=0&&d.doubleValue()<=1e15?d:null;
        return obj("method",allowed(method,"GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS","TRACE","CONNECT"),"path",path(first(row,attrs,"path")),
            "status",allowed(status,"ACTIVE","COMPLETED","ERROR","FAILED","TIMED_OUT","TIMEOUT"),"httpStatus",statusCode(first(row,attrs,"httpStatus")),"durationMs",duration);
    }
    static String text(JsonNode v) { return v!=null&&v.isTextual()?v.textValue():null; }
    static String text(JsonNode v,String key) { return Json.text(v,key); }
    static String allowed(String v,String...values) { return v!=null&&Arrays.asList(values).contains(v)?v:null; }
    static boolean truthy(JsonNode value){return value!=null&&!value.isNull()&&!value.isMissingNode()&&(value.isBoolean()?value.booleanValue():value.isNumber()?value.doubleValue()!=0:value.isTextual()?!value.textValue().isEmpty():value.size()>0);}
    static ObjectNode dependency(JsonNode event) {
        JsonNode full=Dependencies.boundarySummary(event);if(full==null||full.isNull())return null;
        ObjectNode result=select(full,"boundaryKind","system","direction","destination","operation","status","evidenceKind","errorType");
        // Compact captures remain SQL-free, unlike explicit full viewer snapshots.
        if(Dependencies.sqlExecution(full))result.setAll(select(full,"durationMs","batchSize","sqlSource","sqlTruncated"));
        if(Dependencies.sqlLimit(full))result.set("queryLimit",full.get("queryLimit"));
        return result;
    }
    static ObjectNode httpDependency(JsonNode row) {
        if(row==null||!row.isObject())return null;
        return obj("boundaryKind","http","system","http","direction","outbound","destination",Dependencies.destination(text(row,"peer")),
            "operation",request(row).get("method"),"path",path(row.get("path")),"httpStatus",statusCode(row.get("httpStatus")),
            "status",allowed(text(row,"status"),"ACTIVE","COMPLETED","ERROR","ATTEMPTED"),"evidenceKind","client-boundary","errorType",match(row.get("errorType"),IDENTIFIER_RE));
    }
    private static void count(Map<String,Integer> values,JsonNode key,int max,String warning,Set<String> warnings) {
        String identity=canonical(key);if(values.containsKey(identity)||values.size()<max)values.put(identity,values.getOrDefault(identity,0)+1);else warnings.add(warning);
    }
    private static ArrayNode counterRows(Map<String,Integer> values) {
        ArrayNode result=arr();for(Map.Entry<String,Integer> e:new TreeMap<>(values).entrySet()) { ObjectNode row=(ObjectNode)parse(e.getKey());row.put("count",e.getValue());result.add(row); }return result;
    }
    private static ArrayNode nestingRows(Map<String,Integer> values) {
        // Python sorts the semantic tuple, not alphabetical JSON field names.
        // In particular, parent identity must sort before the child's identity.
        ArrayNode rows=counterRows(values);List<JsonNode> sorted=new ArrayList<>();rows.forEach(sorted::add);
        sorted.sort(Comparator.comparing(row->canonical(arr(row.get("parentClass"),row.get("parentMethod"),row.get("className"),row.get("methodName")))));
        return (ArrayNode)node(sorted);
    }
    public static ObjectNode projectSegment(String service,JsonNode row,int remainingEvents) {
        return projectSegment(service,row,remainingEvents,Execution.MODEL_MAX_BYTES);
    }
    static ObjectNode projectSegment(String service,JsonNode row,int remainingEvents,int nestingModelBytes) {
        Set<String>warnings=new TreeSet<>();String parentSegment=match(row.get("parentSegmentId"),UUID_RE),parentSpan=match(row.get("parentSpanId"),SPAN_RE);
        if(row.hasNonNull("parentSegmentId")&&parentSegment==null||row.hasNonNull("parentSpanId")&&parentSpan==null)warnings.add("invalid_parent_identity");
        JsonNode raw=row.get("events");if(raw==null||!raw.isArray()){warnings.add("missing_events");raw=arr();}
        int available=raw.size(),processed=Math.min(available,Math.max(0,remainingEvents));ArrayNode events=arr();for(int i=0;i<processed;i++)events.add(raw.get(i));
        if(available>processed)warnings.add("event_processing_limit");if(integer(row.get("eventCount"),0,Long.MAX_VALUE)==null||row.path("eventCount").longValue()!=available)warnings.add("event_count_snapshot_mismatch");
        if(bool(row,"truncated"))warnings.add("agent_segment_truncated");if(bool(row,"httpCallsTruncated"))warnings.add("agent_http_calls_truncated");if("ACTIVE".equals(text(row,"status")))warnings.add("active_snapshot");
        Map<String,Integer>methods=new TreeMap<>(),lines=new TreeMap<>(),exceptions=new TreeMap<>(),dependencies=new TreeMap<>(),nesting=new TreeMap<>();
        Long previous=null;
        for(JsonNode e:events) {
            if(!e.isObject()){warnings.add("malformed_event");continue;}Long seq=integer(e.get("seq"),1,Long.MAX_VALUE);
            if(seq==null||previous==null&&seq!=1||previous!=null&&seq!=previous+1)warnings.add("sequence_missing_gap_or_reordering");previous=seq;
            String cls=match(e.get("className"),IDENTIFIER_RE),method=match(e.get("methodName"),IDENTIFIER_RE),kind=text(e,"type");
            if(Arrays.asList("METHOD_ENTER","METHOD_EXIT","EXCEPTION").contains(kind)&&(cls==null||method==null))warnings.add("invalid_code_identity_omitted");
            Long line=integer(e.get("line"),1,Integer.MAX_VALUE);if("LINE".equals(kind)&&(cls==null||line==null))warnings.add("invalid_line_identity_omitted");
            if("METHOD_ENTER".equals(kind)&&cls!=null&&method!=null){
                String descriptor=methodDescriptor(e.get("methodDescriptor"));
                if(e.hasNonNull("methodDescriptor")&&descriptor==null)warnings.add("invalid_method_descriptor_omitted");
                else{ObjectNode key=obj("className",cls,"methodName",method);if(descriptor!=null)key.put("methodDescriptor",descriptor);count(methods,key,MAX_METHODS,"method_identity_limit",warnings);}
            }
            else if("LINE".equals(kind)&&cls!=null&&line!=null)count(lines,obj("className",cls,"line",line),MAX_LINES,"line_identity_limit",warnings);
            else if("EXCEPTION".equals(kind)) { String ex=match(e.path("attributes").get("exceptionType"),IDENTIFIER_RE);if(ex==null)warnings.add("invalid_exception_type_omitted");else count(exceptions,obj("exceptionType",ex),MAX_METHODS,"exception_identity_limit",warnings); }
            ObjectNode dep=dependency(e);if(dep!=null){count(dependencies,dep,MAX_DEPENDENCIES,"dependency_identity_limit",warnings);
                if(Dependencies.jdbcLimit(dep))warnings.add("jdbc_query_capture_limit");}
        }
        JsonNode calls=row.path("httpCalls");if(calls.isMissingNode())calls=arr();else if(!calls.isArray()){calls=arr();warnings.add("malformed_http_calls");}
        if(calls.size()>MAX_DEPENDENCIES)warnings.add("http_processing_limit");for(int i=0;i<Math.min(calls.size(),MAX_DEPENDENCIES);i++){ObjectNode d=httpDependency(calls.get(i));if(d!=null)count(dependencies,d,MAX_DEPENDENCIES,"dependency_identity_limit",warnings);}
        JsonNode model=Execution.build(Dependencies.withoutSql(obj("events",events,"eventCount",events.size())),500,nestingModelBytes);
        for(JsonNode warning:model.path("warnings"))warnings.add("nesting_"+text(warning,"code"));
        Map<String,JsonNode>nodes=new HashMap<>();for(JsonNode n:model.path("nodes"))if("invocation".equals(text(n,"kind"))&&!n.path("methodIdentityInvalid").asBoolean())nodes.put(text(n,"id"),n);
        for(JsonNode n:nodes.values()) { JsonNode p=nodes.get(text(n,"parentId"));if(p==null)continue;
            ObjectNode key=obj("parentClass",match(p.get("className"),IDENTIFIER_RE),"parentMethod",match(p.get("methodName"),IDENTIFIER_RE),"className",match(n.get("className"),IDENTIFIER_RE),"methodName",match(n.get("methodName"),IDENTIFIER_RE));
            if(p.hasNonNull("methodDescriptor"))key.set("parentMethodDescriptor",p.get("methodDescriptor"));
            if(n.hasNonNull("methodDescriptor"))key.set("methodDescriptor",n.get("methodDescriptor"));
            boolean valid=true;for(JsonNode v:key)if(v.isNull())valid=false;if(valid)count(nesting,key,500,"nesting_identity_limit",warnings);
        }
        ObjectNode projected=obj("service",service,"serviceInstanceId",row.get("serviceInstanceId"),"segmentId",row.get("segmentId"),"spanId",match(row.get("spanId"),SPAN_RE),
            "parentSpanId",parentSpan,"parentSegmentId",parentSegment,"request",request(row),"eventsAvailable",available,"eventsProcessed",processed,
            "methods",counterRows(methods),"lines",counterRows(lines),"exceptions",counterRows(exceptions),"nesting",nestingRows(nesting),"dependencies",counterRows(dependencies),"warnings",warnings,"partial",!warnings.isEmpty());
        if(row.path("recordingManifest").isObject())projected.set("recordingManifest",recordingManifest(row.path("recordingManifest")));
        return projected;
    }
    static ObjectNode recordingManifest(JsonNode source){
        ObjectNode result=obj("schemaVersion",1);
        for(String key:new String[]{"agentBuildId","configurationFingerprint"}){String value=match(source.get(key),"[0-9a-f]{64}");if(value!=null)result.put(key,value);}
        String version=text(source,"applicationVersion");if(version!=null&&version.length()<=256&&printable(version))result.put("applicationVersion",version);
        return result;
    }
    public static ObjectNode buildCapture(Federation discovery,String traceId,String label,String revision) {
        validateCaptureInputs(traceId,label,revision);if(discovery.services.size()<1||discovery.services.size()>32)throw invalid("capture requires a finite configured Federation");
        for(String name:discovery.services.keySet())if(match(name,SERVICE_RE)==null)throw invalid("capture requires valid configured service names");
        ObjectNode capture=base(UUID.randomUUID().toString(),traceId,label,revision,timestamp(Instant.now()));ArrayNode segments=(ArrayNode)capture.get("segments");
        Set<String>warnings=new TreeSet<>();int remaining=MAX_EVENTS;Reader reader=new Reader();
        for(String service:new TreeSet<>(discovery.services.keySet()))try {
            DebugClient client=discovery.clients.get(service);JsonNode found;
            try{found=reader.read(client,"/find-by-trace-id/"+traceId);}catch(ToolException e){if(e.status!=404)throw e;found=reader.read(client,"/traces");}
            if(!found.path("traces").isArray())throw invalid("capture_invalid_summaries");
            if(truthy(found.get("limited"))||truthy(found.get("partial")))warnings.add(service+":summary_incomplete");Set<String>seen=new HashSet<>();
            for(JsonNode summary:found.path("traces")) {
                if(!summary.isObject()||!traceId.equals(text(summary,"traceId")))continue;
                if(segments.size()>=MAX_SEGMENTS){warnings.add("segment_limit");break;}
                String id=match(summary.get("segmentId"),UUID_RE),instance=match(summary.get("serviceInstanceId"),ID_RE);
                if(id==null||instance==null){warnings.add(service+":invalid_segment_identity");continue;}
                if(!seen.add(id+":"+instance)){warnings.add(service+":duplicate_segment_summary");continue;}
                try { JsonNode row=reader.read(client,"/export/"+id+"?evict=false");
                    if(!traceId.equals(text(row,"traceId"))||!id.equals(text(row,"segmentId"))||!instance.equals(text(row,"serviceInstanceId")))throw invalid("capture_instance_or_trace_changed");
                    ObjectNode projected=projectSegment(service,row,remaining);remaining-=projected.path("eventsProcessed").intValue();segments.add(projected);
                    if(bytes(capture).length>MAX_CAPTURE_BYTES-16384){segments.remove(segments.size()-1);warnings.add("capture_byte_limit");}
                }catch(RuntimeException e){warnings.add(service+":segment_unavailable_or_invalid");}
            }
        }catch(RuntimeException e){warnings.add(service+":discovery_unavailable_or_invalid");}
        finish(capture,warnings);return capture;
    }
    private static class Reader {
        int remaining=32*1024*1024,requests;
        JsonNode read(DebugClient client,String path) {
            if(requests++>=64||remaining<=0)throw invalid("capture_network_budget");int cap=Math.min(8*1024*1024,remaining);
            JsonNode result;try{result=client.request("GET",path,cap,5000);}finally{remaining-=client.lastResponseBytes();}
            if(!result.isObject())throw invalid("capture_invalid_document");
            // Keep the injectable client seam bounded, too. Real clients report
            // their exact consumed wire bytes, including oversize failures.
            if(client.lastResponseBytes()==0){int size=bytes(result).length;remaining-=size;if(size>cap)throw invalid("capture_response_byte_limit");}
            return result;
        }
    }
    static ObjectNode base(String id,String trace,String label,String revision,String created) { return obj("schemaVersion",1,"kind","request-capture","captureId",id,"label",label,"revision",revision,"traceId",trace,"createdAt",created,"segments",arr(),"warnings",arr(),"partial",false,"limitations",LIMITATIONS); }
    static String timestamp(Instant instant) {
        OffsetDateTime time=instant.atOffset(ZoneOffset.UTC);
        if(time.getYear()<1||time.getYear()>9999)throw invalid("Snapshot capture time cannot be represented in a CLI capture.");
        String value=time.format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss",Locale.ROOT));int micros=time.getNano()/1000;
        return value+(micros==0?"":String.format(Locale.ROOT,".%06d",micros))+"+00:00";
    }
    static void finish(ObjectNode capture,Set<String>warnings) {
        if(capture.path("segments").size()==0)throw invalid("No retained segments could be captured; reproduce the request and check service availability and instance identity");
        int roots=0;boolean partial=false;for(JsonNode s:capture.path("segments")){if(s.path("parentSpanId").isNull()&&s.path("parentSegmentId").isNull())roots++;partial|=bool(s,"partial");}
        if(roots!=1)warnings.add("root_missing_or_ambiguous");capture.set("warnings",node(warnings));capture.put("partial",partial||!warnings.isEmpty());
        if(hasDescriptors(capture))capture.set("limitations",node(IDENTITY_LIMITATIONS));
    }
    private static boolean hasDescriptors(JsonNode capture){for(JsonNode segment:capture.path("segments"))for(JsonNode method:segment.path("methods"))if(method.hasNonNull("methodDescriptor"))return true;return false;}
    static ObjectNode metadata(JsonNode capture,int size) { ObjectNode result=select(capture,"captureId","label","revision","traceId","createdAt","partial");result.put("segmentCount",capture.path("segments").size());result.put("sizeBytes",size);return result; }
    static void fields(JsonNode v,String message,String...expected) {
        if(v==null||!v.isObject())throw invalid(message);Set<String> actual=new HashSet<>();v.fieldNames().forEachRemaining(actual::add);if(!actual.equals(new HashSet<>(Arrays.asList(expected))))throw invalid(message);
    }
    private static void warnings(JsonNode value) { if(value==null||!value.isArray()||value.size()>256)throw invalid("invalid capture warnings");for(JsonNode v:value)if(match(v,"[A-Za-z0-9_.:-]{1,160}")==null)throw invalid("invalid capture warnings"); }
    private static void segmentFields(JsonNode segment){
        List<String> keys=new ArrayList<>(Arrays.asList("service","serviceInstanceId","segmentId","spanId","parentSpanId","parentSegmentId","request","eventsAvailable","eventsProcessed","methods","lines","exceptions","nesting","dependencies","warnings","partial"));
        if(segment.has("recordingManifest")){
            JsonNode manifest=segment.get("recordingManifest");List<String> fields=new ArrayList<>(Collections.singletonList("schemaVersion"));
            if(integer(manifest.get("schemaVersion"),1,1)==null)throw invalid("invalid recording manifest");
            for(String key:new String[]{"applicationVersion","agentBuildId","configurationFingerprint"})if(manifest.has(key)){
                String value=text(manifest,key);if("applicationVersion".equals(key)?value==null||value.length()>256||!printable(value):match(value,"[0-9a-f]{64}")==null)throw invalid("invalid recording manifest");fields.add(key);
            }
            fields(manifest,"invalid recording manifest",fields.toArray(new String[0]));keys.add("recordingManifest");
        }
        if(segment.has("analysisCoverage")){
            JsonNode coverage=segment.get("analysisCoverage");fields(coverage,"invalid analysis coverage","savedEvents","retainedEvents","rawBytesRead","complete");
            Long saved=integer(coverage.get("savedEvents"),0,Long.MAX_VALUE),retained=integer(coverage.get("retainedEvents"),0,Long.MAX_VALUE),bytes=integer(coverage.get("rawBytesRead"),0,Long.MAX_VALUE);
            if(saved==null||retained==null||bytes==null||retained>saved||!coverage.path("complete").isBoolean())throw invalid("invalid analysis coverage");keys.add("analysisCoverage");
        }
        fields(segment,"invalid capture segment schema",keys.toArray(new String[0]));
    }
    public static void validateCapture(JsonNode c) {
        fields(c,"invalid capture schema","schemaVersion","kind","captureId","label","revision","traceId","createdAt","segments","warnings","partial","limitations");
        if(integer(c.get("schemaVersion"),1,1)==null||!"request-capture".equals(text(c,"kind")))throw invalid("invalid capture schema");
        if(!c.path("revision").isNull()&&!c.path("revision").isTextual())throw invalid("invalid capture revision");
        validateCaptureInputs(text(c,"traceId"),text(c,"label"),text(c,"revision"));if(match(c.get("captureId"),UUID_RE)==null)throw invalid("invalid capture id");
        String stamp=text(c,"createdAt");try{if(stamp==null||stamp.length()>40)throw new IllegalArgumentException();OffsetDateTime.parse(stamp);}catch(Exception e){throw invalid("invalid capture timestamp");}
        if(!node(LIMITATIONS).equals(c.get("limitations"))&&!node(IDENTITY_LIMITATIONS).equals(c.get("limitations"))||!c.path("partial").isBoolean())throw invalid("invalid capture evidence limits");
        warnings(c.get("warnings"));JsonNode segments=c.get("segments");if(!segments.isArray()||segments.size()<1||segments.size()>MAX_SEGMENTS)throw invalid("invalid capture segments");
        long total=0;boolean partial=c.path("warnings").size()>0;
        for(JsonNode s:segments) {
            segmentFields(s);
            if(match(s.get("service"),SERVICE_RE)==null||match(s.get("serviceInstanceId"),ID_RE)==null||match(s.get("segmentId"),UUID_RE)==null)throw invalid("invalid capture segment identity");
            for(String f:Arrays.asList("spanId","parentSpanId"))if(!s.path(f).isNull()&&match(s.get(f),SPAN_RE)==null)throw invalid("invalid capture span identity");
            if(!s.path("parentSegmentId").isNull()&&match(s.get("parentSegmentId"),UUID_RE)==null)throw invalid("invalid capture parent segment identity");
            JsonNode req=s.get("request");fields(req,"invalid capture request metadata","method","path","status","httpStatus","durationMs");ObjectNode check=copy(req);check.set("httpMethod",req.get("method"));if(!request(check).equals(req))throw invalid("invalid capture request metadata");
            Long avail=integer(s.get("eventsAvailable"),0,Long.MAX_VALUE),processed=integer(s.get("eventsProcessed"),0,MAX_EVENTS);
            if(avail==null||processed==null||processed>avail)throw invalid("invalid capture event counts");total+=processed;
            warnings(s.get("warnings"));if(!s.path("partial").isBoolean()||bool(s,"partial")!=(s.path("warnings").size()>0))throw invalid("invalid capture partial flag");partial|=bool(s,"partial");
            evidence(s,"methods",MAX_METHODS,"className","methodName");evidence(s,"lines",MAX_LINES,"className","line");evidence(s,"exceptions",MAX_METHODS,"exceptionType");evidence(s,"nesting",500,"parentClass","parentMethod","className","methodName");
            JsonNode deps=s.get("dependencies");if(!deps.isArray()||deps.size()>MAX_DEPENDENCIES)throw invalid("capture dependency limit");
            for(JsonNode d:deps){if(!d.isObject()||integer(d.get("count"),1,MAX_EVENTS+MAX_DEPENDENCIES)==null)throw invalid("invalid capture dependency");
                ObjectNode raw=copy(d);raw.remove("count");ObjectNode projected;
                if("http".equals(text(raw,"boundaryKind"))){ObjectNode input=copy(raw);input.set("peer",raw.get("destination"));input.set("httpMethod",raw.get("operation"));projected=httpDependency(input);}
                else if("database-route".equals(text(raw,"boundaryKind")))projected=dependency(obj("type","DB_ROUTE","attributes",obj("route",raw.get("destination"))));
                else projected=dependency(obj("type","EXTERNAL_CALL","attributes",raw));
                if(!raw.equals(projected))throw invalid("invalid capture dependency metadata");
            }
        }
        if(total>MAX_EVENTS)throw invalid("capture event processing limit");if(bool(c,"partial")!=partial)throw invalid("invalid capture partial flag");if(bytes(c).length>MAX_CAPTURE_BYTES)throw invalid("capture exceeds 5 MiB");
    }
    private static void evidence(JsonNode s,String category,int max,String...keys) {
        JsonNode values=s.get(category);if(!values.isArray()||values.size()>max)throw invalid("capture evidence limit");String[] expected=Arrays.copyOf(keys,keys.length+1);expected[keys.length]="count";
        for(JsonNode v:values){
            List<String> accepted=new ArrayList<>(Arrays.asList(expected));
            for(String descriptor:new String[]{"methodDescriptor","parentMethodDescriptor"})if(v.has(descriptor)){
                if(!"nesting".equals(category)&&!("methods".equals(category)&&"methodDescriptor".equals(descriptor))||methodDescriptor(v.get(descriptor))==null)throw invalid("invalid capture method descriptor");
                accepted.add(descriptor);
            }
            fields(v,"invalid capture evidence",accepted.toArray(new String[0]));if(integer(v.get("count"),1,MAX_EVENTS)==null)throw invalid("invalid capture evidence");for(String key:keys)if("line".equals(key)?integer(v.get(key),1,Integer.MAX_VALUE)==null:match(v.get(key),IDENTIFIER_RE)==null)throw invalid("invalid capture code identity");
        }
    }
    private static Map<String,Long> aggregate(JsonNode capture,String category) {
        Map<String,Long> result=new TreeMap<>();for(JsonNode s:capture.path("segments")) {
            if("outcomes".equals(category)){ObjectNode key=copy(s.path("request"));key.remove("durationMs");key.set("service",s.get("service"));String k=canonical(key);result.put(k,result.getOrDefault(k,0L)+1);continue;}
            for(JsonNode row:s.path(category)){ObjectNode key=copy(row);key.remove("count");key.set("service",s.get("service"));String k=canonical(key);result.put(k,result.getOrDefault(k,0L)+row.path("count").longValue());}
        }return result;
    }
    private static ObjectNode delta(Map<String,Long>b,Map<String,Long>a) {
        ObjectNode result=obj("added",arr(),"noLongerObserved",arr(),"countChanged",arr(),"totals",obj("added",0,"noLongerObserved",0,"countChanged",0),"unchangedIdentities",0);
        Set<String>keys=new TreeSet<>(b.keySet());keys.addAll(a.keySet());int unchanged=0;
        for(String k:keys){String type=!b.containsKey(k)?"added":!a.containsKey(k)?"noLongerObserved":!a.get(k).equals(b.get(k))?"countChanged":null;
            if(type==null){unchanged++;continue;}ObjectNode totals=(ObjectNode)result.get("totals");totals.put(type,totals.path(type).intValue()+1);ArrayNode list=(ArrayNode)result.get(type);
            if(list.size()<200){ObjectNode row=copy(parse(k));row.put("beforeCount",b.getOrDefault(k,0L));row.put("afterCount",a.getOrDefault(k,0L));list.add(row);}
        }result.put("unchangedIdentities",unchanged);return result;
    }
    static final String[] CATEGORIES={"methods","lines","nesting","dependencies","exceptions","outcomes"}, DELTAS={"added","noLongerObserved","countChanged"};
    public static ObjectNode compareCaptures(JsonNode before,JsonNode after) {
        validateCapture(before);validateCapture(after);Set<String>warnings=new TreeSet<>(Arrays.asList("scope_and_inputs_not_verified","observations_do_not_prove_coverage"));
        if(bool(before,"partial")||bool(after,"partial"))warnings.add("partial_capture_evidence");if(!before.hasNonNull("revision")||!after.hasNonNull("revision"))warnings.add("runtime_build_labels_missing");
        Map<String,Integer>bs=new TreeMap<>(),as=new TreeMap<>();Set<String>bp=new TreeSet<>(),ap=new TreeSet<>();
        for(JsonNode c:Arrays.asList(before,after)){Map<String,Integer>counts=c==before?bs:as;Set<String>paths=c==before?bp:ap;for(JsonNode s:c.path("segments")){String service=text(s,"service");counts.put(service,counts.getOrDefault(service,0)+1);paths.add(canonical(obj("service",service,"method",s.path("request").get("method"),"path",s.path("request").get("path"))));}}
        if(!bs.equals(as))warnings.add("service_or_segment_counts_differ");if(!bp.equals(ap))warnings.add("request_paths_or_methods_differ");
        ObjectNode result=obj("schemaVersion",1,"kind","capture-comparison","completeness","observed-only","before",metadata(before,bytes(before).length),"after",metadata(after,bytes(after).length),
            "warnings",warnings,"limitations",hasDescriptors(before)||hasDescriptors(after)?IDENTITY_LIMITATIONS:LIMITATIONS,"partial",bool(before,"partial")||bool(after,"partial"),"limited",false,"maxBytes",MAX_COMPARISON_BYTES,"evidenceWarnings",obj(),"durationSamples",obj());
        for(String side:Arrays.asList("before","after")){JsonNode c=side.equals("before")?before:after;ArrayNode segmentWarnings=arr(),durations=arr();
            for(JsonNode s:c.path("segments")){if(s.path("warnings").size()>0)segmentWarnings.add(obj("service",s.get("service"),"segmentId",s.get("segmentId"),"warnings",slice(s.path("warnings"),4),"warningsOmitted",Math.max(0,s.path("warnings").size()-4)));ObjectNode d=copy(s.path("request"));d.set("service",s.get("service"));durations.add(d);}
            ((ObjectNode)result.get("evidenceWarnings")).set(side,obj("warnings",slice(c.path("warnings"),8),"warningsOmitted",Math.max(0,c.path("warnings").size()-8),"segments",segmentWarnings));
            ((ObjectNode)result.get("durationSamples")).set(side,durations);
        }
        for(String cat:CATEGORIES)result.set(cat,delta(aggregate(before,cat),aggregate(after,cat)));finishComparison(result);pruneComparison(result,MAX_COMPARISON_BYTES);return result;
    }
    static ArrayNode slice(JsonNode values,int cap){ArrayNode a=arr();for(int i=0;i<Math.min(values.size(),cap);i++)a.add(values.get(i));return a;}
    static void finishComparison(ObjectNode result){boolean limited=false;for(String cat:CATEGORIES){ObjectNode r=(ObjectNode)result.get(cat);int returned=0,total=0;for(String k:DELTAS){returned+=r.path(k).size();total+=r.path("totals").path(k).intValue();}r.put("returned",returned);r.put("omitted",total-returned);limited|=total>returned;}for(JsonNode details:result.path("evidenceWarnings")){limited|=details.path("warningsOmitted").asInt()>0;for(JsonNode s:details.path("segments"))limited|=s.path("warningsOmitted").asInt()>0;}result.put("limited",limited);}
    static void pruneComparison(ObjectNode result,int max){pruneComparison(result,max,true);}
    static void pruneComparison(ObjectNode result,int max,boolean quarter){
        while(bytes(result).length>max){
            ArrayNode largest=null;String largestKey="";
            for(String cat:CATEGORIES)for(String key:DELTAS){
                ArrayNode rows=(ArrayNode)result.path(cat).path(key);String ordering=cat+"\0"+key;
                if(largest==null||rows.size()>largest.size()||rows.size()==largest.size()&&ordering.compareTo(largestKey)>0){largest=rows;largestKey=ordering;}
            }
            if(largest==null||largest.size()==0)throw invalid("comparison metadata exceeds byte limit");
            int count=quarter?Math.max(1,largest.size()/4):1;while(count-->0)largest.remove(largest.size()-1);
            finishComparison(result);
        }
    }
}
