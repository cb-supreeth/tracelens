package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.chargebee.tracelens.tool.Json.*;

/** Summary-first focused request selection, with explicit ambiguity. */
public final class Investigation {
    private static final int MAX_SUMMARIES=10000,MAX_CANDIDATES=20,MAX_NODES=40,MAX_FAILURES=20,MAX_BYTES=48*1024;
    private Investigation(){}
    static boolean control(int cp){int t=Character.getType(cp);return t==Character.CONTROL||t==Character.FORMAT||t==Character.SURROGATE||t==Character.PRIVATE_USE||t==Character.UNASSIGNED;}
    static String safeText(JsonNode value,int limit){
        if(value==null||!value.isTextual())return null;String s=value.textValue();StringBuilder b=new StringBuilder();int count=0;for(int i=0;i<s.length()&&count++<limit;){int cp=s.codePointAt(i);i+=Character.charCount(cp);if(control(cp))b.append(' ');else b.appendCodePoint(cp);}return b.length()==0?null:b.toString();
    }
    static ObjectNode validateText(Federation discovery,JsonNode supplied,String[]names,int[]limits){
        ObjectNode result=obj();for(int i=0;i<names.length;i++){String name=names[i];JsonNode v=supplied.get(name);if(v==null||v.isNull()){result.putNull(name);continue;}if(!v.isTextual()||v.textValue().codePointCount(0,v.textValue().length())>limits[i]||v.textValue().codePoints().anyMatch(Investigation::control))throw Captures.invalid(name+" must be text of at most "+limits[i]+" characters without controls");String s=Captures.strip(v.textValue());result.set(name,s.isEmpty()?node(null):node(s));}
        if(result.hasNonNull("service")&&!discovery.services.containsKey(text(result,"service")))throw Captures.invalid("unknown configured service");if(result.hasNonNull("method"))result.put("method",text(result,"method").toUpperCase(Locale.ROOT));return result;
    }
    static ObjectNode validate(Federation discovery,ObjectNode supplied){
        if(supplied==null)supplied=obj();ObjectNode f=validateText(discovery,supplied,new String[]{"service","method","path","user","label"},new int[]{64,32,2048,256,256});
        String trace=text(supplied,"trace_id");if(supplied.hasNonNull("trace_id")&&Captures.match(supplied.get("trace_id"),Captures.ID_RE)==null)throw Captures.invalid("invalid trace_id");
        if(!f.hasNonNull("path")&&!f.hasNonNull("label")&&trace==null)throw Captures.invalid("provide a path, scenario label, or exact trace_id to focus the investigation");
        JsonNode since=supplied.has("since_minutes")?supplied.get("since_minutes"):node(10);if(!since.isNumber()||!Double.isFinite(since.doubleValue())||since.doubleValue()<0||since.doubleValue()>525600)throw Captures.invalid("since_minutes must be a finite number between 0 and 525600");
        String outcome=supplied.has("outcome")?text(supplied,"outcome"):"all",selection=supplied.has("selection")?text(supplied,"selection"):"unique";if(!Arrays.asList("all","error","success","skipped","active","unknown").contains(outcome))throw Captures.invalid("outcome must be all, error, success, skipped, active or unknown");
        if(!Arrays.asList("unique","latest").contains(selection))throw Captures.invalid("selection must be unique or latest");f.set("since_minutes",since);f.put("outcome",outcome);f.put("selection",selection);f.set("trace_id",node(trace));return f;
    }
    static ArrayNode group(JsonNode rows,Set<String>warnings){
        if(rows==null||!rows.isArray()){warnings.add("malformed_summaries");return arr();}if(rows.size()>MAX_SUMMARIES)warnings.add("summary_limit");
        List<JsonNode>normalized=new ArrayList<>();Map<String,Set<String>>identities=new HashMap<>();Set<String>conflicts=new HashSet<>();
        for(JsonNode row:Captures.slice(rows,MAX_SUMMARIES)){
            if(!row.isObject()||Captures.match(row.get("traceId"),Captures.ID_RE)==null){warnings.add("invalid_summary_identity");continue;}
            ObjectNode segment=ErrorGroups.normalize(row);segment.set("traceId",row.get("traceId"));String key=ErrorGroups.segmentIdentity(segment),signature=Captures.canonical(segment);
            if(segment.hasNonNull("segmentId")&&!segment.path("segmentId").asText().isEmpty()){
                Set<String>seen=identities.computeIfAbsent(key,k->new HashSet<>());if(seen.contains(signature))continue;if(!seen.isEmpty()){warnings.add("conflicting_segment_identity");conflicts.add(key);}seen.add(signature);
            }else warnings.add("missing_segment_identity");normalized.add(segment);
        }return ErrorGroups.requests(normalized,conflicts);
    }
    static boolean matches(JsonNode request,ObjectNode filters,double cutoff){
        if(filters.hasNonNull("trace_id")&&!filters.get("trace_id").equals(request.get("traceId")))return false;
        if(!"all".equals(text(filters,"outcome"))&&!Objects.equals(text(filters,"outcome"),text(request,"outcome")))return false;
        for(JsonNode s:request.path("segments")){
            if(!s.hasNonNull("startedAtMillis")||s.path("startedAtMillis").asDouble()<cutoff)continue;
            boolean matches=true;for(String field:Arrays.asList("service","method"))if(filters.hasNonNull(field)&&!filters.get(field).equals(s.get(field)))matches=false;
            for(String field:Arrays.asList("path","user","label"))if(filters.hasNonNull(field)&&!Captures.casefold(s.path(field).asText("")).contains(Captures.casefold(filters.path(field).asText())))matches=false;
            if(matches)return true;
        }return false;
    }
    static ObjectNode candidate(JsonNode request){
        ArrayNode services=arr();for(JsonNode s:Captures.slice(request.path("services"),16))services.add(safeText(s,64));
        return obj("traceId",request.get("traceId"),"method",request.get("method"),"path",safeText(request.get("path"),256),"label",safeText(request.get("label"),128),
            "services",services,"serviceCount",request.path("services").size(),"segmentCount",request.path("segments").size(),"startedAtMillis",request.get("startedAtMillis"),
            "durationMs",request.get("durationMs"),"httpStatus",request.get("httpStatus"),"outcome",request.get("outcome"),"rootMissing",request.get("rootMissing"),"rootAmbiguous",request.get("rootAmbiguous"));
    }
    static boolean upstream(JsonNode doc,Set<String>warnings){
        if(doc==null||!doc.isObject()){warnings.add("malformed_upstream_result");return true;}if(Captures.truthy(doc.get("limited")))warnings.add("summary_limit");
        if(Captures.truthy(doc.get("partial"))||Captures.truthy(doc.get("errors")))warnings.add("upstream_evidence_incomplete");if(Captures.truthy(doc.get("warnings")))warnings.add("upstream_evidence_warnings");
        return Captures.truthy(doc.get("limited"))||Captures.truthy(doc.get("partial"))||Captures.truthy(doc.get("errors"));
    }
    private static ObjectNode journey(JsonNode journey,Set<String>warnings){
        ObjectNode safe=obj();for(String key:Arrays.asList("segments","httpCalls","externalCalls","links"))safe.set(key,journey.has(key)?journey.get(key):arr());
        safe.set("traceId",journey.get("traceId"));safe.put("partial",bool(journey,"partial"));safe.set("errors",arr());safe.set("warnings",arr());ObjectNode graph=Visualization.build(safe);
        if(bool(graph,"partial"))warnings.add("journey_evidence_incomplete");if(graph.path("nodes").size()>MAX_NODES)warnings.add("journey_display_limit");
        String[]fields={"id","kind","service","serviceInstanceId","segmentId","method","path","status","httpStatus","durationMs","truncated","ownerId","downstreamState","system","boundaryKind","direction","operation","destinationName","evidenceKind"};
        ArrayNode nodes=arr(),edges=arr();Set<String>kept=new HashSet<>();for(JsonNode n:Captures.slice(graph.path("nodes"),MAX_NODES)){ObjectNode out=obj();for(String key:fields)if(n.has(key))out.set(key,n.path(key).isTextual()?node(safeText(n.get(key),256)):n.get(key));nodes.add(out);kept.add(text(out,"id"));}
        for(JsonNode e:graph.path("edges"))if(kept.contains(text(e,"from"))&&kept.contains(text(e,"to")))edges.add(e);
        return obj("nodes",nodes,"edges",edges,"nodeCount",graph.path("nodes").size(),"nodesOmitted",graph.path("nodes").size()-nodes.size(),"partial",bool(graph,"partial")||graph.path("nodes").size()>nodes.size());
    }
    private static ArrayNode failures(JsonNode request,JsonNode journey,Set<String>warnings){
        ArrayNode result=arr();for(JsonNode s:request.path("segments")){ArrayNode signals=arr();if(s.path("httpStatus").asInt()<600&&s.path("httpStatus").asInt()>=400)signals.add("http_error_status");if(bool(s,"asyncTimeout"))signals.add("async_timeout");
            if(Arrays.asList("FAILED","ERROR").contains(text(s,"status")))signals.add("failed_execution_status");if("SCHEDULER".equals(text(s,"channel"))&&"FAILED".equals(text(s,"jobOutcome")))signals.add("failed_job_outcome");if(bool(s,"hasErrors")&&signals.size()==0)signals.add("summary_error_flag");if(bool(s,"hasExceptions"))signals.add("exception_observed_not_necessarily_request_failure");
            if(signals.size()>0)result.add(obj("kind","segment","service",s.get("service"),"segmentId",s.get("segmentId"),"serviceInstanceId",s.get("serviceInstanceId"),"httpStatus",s.get("httpStatus"),"signals",signals));
        }
        JsonNode calls=journey.has("httpCalls")?journey.get("httpCalls"):arr();if(!calls.isArray()){warnings.add("malformed_http_calls");calls=arr();}if(calls.size()>MAX_SUMMARIES)warnings.add("http_call_scan_limit");
        for(JsonNode call:Captures.slice(calls,MAX_SUMMARIES)){if(!call.isObject()){warnings.add("malformed_http_calls");continue;}ObjectNode s=ErrorGroups.normalize(call);if(bool(s,"hasErrors"))result.add(obj("kind","http-call","service",s.get("service"),"method",s.get("method"),"path",safeText(s.get("path"),256),"httpStatus",s.get("httpStatus"),"signals",arr(s.path("httpStatus").asInt()>=400?"http_error_status":"failed_call_status")));}
        if(result.size()>MAX_FAILURES)warnings.add("failure_evidence_display_limit");return result;
    }
    private static ObjectNode finish(ObjectNode result,Set<String>warnings){
        result.set("warnings",node(warnings));result.put("partial",!warnings.isEmpty());result.put("limited",warnings.stream().anyMatch(s->s.contains("limit")));
        while(Captures.bytes(result).length>MAX_BYTES-256){warnings.add("output_byte_limit");JsonNode detail=result.path("investigation");ArrayNode candidates=(ArrayNode)result.get("candidates");
            if(detail.isObject()&&detail.path("journey").path("nodes").size()>0){ObjectNode graph=(ObjectNode)detail.get("journey");ArrayNode nodes=(ArrayNode)graph.get("nodes");String removed=text(nodes.remove(nodes.size()-1),"id");ArrayNode edges=arr();for(JsonNode e:graph.path("edges"))if(!removed.equals(text(e,"from"))&&!removed.equals(text(e,"to")))edges.add(e);graph.set("edges",edges);graph.put("nodesOmitted",graph.path("nodesOmitted").asInt()+1);graph.put("partial",true);}
            else if(candidates.size()>1)candidates.remove(candidates.size()-1);else if(detail.path("failureEvidence").size()>0){ArrayNode f=(ArrayNode)detail.get("failureEvidence");f.remove(f.size()-1);}else break;
            result.set("warnings",node(warnings));result.put("partial",true);result.put("limited",true);
        }
        ((ObjectNode)result.get("counts")).put("candidatesReturned",result.path("candidates").size());return result;
    }
    public static ObjectNode investigateRequest(Federation discovery,ObjectNode supplied){
        return investigateRequest(discovery,supplied,System.currentTimeMillis(),
            ()->discovery.traces(obj("limit",MAX_SUMMARIES)),discovery::getDistributedTrace);
    }
    /** Injectable clock/read seams keep reference tests deterministic and offline. */
    static ObjectNode investigateRequest(Federation discovery,ObjectNode supplied,double now,
            java.util.function.Supplier<JsonNode> summaries,java.util.function.Function<String,JsonNode> journeyReader){
        ObjectNode filters=validate(discovery,supplied);Set<String>warnings=new TreeSet<>();double cutoff=now-filters.path("since_minutes").asDouble()*60000;
        String trace=text(filters,"trace_id"),selection=text(filters,"selection");JsonNode journey=trace==null?null:journeyReader.apply(trace),doc=trace==null?summaries.get():journey;
        boolean incomplete=upstream(doc,warnings);ArrayNode requests=group(doc==null?null:doc.get(trace==null?"traces":"segments"),warnings);incomplete|=!warnings.isEmpty();List<JsonNode>matching=new ArrayList<>();for(JsonNode request:requests)if(matches(request,filters,cutoff))matching.add(request);
        ArrayNode candidates=arr();for(int i=0;i<Math.min(matching.size(),MAX_CANDIDATES);i++)candidates.add(candidate(matching.get(i)));
        ObjectNode result=obj("schemaVersion",1,"kind","request-investigation","scope","retained-only","status","not_found","selectedTraceId",null,"selection",selection,"candidates",candidates,
            "counts",obj("loadedRequests",requests.size(),"matchingRequests",matching.size(),"candidatesReturned",candidates.size()),"investigation",null,"nextSteps",arr(),
            "note","Live retained observations only, not all traffic or proof of non-execution. Failure signals are not root-cause findings. Durations are local elapsed time; cross-service clocks are not assumed synchronized.");
        if(matching.size()>MAX_CANDIDATES)warnings.add("candidate_display_limit");
        if(matching.isEmpty()){result.set("nextSteps",arr("Reproduce the scenario with recording enabled, or adjust the API/label and time window.","Not found means not observed in the available retained scope, not that it did not execute."));return finish(result,warnings);}
        JsonNode selected=null;if(trace!=null||matching.size()==1&&!incomplete)selected=matching.get(0);
        else if("latest".equals(selection)&&!incomplete){boolean known=true;for(JsonNode r:matching)known&=r.hasNonNull("startedAtMillis");if(known&&matching.size()>1&&matching.get(0).path("startedAtMillis").asDouble()>matching.get(1).path("startedAtMillis").asDouble())selected=matching.get(0);else warnings.add("latest_timestamp_ambiguous");}
        if(selected==null){result.put("status","ambiguous");result.set("nextSteps",arr("Narrow path, scenario label, service, user or time; or explicitly select a candidate trace_id.","Use selection=latest only when choosing the newest matching request is intended."));return finish(result,warnings);}
        result.put("status","selected");result.set("selectedTraceId",selected.get("traceId"));
        if(journey==null){journey=journeyReader.apply(text(selected,"traceId"));upstream(journey,warnings);}if(journey==null||!journey.isObject())journey=obj();
        JsonNode current=null;for(JsonNode r:group(journey.get("segments"),warnings))if(selected.get("traceId").equals(r.get("traceId"))){current=r;break;}
        if(current==null){warnings.add("selected_request_no_longer_available");current=selected;}else if(!matches(current,filters,cutoff))warnings.add("selected_request_changed_since_discovery");
        if(bool(current,"rootMissing"))warnings.add("root_missing_or_ambiguous");for(JsonNode s:current.path("segments")){if(bool(s,"truncated")||bool(s,"httpCallsTruncated"))warnings.add("capture_truncated");if("ACTIVE".equals(text(s,"status")))warnings.add("active_request_snapshot");}
        ArrayNode evidence=failures(current,journey,warnings);boolean failure=false;for(JsonNode item:evidence)if(!(item.path("signals").size()==1&&"exception_observed_not_necessarily_request_failure".equals(item.path("signals").get(0).asText())))failure=true;
        ObjectNode request=candidate(current);if(failure)request.put("outcome","error");
        result.set("investigation",obj("request",request,"journey",journey(journey,warnings),"failureEvidence",Captures.slice(evidence,MAX_FAILURES),"failureEvidenceCount",evidence.size(),"hasObservedFailure",failure));
        for(int i=0;i<candidates.size();i++)if(candidates.get(i).get("traceId").equals(selected.get("traceId")))candidates.set(i,request.deepCopy());
        result.set("nextSteps",arr("Inspect an observed failure segment with get_flow_summary or get_segment_execution using its service and serviceInstanceId.","Use get_flow for source evidence only when needed; do not infer a root cause from summary signals."));return finish(result,warnings);
    }
}
