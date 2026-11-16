package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded projections of local dependency boundaries. Opt-in JDBC evidence may contain raw SQL. */
public final class Dependencies {
    public static final int MAX_EVENTS = 100000, MAX_EXTERNAL_CALLS = 5000,
            MAX_ALIASES = 128, MAX_DESTINATION = 512, MAX_WRAPPER_THREADS = 64, MAX_WRAPPER_DEPTH = 512,
            MAX_SQL_CHARS = 16384, MAX_SQL_PARAMETERS = 128, MAX_SQL_PARAMETER_CHARS = 512,
            MAX_SQL_PARAMETER_JSON_CHARS = 8192, MAX_SQL_PARAMETER_SETS = 10;
    private static final Set<String> SQL_PARAMETER_TYPES = new HashSet<>(Arrays.asList(
            "Null","Boolean","Byte","Short","Int","Long","Float","Double","BigDecimal","String","Bytes",
            "Date","Time","Timestamp","AsciiStream","UnicodeStream","BinaryStream","Object","CharacterStream",
            "Ref","Blob","Clob","Array","URL","RowId","NString","NCharacterStream","NClob","SQLXML"));
    private static final Set<String> NATIVE_PARAMETER_TYPES = new HashSet<>(Arrays.asList(
            "Null","Boolean","Byte","Short","Int","Long","Float","Double","BigDecimal","BigInteger","String","Character","UUID",
            "Date","Time","Timestamp","LocalDate","LocalTime","LocalDateTime","OffsetTime","OffsetDateTime","Instant","Object"));
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_$][A-Za-z0-9_.$:-]{0,127}");
    private static final Pattern OPERATION = Pattern.compile("[A-Za-z_$][A-Za-z0-9_.$:-]{0,127}");
    private static final Pattern TARGET = Pattern.compile("[A-Za-z0-9_./:$\\[\\]-]+");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _.:-]{0,127}");
    private static final Pattern THREAD = Pattern.compile("[A-Za-z0-9_.$:#/\\[\\] -]{1,128}");
    private static final Pattern CLASS = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Pattern METHOD = Pattern.compile("(?:[A-Za-z_$][A-Za-z0-9_$]*|<init>|<clinit>)");
    private Dependencies() {}

    private static String token(JsonNode value, Pattern pattern) {
        return value != null && value.isTextual() && pattern.matcher(value.textValue()).matches() ? value.textValue() : null;
    }
    public static String destination(JsonNode value) {
        if (value == null || !value.isTextual()) return null;
        String target = value.textValue();
        if (target.isEmpty() || target.length() > 2048) return null;
        for (int i=0;i<target.length();i++) if (target.charAt(i)<33 || target.charAt(i)>126) return null;
        if (target.contains("://")) {
            try {
                URI uri = new URI(target);
                String scheme = uri.getScheme(), host = uri.getHost();
                if (!("http".equals(scheme) || "https".equals(scheme)) || host == null || host.isEmpty()) return null;
                host = host.toLowerCase(Locale.ROOT);
                if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "["+host+"]";
                if (uri.getPort() < -1 || uri.getPort() > 65535) return null;
                if (uri.getPort() != -1) host += ":"+uri.getPort();
                target = scheme+"://"+host+(uri.getRawPath()==null ? "" : uri.getRawPath());
            } catch (Exception e) { return null; }
        }
        return target.length() <= MAX_DESTINATION && TARGET.matcher(target).matches() ? target : null;
    }
    public static String destination(String value) { return destination(Json.node(value)); }
    public static ObjectNode validateAliases(JsonNode aliases) {
        if (aliases == null || !aliases.isObject() || aliases.size()>MAX_ALIASES)
            throw new IllegalArgumentException("dependencyAliases must be an object with at most 128 entries");
        Iterator<Map.Entry<String,JsonNode>> fields=aliases.fields();
        while (fields.hasNext()) {
            Map.Entry<String,JsonNode> f=fields.next();
            if (!f.getKey().equals(destination(Json.node(f.getKey()))) || token(f.getValue(),NAME)==null)
                throw new IllegalArgumentException("dependencyAliases requires bounded credential-free exact targets and display names");
        }
        return ((ObjectNode)aliases).deepCopy();
    }
    public static String aliasFor(JsonNode aliases,String target) {
        return aliases!=null && aliases.isObject() && target!=null ? token(aliases.get(target),NAME) : null;
    }
    private static Long sequence(JsonNode n) {
        if (n==null) return null;
        if(n.isTextual() && n.textValue().length()<=19 && n.textValue().matches("[0-9]+")) {
            try { long v=Long.parseLong(n.textValue()); return v>0 ? v : null; } catch(NumberFormatException e){return null;}
        }
        return n.isIntegralNumber() && n.canConvertToLong() && n.longValue()>0 ? n.longValue():null;
    }
    private static String wrapper(JsonNode e) {
        String m=Json.text(e,"methodName");
        return "METHOD_ENTER".equals(Json.text(e,"type"))
                && "com.chargebee.ems.config.cache.ValkeyCacheService".equals(Json.text(e,"className"))
                && Evidence.oneOf(m,"get","put","evict") ? m:null;
    }
    /** Accept SQL only on the narrow driver-execution contract, never arbitrary external attributes. */
    public static boolean jdbcExecution(JsonNode a) {
        return jdbcDatabase(a) && "jdbc-execution".equals(Json.text(a,"evidenceKind"))
                && Evidence.oneOf(Json.text(a,"operation"),"execute","executeQuery","executeUpdate","executeLargeUpdate","executeBatch","executeLargeBatch")
                && Evidence.oneOf(Json.text(a,"status"),"OBSERVED","ERROR");
    }
    public static boolean jdbcLimit(JsonNode a) {
        return jdbcDatabase(a) && "jdbc-query-limit".equals(Json.text(a,"evidenceKind"))
                && "query-limit".equals(Json.text(a,"operation")) && "TRUNCATED".equals(Json.text(a,"status"));
    }
    public static boolean clickhouseQuery(JsonNode a) {
        return nativeDatabase(a) && "clickhouse-query".equals(Json.text(a,"evidenceKind"))
                && "query".equals(Json.text(a,"operation")) && Evidence.oneOf(Json.text(a,"status"),"OBSERVED","ERROR","SUBMITTED");
    }
    public static boolean sqlExecution(JsonNode a) { return jdbcExecution(a)||clickhouseQuery(a); }
    public static boolean sqlLimit(JsonNode a) {
        return jdbcLimit(a)||nativeDatabase(a)&&"clickhouse-query-limit".equals(Json.text(a,"evidenceKind"))
                &&"query-limit".equals(Json.text(a,"operation"))&&"TRUNCATED".equals(Json.text(a,"status"));
    }
    private static boolean nativeDatabase(JsonNode a) {
        return a!=null&&a.isObject()&&"database".equals(Json.text(a,"boundaryKind"))
                &&"clickhouse".equals(Json.text(a,"system"))&&"outbound".equals(Json.text(a,"direction"));
    }
    private static boolean jdbcDatabase(JsonNode a) {
        return a!=null && a.isObject() && "database".equals(Json.text(a,"boundaryKind"))
                && Evidence.oneOf(Json.text(a,"system"),"postgresql","mysql") && "outbound".equals(Json.text(a,"direction"));
    }
    private static Integer boundedInteger(JsonNode n,int max) {
        if(n!=null&&n.isTextual()&&(n.textValue().length()>10||!n.textValue().matches("[0-9]+")))return null;
        Long v=Evidence.integer(n);return v!=null&&v>=0&&v<=max?v.intValue():null;
    }
    private static Double duration(JsonNode n) {
        if(n!=null&&n.isTextual()&&(n.textValue().length()>32||!n.textValue().matches("[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")))return null;
        return Evidence.number(n);
    }
    private static boolean trueFlag(JsonNode n) {return n!=null&&(n.isBoolean()&&n.booleanValue()||n.isTextual()&&"true".equals(n.textValue()));}
    private static String sqlText(JsonNode n) {
        if(n==null||!n.isTextual()||n.textValue().isEmpty())return null;
        String source=n.textValue();int end=Math.min(source.length(),MAX_SQL_CHARS);
        if(end<source.length()&&end>0&&Character.isHighSurrogate(source.charAt(end-1)))end--;
        // Preserve SQL literals and formatting; remove terminal/bidi controls, not SQL contents.
        StringBuilder result=new StringBuilder(end);
        for(int i=0;i<end;){int cp=source.codePointAt(i);i+=Character.charCount(cp);
            result.appendCodePoint(cp=='\n'||cp=='\r'||cp=='\t'||!Evidence.control(cp)?cp:' ');}
        return result.toString();
    }
    private static JsonNode parameterJson(JsonNode n) {
        if(n==null||n.isNull()||n.isMissingNode())return null;
        if(n.isTextual()) {
            if(n.textValue().length()>MAX_SQL_PARAMETER_JSON_CHARS)return null;
            try {n=Json.parse(n.textValue());} catch(IllegalArgumentException ignored){return null;}
        }
        return n.isArray()?n:null;
    }
    /** Setter metadata only. Never stringify arbitrary objects, streams or binary values. */
    private static ArrayNode parameterArray(JsonNode raw) {
        JsonNode rows=parameterJson(raw);
        if(rows==null||rows.size()>MAX_SQL_PARAMETERS)return null;
        TreeMap<Integer,ObjectNode> ordered=new TreeMap<>();
        for(JsonNode row:rows) {
            JsonNode index=row.get("index"),value=row.get("value");String type=Json.text(row,"type"),status=Json.text(row,"status");
            if(!row.isObject()||index==null||!index.isIntegralNumber()||!index.canConvertToInt()
                    ||index.intValue()<1||index.intValue()>MAX_SQL_PARAMETERS||ordered.containsKey(index.intValue())
                    ||!SQL_PARAMETER_TYPES.contains(type)||!Evidence.oneOf(status,"captured","null","unsupported","truncated"))return null;
            if(Evidence.oneOf(status,"null","unsupported")) {
                if(value==null||!value.isNull())return null;
            } else if(value==null||!value.isTextual()||value.textValue().length()>MAX_SQL_PARAMETER_CHARS)return null;
            String captured=value.isTextual()?sqlText(value):null;
            // Empty strings are meaningful parameter values, unlike absent SQL text.
            if(value.isTextual()&&value.textValue().isEmpty())captured="";
            // Display-safe replacements are not exact binding evidence.
            if(value.isTextual()&&!value.textValue().equals(captured))status="truncated";
            ordered.put(index.intValue(),Json.obj("index",index.intValue(),"type",type,"value",captured,"status",status));
        }
        ArrayNode result=Json.arr();ordered.values().forEach(result::add);
        return Json.stringify(result).length()<=MAX_SQL_PARAMETER_JSON_CHARS?result:null;
    }
    private static ObjectNode sqlParameters(JsonNode attrs) {
        String rawStatus=Json.text(attrs,"sqlParametersStatus");
        String status=Evidence.choice(attrs,"sqlParametersStatus","unavailable","captured","unavailable","batch","not-applicable");
        boolean hasParameters=attrs.hasNonNull("sqlParameters")&&!attrs.path("sqlParameters").isMissingNode();
        boolean hasSets=attrs.hasNonNull("sqlParameterSets")&&!attrs.path("sqlParameterSets").isMissingNode();
        boolean knownStatus=Evidence.oneOf(rawStatus,"captured","unavailable","batch","not-applicable");
        ArrayNode parameters=null,sets=null;
        boolean invalid=!knownStatus&&(hasParameters||hasSets)||hasParameters&&hasSets
                ||"not-applicable".equals(status)&&(hasParameters||hasSets)
                ||"captured".equals(status)&&hasSets||"batch".equals(status)&&hasParameters;
        Integer omitted=boundedInteger(attrs.get("sqlParameterSetsOmitted"),Integer.MAX_VALUE);
        if(attrs.hasNonNull("sqlParameterSetsOmitted")&&!attrs.path("sqlParameterSetsOmitted").isMissingNode()&&omitted==null)invalid=true;
        if(!invalid&&("batch".equals(status)||"unavailable".equals(status)&&hasSets)) {
            JsonNode raw=parameterJson(attrs.get("sqlParameterSets"));
            if(raw==null||raw.size()>MAX_SQL_PARAMETER_SETS)invalid=true;
            else {
                sets=Json.arr();
                for(JsonNode set:raw){ArrayNode normalized=set.isArray()?parameterArray(set):null;if(normalized==null){invalid=true;break;}sets.add(normalized);}
                if(!invalid&&Json.stringify(sets).length()>MAX_SQL_PARAMETER_JSON_CHARS)invalid=true;
            }
        }else if(!invalid&&!"not-applicable".equals(status)) {
            JsonNode raw=attrs.get("sqlParameters");
            if(raw!=null&&!raw.isNull()&&!raw.isMissingNode()){parameters=parameterArray(raw);invalid=parameters==null;}
            else invalid="captured".equals(status);
        }
        if(invalid){parameters=null;sets=null;status="unavailable";}
        boolean entryTruncated=false;
        if(parameters!=null)for(JsonNode row:parameters)entryTruncated|="truncated".equals(Json.text(row,"status"));
        if(sets!=null)for(JsonNode set:sets)for(JsonNode row:set)entryTruncated|="truncated".equals(Json.text(row,"status"));
        return Json.obj("sqlParameters",parameters,"sqlParameterSets",sets,"sqlParametersStatus",status,
                "sqlParametersTruncated",invalid||entryTruncated||trueFlag(attrs.get("sqlParametersTruncated"))||omitted!=null&&omitted>0,
                "sqlParameterSetsOmitted",omitted);
    }
    private static ObjectNode nativeParameters(JsonNode attrs) {
        String status=Evidence.choice(attrs,"sqlParametersStatus","unavailable","captured","unavailable","not-applicable");
        JsonNode rows=parameterJson(attrs.get("sqlParameters"));ArrayNode result=Json.arr();Set<String>names=new HashSet<>();
        boolean invalid=attrs.hasNonNull("sqlParameterSets")&&!attrs.path("sqlParameterSets").isMissingNode()||"captured".equals(status)&&(rows==null||rows.size()>MAX_SQL_PARAMETERS)
                ||!"captured".equals(status)&&attrs.hasNonNull("sqlParameters")&&!attrs.path("sqlParameters").isMissingNode();
        boolean truncated=trueFlag(attrs.get("sqlParametersTruncated"));
        if(!invalid&&rows!=null)for(JsonNode row:rows){
            String name=Json.text(row,"name"),type=Json.text(row,"type"),state=Json.text(row,"status");JsonNode value=row.get("value");
            if(!row.isObject()||name==null||!name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")||!names.add(name)
                    ||!NATIVE_PARAMETER_TYPES.contains(type)||!Evidence.oneOf(state,"captured","null","unsupported","truncated")
                    ||(Evidence.oneOf(state,"null","unsupported")?value==null||!value.isNull():value==null||!value.isTextual()||value.textValue().length()>MAX_SQL_PARAMETER_CHARS)){invalid=true;break;}
            String clean=value.isTextual()?value.textValue().isEmpty()?"":sqlText(value):null;
            if(value.isTextual()&&!value.textValue().equals(clean))state="truncated";
            truncated|="truncated".equals(state);result.add(Json.obj("name",name,"type",type,"status",state,"value",clean));
        }
        if(Json.stringify(result).length()>MAX_SQL_PARAMETER_JSON_CHARS)invalid=true;
        return Json.obj("sqlParametersStatus",invalid?"unavailable":status,"sqlParameters",!invalid&&"captured".equals(status)?result:null,
                "sqlParametersTruncated",invalid||truncated);
    }
    /** Local viewer projections retain SQL. Compact captures and MCP call this on detached models. */
    static JsonNode withoutSql(JsonNode input) {
        JsonNode result=input.deepCopy();removeSql(result);RequestEvidence.removePayload(result);return result;
    }
    private static void removeSql(JsonNode value) {
        if(value.isObject()){
            ((ObjectNode)value).remove(Arrays.asList("sql","sqlParameters","sqlParameterSets",
                    "sqlParametersStatus","sqlParametersTruncated","sqlParameterSetsOmitted","sqlPreview","sqlPreviewTruncated","sqlDetailsAvailable","sqlParametersAvailable"));
            for(JsonNode child:value)removeSql(child);
        }else if(value.isArray())for(JsonNode child:value)removeSql(child);
    }
    public static ObjectNode boundarySummary(JsonNode event) {
        if(event==null || !event.isObject()) return null;
        String type=Json.text(event,"type"), operation=wrapper(event);
        if(!Evidence.oneOf(type,"EXTERNAL_CALL","DB_ROUTE") && operation==null) return null;
        JsonNode a=event.path("attributes"); ObjectNode r;
        if(operation!=null)
            r=Json.obj("boundaryKind","cache","system","redis","direction","outbound","destination",null,"operation",operation,
                    "status","OBSERVED","evidenceKind","wrapper-entry","errorType",null);
        else if("DB_ROUTE".equals(type))
            r=Json.obj("boundaryKind","database-route","system","database","direction","outbound",
                    "destination",destination(a.get("route")),"operation","route","status","OBSERVED","evidenceKind","routing-marker","errorType",null);
        else if(!a.has("boundaryKind"))
            r=Json.obj("boundaryKind","external","system",token(a.get("system"),TOKEN),"direction","outbound","destination",null,
                    "operation",token(a.get("detail"),OPERATION),"status","OBSERVED","evidenceKind","wrapper-entry","errorType",null);
        else r=Json.obj("boundaryKind",Evidence.choice(a,"boundaryKind","external","messaging","external","database","cache"),
                    "system",token(a.get("system"),TOKEN),"direction",Evidence.choice(a,"direction",null,"publish","consume","outbound"),
                    "destination",destination(a.get("destination")),"operation",token(a.get("operation"),OPERATION),
                    "status",Evidence.choice(a,"status",null,"ATTEMPTED","SUBMITTED","OBSERVED","ERROR"),
                    "evidenceKind",Evidence.choice(a,"evidenceKind",null,"client-boundary","wrapper-entry"),
                    "errorType",token(a.get("errorType"),TOKEN));
        if("EXTERNAL_CALL".equals(type)&&sqlExecution(a)) {
            String sql=sqlText(a.get("sql"));
            boolean nativeQuery=clickhouseQuery(a);r.put("evidenceKind",nativeQuery?"clickhouse-query":"jdbc-execution");
            r.setAll(Json.obj("sql",sql,"sqlTruncated",trueFlag(a.get("sqlTruncated"))
                    ||sql!=null&&!sql.equals(a.path("sql").textValue()),
                    "sqlSource",nativeQuery?Evidence.choice(a,"sqlSource",null,"native-query"):Evidence.choice(a,"sqlSource",null,"statement","prepared","batch","unavailable"),
                    "durationMs",nativeQuery&&"SUBMITTED".equals(Json.text(a,"status"))?null:duration(a.get("durationMs")),"batchSize",boundedInteger(a.get("batchSize"),Integer.MAX_VALUE)));
            r.set("errorType",Json.node(identity(a.get("errorType"),CLASS,256)));
            r.setAll(nativeQuery?nativeParameters(a):sqlParameters(a));
            if(nativeQuery)r.setAll(Json.obj("clientThread",token(a.get("clientThread"),THREAD),
                    "completionKind",Evidence.choice(a,"completionKind",null,"response-complete","immediate-error","observer-unavailable","capture-capacity","unavailable")));
        } else if("EXTERNAL_CALL".equals(type)&&sqlLimit(a)) {
            Integer limit=boundedInteger(a.get("queryLimit"),1000);
            r.setAll(Json.obj("evidenceKind",jdbcLimit(a)?"jdbc-query-limit":"clickhouse-query-limit","status","TRUNCATED","queryLimit",limit!=null&&limit>0?limit:null));
        }
        r.setAll(Json.obj("eventSeq",sequence(event.get("seq")),"thread",token(event.get("thread"),THREAD),"downstreamState","not-observed"));
        Long threadId=Captures.integer(event.get("threadId"),1,9007199254740991L);if(threadId!=null)r.put("threadId",threadId);
        if(a.has("sqlPreview")) {
            String preview=Json.text(a,"sqlPreview","");r.put("sqlPreview",preview.substring(0,Math.min(256,preview.length())));
            r.put("sqlDetailsAvailable",a.path("sqlDetailsAvailable").asBoolean());
        }
        return r;
    }
    public static ObjectNode extractBoundaries(JsonNode events) { return extractBoundaries(events,null); }
    public static ObjectNode extractBoundaries(JsonNode events,JsonNode aliases) {
        if(events==null || !events.isArray()) return Json.obj("externalCalls",Json.arr(),"externalCallsTruncated",true);
        Map<Integer,ObjectNode> calls=new LinkedHashMap<>();
        Map<String,List<WrapperFrame>> stacks=new LinkedHashMap<>();
        boolean limited=events.size()>MAX_EVENTS, enabled=true; Long previous=null;
        for(int i=0;i<Math.min(events.size(),MAX_EVENTS);i++) {
            JsonNode e=events.get(i);
            if(!e.isObject()){for(List<WrapperFrame>s:stacks.values())s.clear(); previous=null;continue;}
            ObjectNode summary=boundarySummary(e); Long seq=sequence(e.get("seq"));
            if(seq==null || e.path("detailGapBefore").asBoolean() || previous!=null && seq!=previous+1) for(List<WrapperFrame>s:stacks.values())s.clear();
            previous=seq;
            String name=token(e.get("thread"),THREAD);Long threadId=Captures.integer(e.get("threadId"),1,9007199254740991L);
            String thread=e.hasNonNull("threadId")?threadId==null?null:"id:"+threadId:name==null?null:"name:"+name;
            if(thread==null) for(List<WrapperFrame>s:stacks.values())s.clear();
            else if(enabled) {
                if(!stacks.containsKey(thread)) {
                    if(stacks.size()>=MAX_WRAPPER_THREADS){limited=true;enabled=false;for(List<WrapperFrame>s:stacks.values())s.clear();}
                    else stacks.put(thread,new ArrayList<WrapperFrame>());
                }
                if(enabled) {
                    List<WrapperFrame>s=stacks.get(thread);
                    String type=Json.text(e,"type"), cls=identity(e.get("className"),CLASS,4096), method=identity(e.get("methodName"),METHOD,256);
                    String descriptor=Captures.methodDescriptor(e.get("methodDescriptor"));boolean invalidDescriptor=e.hasNonNull("methodDescriptor")&&descriptor==null;
                    if("METHOD_ENTER".equals(type)) {
                        if(cls==null || method==null || seq==null || invalidDescriptor)s.clear();
                        else if(s.size()>=MAX_WRAPPER_DEPTH){limited=true;enabled=false;for(List<WrapperFrame>v:stacks.values())v.clear();}
                        else s.add(new WrapperFrame(cls,method,descriptor,i,wrapper(e)));
                    } else if(Evidence.oneOf(type,"METHOD_EXIT","EXCEPTION")) {
                        int position=-1;
                        if(seq!=null&&!invalidDescriptor)for(int j=s.size()-1;j>=0;j--)if(s.get(j).cls.equals(cls)&&s.get(j).method.equals(method)&&Objects.equals(s.get(j).descriptor,descriptor)){position=j;break;}
                        if(position<0)s.clear();else s.subList(position,s.size()).clear();
                    } else if(summary!=null && seq!=null && "cache".equals(Json.text(summary,"boundaryKind"))
                            && "redis".equals(Json.text(summary,"system")) && "client-boundary".equals(Json.text(summary,"evidenceKind"))
                            && "outbound".equals(Json.text(summary,"direction"))
                            && Evidence.oneOf(Json.text(summary,"status"),"ATTEMPTED","SUBMITTED","OBSERVED","ERROR")) {
                        for(int j=s.size()-1;j>=0;j--) {
                            WrapperFrame f=s.get(j);
                            String op="put".equals(f.wrapper)?"set":"evict".equals(f.wrapper)?"delete":f.wrapper;
                            if(op!=null&&op.equals(Json.text(summary,"operation"))){calls.remove(f.index);break;}
                        }
                    }
                }
            }
            if(summary==null)continue;
            if(calls.size()>=MAX_EXTERNAL_CALLS){limited=true;break;}
            JsonNode rawIndex=e.get("eventIndex");
            summary.put("eventIndex",rawIndex!=null&&rawIndex.isIntegralNumber()&&rawIndex.canConvertToInt()&&rawIndex.intValue()>=0?rawIndex.intValue():i);summary.set("destinationName",Json.node(aliasFor(aliases,Json.text(summary,"destination"))));
            calls.put(i,summary);
        }
        return Json.obj("externalCalls",Json.node(calls.values()),"externalCallsTruncated",limited);
    }
    private static String identity(JsonNode n,Pattern pattern,int limit){String s=token(n,pattern);return s!=null&&s.length()<=limit?s:null;}
    private static final class WrapperFrame {
        final String cls,method,descriptor,wrapper; final int index;
        WrapperFrame(String c,String m,String d,int i,String w){cls=c;method=m;descriptor=d;index=i;wrapper=w;}
    }
    public static ObjectNode sanitizeSummary(JsonNode row) {
        if(row==null||!row.isObject())return null;
        ObjectNode attrs=Json.obj(), event=Json.obj("type","EXTERNAL_CALL");
        if("database-route".equals(Json.text(row,"boundaryKind"))){event.put("type","DB_ROUTE");attrs.set("route",row.path("destination"));}
        else for(String k:new String[]{"boundaryKind","system","direction","destination","operation","status","evidenceKind","errorType",
                "sql","sqlTruncated","sqlSource","durationMs","batchSize","queryLimit","clientThread","completionKind",
                "sqlParameters","sqlParameterSets","sqlParametersStatus","sqlParametersTruncated","sqlParameterSetsOmitted"})attrs.set(k,row.path(k));
        for(String key:new String[]{"sqlPreview","sqlDetailsAvailable"})if(row.has(key))attrs.set(key,row.get(key));
        event.set("attributes",attrs);event.set("seq",row.path("eventSeq"));event.set("thread",row.path("thread"));
        if(row.hasNonNull("threadId"))event.set("threadId",row.get("threadId"));
        ObjectNode result=boundarySummary(event);result.set("destinationName",Json.node(token(row.get("destinationName"),NAME)));return result;
    }
}

