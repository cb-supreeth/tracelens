package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.*;
import static com.chargebee.tracelens.tool.Json.*;

/** Exact-path, summary-only diagnosis. Never export events or change recording. */
public final class GuidedInvestigation {
    private static final int MAX_SUMMARIES=1000,MAX_REPORT_BYTES=128*1024;
    private static final String PATH_BAD=".*[\\s?#*\\\\,\\[\\]{}].*";
    private GuidedInvestigation(){}
    public static ObjectNode validateFilters(Federation discovery,ObjectNode supplied){
        if(supplied==null)supplied=obj();ObjectNode f=Investigation.validateText(discovery,supplied,new String[]{"path","method","service","user","site"},new int[]{2048,32,64,256,256});
        String path=text(f,"path");boolean valid=path!=null&&path.startsWith("/")&&!path.startsWith("//")&&!path.matches(PATH_BAD)&&path.matches("[\\x00-\\x7f]*")&&!path.matches(".*%(?![0-9A-Fa-f]{2}).*");
        if(valid)try{valid=!URLDecoder.decode(path.replace("+","%2B"),"UTF-8").codePoints().anyMatch(Investigation::control);}catch(Exception e){valid=false;}
        if(!valid)throw Captures.invalid("path must be an exact absolute backend path without a URL, query, fragment, whitespace or wildcards");
        if(f.hasNonNull("method")&&!text(f,"method").matches("[A-Z][A-Z0-9_-]{0,31}"))throw Captures.invalid("method must be a single HTTP method token");return f;
    }
    private static JsonNode paths(JsonNode v){
        if(v==null||!v.isArray()||v.size()>64)return node(null);for(JsonNode p:v){String s=Captures.text(p);if(s==null||!s.startsWith("/")||s.length()>512||s.matches(PATH_BAD)||!s.matches("[!-~]*"))return node(null);}return v;
    }
    private static JsonNode boolValue(JsonNode v){return v!=null&&v.isBoolean()?v:node(null);}
    private static void check(ArrayNode checks,String service,String id,String status,String message,ObjectNode details){checks.add(obj("service",service,"id",id,"status",status,"message",message,"details",details));}
    private static final class Report {ObjectNode service;ArrayNode rows;Set<String>warnings;Report(ObjectNode s,ArrayNode r,Set<String>w){service=s;rows=r;warnings=w;}}
    private static Report service(Federation discovery,String name,String path){
        DebugClient client=discovery.clients.get(name);JsonNode summaryProbe=Doctor.probe(client,"summary"),metadataProbe=Doctor.probe(client,"metadata");
        JsonNode summary=summaryProbe.path("document"),metadata=metadataProbe.path("document"),summaryError=summaryProbe.path("error"),metadataError=metadataProbe.path("error");
        ArrayNode checks=arr();Set<String>warnings=new TreeSet<>();if(!summaryError.isNull()||!metadataError.isNull())warnings.add("diagnostic_evidence_unavailable");
        boolean reachable=summaryError.isNull();
        check(checks,name,"reachability",reachable?"pass":"unknown",reachable?"The configured debug summary endpoint responded.":"The configured debug summary could not be read; check the debug URL, service startup and access policy.",obj("reachable",reachable,"httpStatus",summaryError.get("httpStatus")));
        check(checks,name,"metadata",metadataError.isNull()?"pass":"unknown",metadataError.isNull()?"Running agent metadata was inspected.":"Running metadata is unavailable; local configuration files are not evidence of effective settings.",obj());
        for(String key:Arrays.asList("agent","recording")){
            String field=key.equals("agent")?"agentActive":"recordingEnabled";JsonNode value=boolValue(summary.has(field)?summary.get(field):metadata.get(field));
            if(metadata.has(field)&&summary.has(field)&&!boolValue(metadata.get(field)).equals(value))value=node(null);
            boolean yes=value.isBoolean()&&value.booleanValue(),no=value.isBoolean()&&!value.booleanValue();
            check(checks,name,key,yes?"pass":no?(key.equals("recording")?"excluded":"fail"):"unknown",yes?field+" is enabled for eligible new requests.":no?field+" reports false now; the current capture setup is not ready.":field+" is unknown or inconsistent; no capture readiness is assumed.",obj("value",value));
        }
        List<JsonNode>available=new ArrayList<>();for(JsonNode doc:Arrays.asList(summary,metadata))if(doc.has("httpIncludePaths"))available.add(paths(doc.get("httpIncludePaths")));
        JsonNode configured=available.isEmpty()?node(null):available.get(0);for(JsonNode p:available)if(!p.equals(configured))configured=node(null);
        Boolean allowed=null;if(configured.isArray()){allowed=false;for(JsonNode p:configured){String prefix=p.asText().replaceAll("/+$","");if(prefix.isEmpty()||path.equals(prefix)||path.startsWith(prefix+"/"))allowed=true;}}
        check(checks,name,"http_path",Boolean.TRUE.equals(allowed)?"pass":Boolean.FALSE.equals(allowed)?"excluded":"unknown",
            Boolean.TRUE.equals(allowed)?"The exact path is included by the running HTTP entry-path configuration.":Boolean.FALSE.equals(allowed)?"The running httpIncludePaths excludes this path. This proves current exclusion, not why a past request is absent.":"Effective httpIncludePaths is missing, malformed or inconsistent; inclusion is unknown.",
            obj("httpIncludePaths",configured,"included",allowed));
        JsonNode required=boolValue(metadata.get("requireLabels"));check(checks,name,"labels",required.isNull()?"unknown":required.booleanValue()?"warn":"pass",
            required.isNull()?"The running label requirement is unknown; an unlabeled request may not be captured.":required.booleanValue()?"Running requireLabels is enabled. Reproduce with an approved TraceLens label; user/site filters do not supply a label.":"Running metadata does not require request labels.",obj("requireLabels",required));
        JsonNode packages=metadata.path("packages");boolean valid=packages.isArray()&&packages.size()<=64;for(JsonNode p:packages)valid&=p.isTextual()&&p.asText().length()<=256&&p.asText().matches("[A-Za-z_$][A-Za-z0-9_$.]*");
        JsonNode line=boolValue(metadata.get("lineCaptureEnabled"));if(line.isNull()&&valid)line=node(packages.size()>0);boolean disabled=line.isBoolean()&&!line.booleanValue();
        check(checks,name,"scope",disabled?"warn":"unknown",disabled?"No line-capture package scope is enabled; HTTP boundary evidence may still be available.":"Package scope alone cannot establish that this API's implementation is instrumented. Verify the backend handler, package scope and compiled line tables.",obj("lineCaptureEnabled",line,"packageCount",valid?packages.size():null));
        check(checks,name,"retention","unknown","Missing retained data does not prove expiry or non-execution. Idle expiry, capacity eviction, exclusion and an unobserved request remain possible.",obj("idleTtlMinutes",Captures.integer(metadata.get("idleTtlMinutes"),1,Long.MAX_VALUE),"maxTrackedRequests",Captures.integer(summary.get("maxTrackedRequests"),1,Long.MAX_VALUE)));
        ArrayNode selected=arr();
        try{
            JsonNode doc=client.request("GET","/traces",1024*1024,5000),rows=doc.get("traces");if(rows==null||!rows.isArray())throw Captures.invalid("invalid summaries");
            if(rows.size()>MAX_SUMMARIES||bool(doc,"limited"))warnings.add("summary_limit");if(bool(doc,"partial")||doc.path("errors").size()>0)warnings.add("summary_evidence_incomplete");
            for(JsonNode row:Captures.slice(rows,MAX_SUMMARIES)){if(!row.isObject()){warnings.add("malformed_summary");continue;}ObjectNode s=copy(row);s.put("service",name);selected.add(s);}
        }catch(RuntimeException e){warnings.add("summary_evidence_unavailable");}
        return new Report(obj("service",name,"checks",checks),selected,warnings);
    }
    public static ObjectNode diagnoseRequest(Federation discovery,ObjectNode supplied){
        ObjectNode filters=validateFilters(discovery,supplied);List<String>names=filters.hasNonNull("service")?Collections.singletonList(text(filters,"service")):new ArrayList<>(discovery.services.keySet());
        if(names.isEmpty()||names.size()>32)throw Captures.invalid("diagnosis requires 1–32 configured services");
        ArrayNode reports=arr(),rows=arr(),checks=arr();Set<String>warnings=new TreeSet<>();ExecutorService pool=Executors.newFixedThreadPool(Math.min(8,names.size()));
        try{List<Future<Report>>pending=new ArrayList<>();for(String name:names)pending.add(pool.submit(()->service(discovery,name,text(filters,"path"))));
            for(Future<Report>future:pending){Report r;try{r=future.get();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ToolException(503,"Diagnosis interrupted.");}catch(ExecutionException e){throw new ToolException(503,"Diagnostic evidence could not be inspected.");}
                reports.add(r.service);rows.addAll(r.rows);checks.addAll((ArrayNode)r.service.get("checks"));warnings.addAll(r.warnings);}
        }finally{pool.shutdownNow();}
        if(rows.size()>MAX_SUMMARIES)warnings.add("summary_limit");ArrayNode requests=Investigation.group(Captures.slice(rows,MAX_SUMMARIES),warnings),candidates=arr();int matching=0;
        for(JsonNode request:requests){boolean matches=false;for(JsonNode s:request.path("segments")){
            boolean selected="HTTP".equals(text(s,"channel"))&&filters.get("path").equals(s.get("path"));
            for(String key:Arrays.asList("method","service","user","site"))if(filters.hasNonNull(key)&&!filters.get(key).equals(s.get(key)))selected=false;
            if(selected){matches=true;break;}
        }if(matches){matching++;if(candidates.size()<20)candidates.add(Investigation.candidate(request));}}
        ObjectNode result=obj("schemaVersion",1,"kind","guided-investigation","scope","retained-only","filters",filters,"services",reports,"checks",checks,"status",matching>0?"retained_matches":"no_retained_match",
            "candidates",candidates,"counts",obj("loadedRequests",requests.size(),"matchingRequests",matching),
            "guidance",arr("Use the backend API path, not the browser page route. Matching is exact and case-sensitive.","HTTP prefix inclusion does not override built-in debug, health or static-resource exclusions; these can depend on the running adapter.",
                "These checks read running metadata and retained summaries only; nothing was enabled, restarted or reconfigured.","Reproduce one matching request after explicitly arming a 60- or 120-second watch. Close or cancel the watch to stop it.","A saved capture is point-in-time evidence and can be partial; saving cannot recover already-evicted data."));
        if(matching>20)warnings.add("candidate_display_limit");for(JsonNode c:checks)if("unknown".equals(text(c,"status"))&&!Arrays.asList("scope","retention").contains(text(c,"id")))warnings.add("diagnostic_evidence_unknown");
        if(Captures.bytes(result).length>MAX_REPORT_BYTES-1024){warnings.add("report_detail_limit");for(JsonNode check:checks)((ObjectNode)check.get("details")).remove("httpIncludePaths");}
        result.set("warnings",node(warnings));result.put("partial",!warnings.isEmpty());result.put("limited",warnings.stream().anyMatch(s->s.contains("limit")));((ObjectNode)result.get("counts")).put("candidatesReturned",candidates.size());return result;
    }
}
