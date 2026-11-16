package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.regex.Pattern;

/** Pure retained-summary grouping. Similar failure signals are not root-cause claims. */
public final class ErrorGroups {
    public static final int MAX_SEGMENTS=10000,MAX_ERRORS=64,MAX_WARNINGS=128;
    private static final double SAFE_INTEGER=9007199254740991d;
    private static final Pattern CLASS=Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final String SCOPE="Counts cover loaded retained evidence only, not all traffic or an error rate. Requests can occur in multiple groups; group request counts are not additive.";
    private static final String SUMMARY="Summary grouping does not index arbitrary exception events or HTTP-client-only failures.";
    private static final Map<String,Integer>TEXT_FILTERS=new LinkedHashMap<>();
    private static final Set<String>NUMERIC_FILTERS=new TreeSet<>(Arrays.asList("since_minutes","min_duration","max_duration","http_status"));
    static {TEXT_FILTERS.put("service",256);TEXT_FILTERS.put("method",32);TEXT_FILTERS.put("path",2048);TEXT_FILTERS.put("category",32);
        TEXT_FILTERS.put("exception_class",256);TEXT_FILTERS.put("user",256);TEXT_FILTERS.put("site",256);TEXT_FILTERS.put("channel",64);TEXT_FILTERS.put("query",256);}
    private ErrorGroups(){}
    private static String text(JsonNode n,int max){String s=Evidence.text(n,max);return s==null?"":s;}
    private static String firstText(int max,JsonNode... values){for(JsonNode n:values){String s=text(n,max);if(!s.isEmpty())return s;}return "";}
    private static String nullable(String s){return s==null||s.isEmpty()?null:s;}
    private static Double number(JsonNode n){if(n!=null&&n.isTextual()&&!n.textValue().trim().matches("[0-9]+(?:\\.[0-9]+)?"))return null;return Evidence.number(n);}
    private static Double firstNumber(JsonNode...n){for(JsonNode v:n){Double d=number(v);if(d!=null)return d;}return null;}
    private static String scalar(JsonNode n){
        if(n!=null&&n.isTextual())return text(n,256);
        if(n!=null&&n.isNumber()&&Double.isFinite(n.doubleValue())){double d=n.doubleValue();return d==Math.floor(d)?new java.math.BigDecimal(n.asText()).toBigInteger().toString():Double.toString(d);}
        return "";
    }
    private static String firstScalar(JsonNode...nodes){for(JsonNode n:nodes){String s=scalar(n);if(!s.isEmpty())return s;}return "";}
    private static boolean truth(JsonNode n){return n!=null&&(n.isBoolean()&&n.booleanValue()||"true".equalsIgnoreCase(text(n,256)));}
    private static boolean strictTrue(JsonNode n){return n!=null&&n.isBoolean()&&n.booleanValue();}
    public static ObjectNode validateFilters(JsonNode filters){
        if(filters==null||filters.isNull())filters=Json.obj();
        if(!filters.isObject())throw new IllegalArgumentException("error group filters must be an object");
        Iterator<String>keys=filters.fieldNames();while(keys.hasNext()){String k=keys.next();if(!TEXT_FILTERS.containsKey(k)&&!NUMERIC_FILTERS.contains(k))throw new IllegalArgumentException("unknown error group filter");}
        ObjectNode result=Json.obj();
        for(Map.Entry<String,Integer> f:TEXT_FILTERS.entrySet()){
            JsonNode value=filters.get(f.getKey());
            if(value==null||value.isNull()){result.putNull(f.getKey());continue;}
            if(!value.isTextual()||value.textValue().codePointCount(0,value.textValue().length())>f.getValue()||value.textValue().codePoints().anyMatch(Evidence::control))
                throw new IllegalArgumentException(f.getKey()+" must be text of at most "+f.getValue()+" characters without control characters");
            result.set(f.getKey(),Json.node(nullable(value.textValue().trim())));
        }
        if("all".equals(Json.text(result,"category")))result.putNull("category");
        if(result.hasNonNull("category")&&!Evidence.oneOf(Json.text(result,"category"),"http4xx","http5xx","timeout","execution"))throw new IllegalArgumentException("category must be http4xx, http5xx, timeout or execution");
        for(String k:Arrays.asList("method","channel"))if(result.hasNonNull(k))result.put(k,Json.text(result,k).toUpperCase(Locale.ROOT));
        for(String name:NUMERIC_FILTERS){
            JsonNode value=filters.has(name)?filters.get(name):"since_minutes".equals(name)?Json.node(30):null;
            if(value==null||value.isNull()||value.isTextual()&&value.textValue().isEmpty()){result.putNull(name);continue;}
            Double d=Evidence.number(value);
            if(d==null||value.isTextual()&&(value.textValue().length()>64||value.textValue().trim().isEmpty()))throw new IllegalArgumentException(name+" must be a finite nonnegative number");
            if("since_minutes".equals(name)&&d>525600)throw new IllegalArgumentException("since_minutes must be between 0 and 525600, or null for all retained");
            if("http_status".equals(name)){
                if(d!=Math.floor(d)||d<100||d>599)throw new IllegalArgumentException("http_status must be an integer between 100 and 599");result.put(name,d.intValue());
            }else result.put(name,d);
        }
        if(result.hasNonNull("min_duration")&&result.hasNonNull("max_duration")&&result.get("min_duration").doubleValue()>result.get("max_duration").doubleValue())
            throw new IllegalArgumentException("min_duration must not exceed max_duration");
        return result;
    }
    public static String validateGroupId(String id){if(id==null||!id.matches("eg_[0-9a-f]{24}"))throw new IllegalArgumentException("invalid error group id");return id;}
    private static String traceId(JsonNode n){
        String s=text(n,128).toLowerCase(Locale.ROOT);
        return s.matches("(?:[0-9a-f]{32}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})")&&!s.replace("-","").matches("0+")?s:null;
    }
    private static String parent(JsonNode r,JsonNode a,String name){JsonNode n=r.get(name);if(n==null||n.isNull()||n.isTextual()&&n.textValue().isEmpty())n=a.get(name);
        if(n==null||n.isNull()||n.isTextual()&&n.textValue().isEmpty())return "";String s=text(n,256);return s.isEmpty()?"[unknown parent]":s;}
    private static JsonNode[] routeValues(JsonNode r){JsonNode a=r.path("attributes");return new JsonNode[]{r.get("routeTemplate"),r.get("httpRoute"),r.get("httpRouteTemplate"),a.get("routeTemplate"),a.get("httpRoute"),a.get("httpRouteTemplate"),a.get("http.route"),a.get("route")};}
    private static String routeIdentity(JsonNode raw,ObjectNode normalized){
        JsonNode[] values=!Evidence.s(normalized,"routeTemplate").isEmpty()?routeValues(raw):!Evidence.s(normalized,"path").isEmpty()
                ?new JsonNode[]{raw.get("path"),raw.path("attributes").get("path")}:new JsonNode[]{raw.get("lensId"),raw.get("label"),raw.path("attributes").get("lensId")};
        String value="";for(JsonNode n:values)if(n!=null&&n.isTextual()&&!n.textValue().trim().isEmpty()){value=n.textValue();break;}
        return Evidence.hash(value);
    }
    public static ObjectNode normalize(JsonNode r){
        JsonNode a=r.path("attributes");String status=firstText(256,r.get("status"),a.get("status")).toUpperCase(Locale.ROOT);
        Double code=firstNumber(r.get("httpStatus"),a.get("httpStatus"));Integer http=code!=null&&code==Math.floor(code)&&code>=100&&code<=599?code.intValue():null;
        Double started=firstNumber(r.get("startedAtMillis"),r.get("startedAt"),a.get("startedAtMillis"),a.get("startedAt"));
        String boundary=firstText(256,r.get("filterException"),a.get("filterException"));boolean timeout=truth(r.get("asyncTimeout"))||truth(a.get("asyncTimeout"));
        Double ec=firstNumber(r.get("exceptionCount"),a.get("exceptionCount"));double exceptionCount=ec==null?0:ec;
        String channel=firstText(64,r.get("entryChannel"),r.get("channel"),a.get("entryChannel")).toUpperCase(Locale.ROOT);if(channel.isEmpty())channel="UNKNOWN";
        String jobOutcome=nullable(firstText(256,r.get("jobOutcome"),a.get("jobOutcome")).toUpperCase(Locale.ROOT)),
                jobFailure=firstText(256,r.get("jobFailureType"),a.get("jobFailureType"));
        boolean hasErrors=http!=null&&http>=400||Evidence.oneOf(status,"FAILED","ERROR")||strictTrue(r.get("hasErrors"))||timeout||"SCHEDULER".equals(channel)&&"FAILED".equals(jobOutcome);
        boolean boundaryEvidence=!boundary.isEmpty()&&!"false".equalsIgnoreCase(boundary);
        String exceptionClass=!Evidence.oneOf(boundary.toLowerCase(Locale.ROOT),"true","false","null","none")&&CLASS.matcher(boundary).matches()?boundary:null;
        if(exceptionClass==null&&"SCHEDULER".equals(channel)&&"FAILED".equals(jobOutcome)&&CLASS.matcher(jobFailure).matches())exceptionClass=jobFailure;
        String method=firstText(32,r.get("httpMethod"),r.get("method"),a.get("httpMethod"),a.get("method")).toUpperCase(Locale.ROOT),
                path=firstText(2048,r.get("path"),a.get("path")),template=firstText(2048,routeValues(r)),
                label=firstText(256,r.get("lensId"),r.get("label"),a.get("lensId")),user=firstText(256,r.get("user"),a.get("user")),
                site=firstScalar(r.get("siteId"),r.get("site"),a.get("siteId"),a.get("site"));
        String service=firstText(256,r.get("service"),r.get("applicationId"),a.get("applicationId"));if(service.isEmpty())service="unknown";
        Double count=firstNumber(r.get("eventCount"));
        return Json.obj("traceId",traceId(r.get("traceId")),"segmentId",text(r.get("segmentId"),256),
                "segmentKey",nullable(text(r.get("segmentKey"),4096)),"service",service,
                "serviceInstanceId",firstText(256,r.get("serviceInstanceId"),a.get("serviceInstanceId")),
                "applicationId",firstText(256,r.get("applicationId"),a.get("applicationId")),
                "parentSpanId",parent(r,a,"parentSpanId"),"parentSegmentId",parent(r,a,"parentSegmentId"),
                "spanId",firstText(256,r.get("spanId"),a.get("spanId")),"method",method,"httpMethod",method,"path",path,"routeTemplate",template,
                "label",label,"lensId",label,"channel",channel,"entryChannel",channel,
                "jobType",nullable(firstText(256,r.get("jobType"),a.get("jobType"))),"jobId",nullable(firstScalar(r.get("jobId"),a.get("jobId"))),
                "jobOutcome",jobOutcome,"jobAuditStatus",nullable(firstText(256,r.get("jobAuditStatus"),a.get("jobAuditStatus")).toUpperCase(Locale.ROOT)),
                "jobFailureType",CLASS.matcher(jobFailure).matches()?jobFailure:null,"scheduler",nullable(firstText(256,r.get("scheduler"),a.get("scheduler"))),
                "status",status,"httpStatus",http,"user",user,"site",site,"siteId",site,"startedAtMillis",started,
                "lastActivityMillis",firstNumber(r.get("lastActivityMillis"),a.get("lastActivityMillis"),r.get("endedAt"),Json.node(started)),
                "durationMs",firstNumber(r.get("durationMs"),a.get("durationMs")),"eventCount",count==null?0d:Math.min(count,SAFE_INTEGER),
                "hasErrors",hasErrors,"hasExceptions",strictTrue(r.get("hasExceptions"))||exceptionCount>0||boundaryEvidence,
                "filterException",exceptionClass!=null?exceptionClass:boundaryEvidence?"true":"","exceptionClass",exceptionClass,"asyncTimeout",timeout,
                "truncated",strictTrue(r.get("truncated")),"httpCallsTruncated",strictTrue(r.get("httpCallsTruncated")),"httpCallCount",firstNumber(r.get("httpCallCount")));
    }
    public static String segmentIdentity(JsonNode segment){return Evidence.canonical(Json.arr(segment.path("service"),segment.path("serviceInstanceId"),segment.path("segmentId")));}
    private static Comparator<JsonNode>segmentOrder(){return Comparator.comparingDouble((JsonNode n)->n.hasNonNull("startedAtMillis")?n.path("startedAtMillis").doubleValue():Double.POSITIVE_INFINITY)
            .thenComparing(n->Evidence.s(n,"service")).thenComparing(n->Evidence.s(n,"serviceInstanceId")).thenComparing(n->Evidence.s(n,"segmentId")).thenComparing(Evidence::canonical);}
    public static ArrayNode requests(List<JsonNode> segments,Set<String>conflictingIdentities){
        List<JsonNode>sorted=new ArrayList<>(segments);sorted.sort(segmentOrder());Map<String,List<JsonNode>>groups=new LinkedHashMap<>();Map<String,Integer>occurrences=new HashMap<>();
        for(JsonNode s:sorted){
            String key;
            if(s.hasNonNull("traceId"))key="trace:"+Json.text(s,"traceId");
            else{
                String base="segment:"+Json.stringify(Json.arr(s.path("service"),s.path("serviceInstanceId"),s.path("segmentId")));
                int occurrence=occurrences.getOrDefault(base,0);occurrences.put(base,occurrence+1);key=base+(occurrence==0?"":":observation:"+occurrence);
            }groups.computeIfAbsent(key,k->new ArrayList<>()).add(s);
        }
        List<JsonNode>out=new ArrayList<>();
        for(Map.Entry<String,List<JsonNode>>entry:groups.entrySet()){
            List<JsonNode>members=entry.getValue(),roots=new ArrayList<>();boolean errors=false,active=false,exceptions=false;double eventCount=0;
            Double startMin=null,activityMax=null;Set<String>services=new TreeSet<>();
            for(JsonNode s:members){
                if(Evidence.s(s,"parentSpanId").isEmpty()&&Evidence.s(s,"parentSegmentId").isEmpty())roots.add(s);
                errors|=Json.bool(s,"hasErrors");active|="ACTIVE".equals(Json.text(s,"status"))||"SCHEDULER".equals(Json.text(s,"channel"))&&"RUNNING".equals(Json.text(s,"jobOutcome"));
                exceptions|=Json.bool(s,"hasExceptions");eventCount+=s.path("eventCount").asDouble();services.add(Json.text(s,"service"));
                if(s.hasNonNull("startedAtMillis"))startMin=startMin==null?s.get("startedAtMillis").doubleValue():Math.min(startMin,s.get("startedAtMillis").doubleValue());
                if(s.hasNonNull("lastActivityMillis"))activityMax=activityMax==null?s.get("lastActivityMillis").doubleValue():Math.max(activityMax,s.get("lastActivityMillis").doubleValue());
            }
            boolean conflict=false;for(JsonNode s:roots)conflict|=conflictingIdentities.contains(segmentIdentity(s));
            boolean missing=roots.size()!=1||conflict;JsonNode root=roots.isEmpty()?members.get(0):roots.get(0);boolean job="SCHEDULER".equals(Json.text(root,"channel"));
            int http=root.path("httpStatus").asInt(-1);
            boolean success=!missing&&(job?"SUCCEEDED".equals(Json.text(root,"jobOutcome")):http>=200&&http<400),skipped=!missing&&job&&"SKIPPED".equals(Json.text(root,"jobOutcome"));
            String label=Json.text(root,"label");
            if(label==null||label.isEmpty())label=job?Json.text(root,"jobType"):null;
            if(label==null||label.isEmpty())label=(Evidence.s(root,"method")+" "+Evidence.s(root,"path")).trim();
            if(label.isEmpty())label=Json.text(root,"channel")+" request";
            ArrayNode memberNodes=Json.arr();for(JsonNode member:members)memberNodes.add(member);
            ObjectNode r=Json.obj("key",entry.getKey(),"traceId",members.get(0).path("traceId"),"segments",memberNodes,"root",root,
                    "rootMissing",missing,"rootAmbiguous",roots.size()>1,"label",label,"services",Json.node(services),
                    "jobOutcome",missing?null:root.get("jobOutcome"),
                    "startedAtMillis",!missing&&root.hasNonNull("startedAtMillis")?root.get("startedAtMillis"):Json.node(startMin),
                    "lastActivityMillis",activityMax,"durationMs",missing?null:root.get("durationMs"),"httpStatus",missing?null:root.get("httpStatus"),
                    "outcome",errors?"error":active?"active":success?"success":skipped?"skipped":"unknown","hasErrors",errors,"hasExceptions",exceptions,
                    "eventCount",Math.min(eventCount,SAFE_INTEGER));
            for(String k:new String[]{"method","path","user","site","jobType","jobId","jobAuditStatus","jobFailureType","scheduler","channel"})r.set(k,root.path(k));
            out.add(r);
        }
        out.sort(Comparator.comparingDouble((JsonNode n)->n.hasNonNull("startedAtMillis")?-n.get("startedAtMillis").doubleValue():Double.POSITIVE_INFINITY).thenComparing(n->Json.text(n,"key")));
        ArrayNode result=Json.arr();for(JsonNode r:out)result.add(r);return result;
    }
    private static String category(JsonNode s){if(Json.bool(s,"asyncTimeout"))return "timeout";int status=s.path("httpStatus").asInt(-1);
        if(status>=500&&status<=599)return "http5xx";if(status>=400&&status<=499)return "http4xx";return Json.bool(s,"hasErrors")?"execution":null;}
    private static boolean contains(JsonNode value,String query){return value!=null&&!value.isNull()&&value.asText().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));}
    private static boolean requestMatches(JsonNode r,ObjectNode f,double now){
        if(f.hasNonNull("since_minutes")&&(!r.hasNonNull("startedAtMillis")||r.get("startedAtMillis").doubleValue()<now-f.get("since_minutes").doubleValue()*60000))return false;
        if(f.hasNonNull("min_duration")&&(!r.hasNonNull("durationMs")||r.get("durationMs").doubleValue()<f.get("min_duration").doubleValue()))return false;
        if(f.hasNonNull("max_duration")&&(!r.hasNonNull("durationMs")||r.get("durationMs").doubleValue()>f.get("max_duration").doubleValue()))return false;
        boolean identity=false;
        for(JsonNode s:Json.rows(r,"segments"))if((!f.hasNonNull("user")||contains(s.get("user"),Json.text(f,"user")))&&(!f.hasNonNull("site")||Json.text(f,"site").equals(Json.text(s,"site")))){identity=true;break;}
        if(!identity)return false;
        String query=Json.text(f,"query");
        if(query!=null){
            if(contains(r.get("traceId"),query)||contains(r.get("label"),query))return true;
            for(JsonNode s:Json.rows(r,"segments"))for(String k:new String[]{"segmentId","service","serviceInstanceId","method","path","routeTemplate","label","user","site"})if(contains(s.get(k),query))return true;
            return false;
        }return true;
    }
    private static boolean segmentMatches(JsonNode s,String category,ObjectNode f){
        if(category==null)return false;
        for(String k:new String[]{"service","method","channel"})if(f.hasNonNull(k)&&!Json.text(f,k).equals(Json.text(s,k)))return false;
        if(f.hasNonNull("path")&&!contains(s.get("path"),Json.text(f,"path"))&&!contains(s.get("routeTemplate"),Json.text(f,"path")))return false;
        if(f.hasNonNull("category")&&!category.equals(Json.text(f,"category")))return false;
        if(f.hasNonNull("http_status")&&(!s.hasNonNull("httpStatus")||f.get("http_status").intValue()!=s.get("httpStatus").intValue()))return false;
        return !f.hasNonNull("exception_class")||contains(s.get("exceptionClass"),Json.text(f,"exception_class"));
    }
    private static final class Warnings{
        final Set<String>messages=new HashSet<>(Arrays.asList(SCOPE,SUMMARY));boolean incomplete;
        void add(String message){messages.add(message);incomplete=true;}
    }
    private static final class Group {ObjectNode node;Set<String>requestKeys=new TreeSet<>(),segmentIdentities=new HashSet<>();}
    public static ObjectNode build(JsonNode doc,JsonNode filters){return build(doc,filters,System.currentTimeMillis());}
    public static ObjectNode build(JsonNode doc,JsonNode filters,double nowMs){
        ObjectNode f=validateFilters(filters);if(!Double.isFinite(nowMs)||nowMs<0)throw new IllegalArgumentException("now_ms must be a finite nonnegative timestamp");
        Warnings warnings=new Warnings();
        if(doc==null||!doc.isObject()){warnings.add("Malformed summary document; no missing evidence inferred");doc=Json.obj();}
        JsonNode rows=doc.path("traces");
        if(!rows.isArray()){if(!rows.isMissingNode())warnings.add("Malformed trace summaries omitted");rows=Json.arr();}
        boolean limited=Evidence.truthy(doc.get("limited"))||rows.size()>MAX_SEGMENTS;
        if(limited)warnings.add("Retained summary results are limited; counts exclude omitted segments and requests");
        if(rows.size()>MAX_SEGMENTS)warnings.add("Error grouping input limit reached (10000 segments)");
        List<JsonNode>errors=new ArrayList<>();JsonNode rawErrors=doc.path("errors");
        if(!rawErrors.isArray()){if(!rawErrors.isMissingNode())warnings.add("Malformed upstream error metadata omitted");rawErrors=Json.arr();}
        for(int i=0;i<Math.min(rawErrors.size(),MAX_ERRORS);i++){
            JsonNode e=rawErrors.get(i);if(!e.isObject()){warnings.add("Malformed upstream error entry omitted");continue;}
            String error=text(e.get("error"),512);errors.add(Json.obj("service",nullable(text(e.get("service"),256)),"error",error.isEmpty()?"Unknown upstream error":error));
        }
        if(rawErrors.size()>MAX_ERRORS)warnings.add("Upstream error display limit reached (64)");
        if(!errors.isEmpty()||Evidence.truthy(doc.get("partial")))warnings.add("Some upstream evidence is unavailable or partial; upstream errors are not application error groups");
        JsonNode rawWarnings=doc.path("warnings");
        if(rawWarnings.isArray()){
            for(int i=0;i<Math.min(rawWarnings.size(),MAX_WARNINGS);i++){String s=text(rawWarnings.get(i),512);warnings.add(s.isEmpty()?"Malformed upstream warning omitted":s);}
            if(rawWarnings.size()>MAX_WARNINGS)warnings.add("Upstream warning display limit reached");
        }else if(!rawWarnings.isMissingNode())warnings.add("Malformed upstream warnings omitted");
        List<JsonNode>segments=new ArrayList<>();Map<JsonNode,String>routes=new IdentityHashMap<>();
        Map<String,Set<String>>identities=new HashMap<>();Set<String>conflicts=new HashSet<>();
        for(int i=0;i<Math.min(rows.size(),MAX_SEGMENTS);i++){
            JsonNode raw=rows.get(i);if(!raw.isObject()){warnings.add("Malformed trace summary entries omitted");continue;}
            ObjectNode s=normalize(raw);String route=routeIdentity(raw,s),id=segmentIdentity(s);
            if(!Evidence.s(s,"segmentId").isEmpty()){
                String signature=Evidence.canonical(Json.arr(s,route));Set<String>seen=identities.computeIfAbsent(id,k->new HashSet<>());
                if(seen.contains(signature)){warnings.add("Duplicate identical retained segment summaries were deduplicated");continue;}
                if(!seen.isEmpty()){warnings.add("Conflicting summaries for one segment are retained; root identification may be ambiguous");conflicts.add(id);}
                seen.add(signature);
            }
            segments.add(s);routes.put(s,route);
            if(!s.hasNonNull("traceId"))warnings.add("Some summaries have no valid trace ID; unidentified observations are not merged into logical requests");
            if(Evidence.s(s,"segmentId").isEmpty())warnings.add("Some summaries lack segment identifiers; their observed entries are counted separately");
            if(Json.bool(s,"truncated")||Json.bool(s,"httpCallsTruncated"))warnings.add("Some retained segments are truncated; additional error evidence may be unavailable");
            if("ACTIVE".equals(Json.text(s,"status")))warnings.add("Some retained segments are active; recorded outcomes can still change");
        }
        ArrayNode requests=requests(segments,conflicts),matching=Json.arr();Map<String,Group>groups=new HashMap<>();Set<String>matchingSegments=new HashSet<>();
        for(JsonNode request:requests){
            if(!requestMatches(request,f,nowMs))continue;boolean matched=false;int memberIndex=0;
            for(JsonNode segment:Json.rows(request,"segments")){
                String category=category(segment);if(!segmentMatches(segment,category,f)){memberIndex++;continue;}
                matched=true;
                String sid=!Evidence.s(segment,"segmentId").isEmpty()?segmentIdentity(segment):"unidentified:"+Json.text(request,"key")+":"+memberIndex;
                memberIndex++;matchingSegments.add(sid);
                String template=Evidence.s(segment,"routeTemplate"),path=Evidence.s(segment,"path"),label=Evidence.s(segment,"label");
                String route=!template.isEmpty()?template:!path.isEmpty()?path:label,kind=!template.isEmpty()?"template":!path.isEmpty()?"literal":!label.isEmpty()?"label":"unknown";
                String routeId=routes.get(segment);
                String id="eg_"+Evidence.hash(Evidence.canonical(Json.arr(segment.path("service"),segment.path("channel"),segment.path("method"),kind,routeId,category,segment.path("httpStatus"),segment.path("exceptionClass")))).substring(0,24);
                Group group=groups.get(id);
                if(group==null){
                    group=new Group();group.node=Json.obj("id",id,"service",segment.path("service"),"channel",segment.path("channel"),"method",segment.path("method"),
                            "route",route,"routeKind",kind,"category",category,"httpStatus",segment.path("httpStatus"),"exceptionClass",segment.path("exceptionClass"),
                            "requestCount",0,"segmentCount",0,"firstSeenMillis",null,"lastSeenMillis",null);groups.put(id,group);
                }
                group.segmentIdentities.add(sid);group.requestKeys.add(Json.text(request,"key"));
                if(request.hasNonNull("startedAtMillis")){
                    double started=request.get("startedAtMillis").doubleValue();
                    group.node.put("firstSeenMillis",group.node.hasNonNull("firstSeenMillis")?Math.min(group.node.get("firstSeenMillis").doubleValue(),started):started);
                    group.node.put("lastSeenMillis",group.node.hasNonNull("lastSeenMillis")?Math.max(group.node.get("lastSeenMillis").doubleValue(),started):started);
                }
            }
            if(matched)matching.add(request);
        }
        List<JsonNode>resultGroups=new ArrayList<>();for(Group g:groups.values()){g.node.put("segmentCount",g.segmentIdentities.size());g.node.set("requestKeys",Json.node(g.requestKeys));g.node.put("requestCount",g.requestKeys.size());resultGroups.add(g.node);}
        resultGroups.sort(Comparator.comparingInt((JsonNode g)->-g.path("requestCount").asInt()).thenComparingDouble(g->g.hasNonNull("lastSeenMillis")?-g.get("lastSeenMillis").doubleValue():Double.POSITIVE_INFINITY).thenComparing(g->Json.text(g,"id")));
        List<String>messages=new ArrayList<>(warnings.messages);messages.sort(Comparator.comparing((String s)->!SCOPE.equals(s)).thenComparing(s->!s.toLowerCase(Locale.ROOT).contains("limit")).thenComparing(s->s));
        if(messages.size()>MAX_WARNINGS){messages=new ArrayList<>(messages.subList(0,MAX_WARNINGS-1));messages.add("Warning display limit reached; additional warnings omitted");}
        errors.sort(Comparator.comparing(Evidence::canonical));
        return Json.obj("schemaVersion",1,"kind","error-groups","scope","retained-only","partial",warnings.incomplete,"limited",limited,"errors",Json.node(errors),"warnings",Json.node(messages),
                "counts",Json.obj("loadedSegments",segments.size(),"loadedRequests",requests.size(),"matchingRequests",matching.size(),"matchingSegments",matchingSegments.size(),"groups",resultGroups.size()),
                "groups",Json.node(resultGroups),"requests",matching);
    }
    public static ObjectNode select(JsonNode model,String groupId){
        validateGroupId(groupId);JsonNode group=null;for(JsonNode g:Json.rows(model,"groups"))if(groupId.equals(Json.text(g,"id"))){group=g;break;}
        Set<String>keys=new HashSet<>();if(group!=null)for(JsonNode k:Json.rows(group,"requestKeys"))keys.add(k.asText());
        ArrayNode requests=Json.arr();for(JsonNode request:Json.rows(model,"requests"))if(keys.contains(Json.text(request,"key")))requests.add(request.deepCopy());
        ArrayNode warnings=Json.arr();for(JsonNode warning:Json.rows(model,"warnings"))warnings.add(warning.deepCopy());
        if(group==null)warnings.add("Error group not observed in the current retained/filter scope; evidence may have changed or expired");
        ObjectNode counts=Json.copy(model.path("counts"));counts.put("matchingRequests",requests.size());counts.put("matchingSegments",group==null?0:group.path("segmentCount").asInt());counts.put("groups",group==null?0:1);
        return Json.obj("schemaVersion",1,"kind","error-group","scope","retained-only","groupId",groupId,"group",group==null?null:group.deepCopy(),"requests",requests,
                "partial",model.path("partial"),"limited",model.path("limited"),"errors",model.path("errors").deepCopy(),"warnings",warnings,"counts",counts);
    }
}