/** Shared strict scalar and canonical identity helpers, not a payload projection. */
final class Evidence {
    private static final String DIGITS="[0-9](?:_?[0-9])*";
    private static final Pattern NUMERIC=Pattern.compile("[+-]?(?:"+DIGITS+"(?:\\.(?:"+DIGITS+")?)?|\\."+DIGITS+")(?:[eE][+-]?"+DIGITS+")?");
    static boolean truthy(JsonNode n){return n!=null&&!n.isNull()&&!n.isMissingNode()&&(n.isBoolean()?n.booleanValue():n.isNumber()?n.doubleValue()!=0:n.isTextual()?!n.textValue().isEmpty():!n.isContainerNode()||n.size()>0);}
    static boolean oneOf(String s,String... options){for(String o:options)if(o.equals(s))return true;return false;}
    static String choice(JsonNode n,String key,String fallback,String... options){String s=Json.text(n,key);return oneOf(s,options)?s:fallback;}
    static boolean control(int c){int t=Character.getType(c);return t==Character.CONTROL||t==Character.FORMAT||t==Character.SURROGATE||t==Character.PRIVATE_USE||t==Character.UNASSIGNED;}
    static String text(JsonNode n,int max){return n!=null&&n.isTextual()?text(n.textValue(),max):null;}
    static String text(String s,int max) {
        if(s==null)return null;
        int[] cps=s.codePoints().limit(max+1L).toArray();StringBuilder b=new StringBuilder();
        for(int c:cps)b.appendCodePoint(control(c)?32:c);
        String value=b.toString().trim();
        if(value.codePointCount(0,value.length())>max)value=value.substring(0,value.offsetByCodePoints(0,max-1))+"…";
        return value.isEmpty()?null:value;
    }
    static String key(JsonNode n){return n!=null&&n.isTextual()&&n.textValue().length()<=4096&&!n.textValue().trim().isEmpty()?n.textValue():null;}
    static Double number(JsonNode n){
        if(n==null||!(n.isNumber()||n.isTextual()))return null;
        String value=n.asText().trim();
        // Double.parseDouble also accepts Java-only hex literals and d/f suffixes;
        // those are not numeric metadata in the original JSON/Python contract.
        if(n.isTextual()&&!NUMERIC.matcher(value).matches())return null;
        try{double d=Double.parseDouble(value.replace("_",""));return Double.isFinite(d)&&d>=0?d:null;}catch(NumberFormatException e){return null;}
    }
    static Long integer(JsonNode n){Double d=number(n);return d!=null&&d==Math.floor(d)&&d<=Long.MAX_VALUE?d.longValue():null;}
    static String hash(String s){
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte v:digest)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    static String canonical(JsonNode n){StringBuilder b=new StringBuilder();canonical(n,b);return b.toString();}
    private static void canonical(JsonNode n,StringBuilder b){
        if(n==null||n.isNull()||n.isMissingNode()){b.append("null");return;}
        if(n.isObject()){
            b.append('{');TreeSet<String>names=new TreeSet<>();n.fieldNames().forEachRemaining(names::add);boolean first=true;
            for(String k:names){if(!first)b.append(',');first=false;quoted(k,b);b.append(':');canonical(n.get(k),b);}b.append('}');
        }else if(n.isArray()){
            b.append('[');boolean first=true;for(JsonNode c:n){if(!first)b.append(',');first=false;canonical(c,b);}b.append(']');
        }else if(n.isTextual())quoted(n.textValue(),b);else b.append(n.toString());
    }
    private static void quoted(String s,StringBuilder b){
        b.append('"');for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){case '"':b.append("\\\"");break;case '\\':b.append("\\\\");break;case '\n':b.append("\\n");break;case '\r':b.append("\\r");break;case '\t':b.append("\\t");break;case '\b':b.append("\\b");break;case '\f':b.append("\\f");break;default:if(c<32||c>126)b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}}b.append('"');
    }
    static String fieldText(JsonNode n,String name,int max){String s=text(n.get(name),max);return s!=null?s:text(n.path("attributes").get(name),max);}
    static String s(JsonNode n,String key){return Json.text(n,key,"");}
}
