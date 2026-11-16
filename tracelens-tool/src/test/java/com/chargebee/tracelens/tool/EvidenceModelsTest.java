package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Regression cases ported from the Python evidence/model test suites. */
class EvidenceModelsTest {
    private static final long NOW=2000000000000L;
    private static final String TRACE="11111111111111111111111111111111";
    private static ObjectNode segment(String id,String service){
        return Json.obj("traceId",TRACE,"segmentId",id,"segmentKey",service,"service",service,"serviceInstanceId",service+"-instance","parentSpanId",null,
                "entryChannel","HTTP","httpMethod","GET","path","/features/item1","routeTemplate","/features/{id}","httpStatus",500,"status","COMPLETED",
                "durationMs",30,"startedAtMillis",NOW-1000,"lastActivityMillis",NOW-950,"eventCount",2,"user","alice","siteId","42");
    }
    private static ObjectNode event(int seq,String type,String method){
        return Json.obj("seq",seq,"type",type,"thread","http-1","className","com.example.FeatureService","methodName",method,
                "nanoTime",9000000000L+seq*1000000L,"line",42,"attributes",Json.obj());
    }
    private static ObjectNode trace(ArrayNode events){return Json.obj("events",events,"eventCount",events.size(),"status","COMPLETED");}
    private static ObjectNode boundary(String direction){
        return Json.obj("seq",2,"thread","http-1","type","EXTERNAL_CALL","attributes",Json.obj("boundaryKind","messaging","system","sqs","direction",direction,
                "destination","orders","operation","sendMessage","status","SUBMITTED","evidenceKind","client-boundary"));
    }
    private static boolean warning(JsonNode model,String code){for(JsonNode w:model.path("warnings"))if(code.equals(Json.text(w,"code")))return true;return false;}
    private static void referencesResolve(JsonNode model){
        Set<String>nodes=new HashSet<>(),lanes=new HashSet<>();for(JsonNode n:model.path("nodes"))nodes.add(Json.text(n,"id"));for(JsonNode l:model.path("lanes"))lanes.add(Json.text(l,"id"));
        for(JsonNode n:model.path("nodes")){assertTrue(lanes.contains(Json.text(n,"laneId")));assertTrue(!n.hasNonNull("parentId")||nodes.contains(Json.text(n,"parentId")));}
    }
    @Test void singleInvocationMatchesReferenceSchemaAndEvidence(){
        ObjectNode input=trace(Json.arr(event(1,"METHOD_ENTER","retrieve"),event(2,"LINE",null),event(3,"METHOD_EXIT","retrieve")));
        ObjectNode original=input.deepCopy(),model=Execution.build(input,200);JsonNode node=model.path("nodes").get(0);
        assertEquals(original,input);assertFalse(model.path("partial").asBoolean());assertEquals(1,model.path("nodes").size());
        assertEquals("n_0",node.path("id").asText());assertEquals("returned",node.path("outcome").asText());assertTrue(node.path("complete").asBoolean());
        assertEquals(2d,node.path("durationMs").asDouble());assertEquals(Json.obj("index",0,"seq",1L),node.path("entry"));assertEquals(Json.obj("index",2,"seq",3L),node.path("exit"));
        assertEquals(42,node.path("observedLines").path("first").asInt());assertEquals(1,node.path("observedLines").path("count").asInt());
        assertEquals(3,model.path("counts").path("eventsProcessed").asInt());referencesResolve(model);
    }
    @Test void recursionAndThreadLanesDoNotInventAsyncEdges(){
        ArrayNode events=Json.arr(event(1,"METHOD_ENTER","root"),event(2,"METHOD_ENTER","visit"),event(3,"METHOD_ENTER","visit"),
                event(4,"METHOD_EXIT","visit"),event(5,"METHOD_EXIT","visit"),event(6,"METHOD_ENTER","other"),event(7,"METHOD_EXIT","other"),event(8,"METHOD_EXIT","root"));
        ((ObjectNode)events.get(5)).put("thread","pool-1");((ObjectNode)events.get(6)).put("thread","pool-1");
        ObjectNode model=Execution.build(trace(events),200);
        assertEquals("n_0",model.path("nodes").get(1).path("parentId").asText());assertEquals("n_1",model.path("nodes").get(2).path("parentId").asText());
        assertTrue(model.path("nodes").get(3).path("parentId").isNull());assertEquals(2,model.path("lanes").size());assertFalse(model.has("edges"));referencesResolve(model);
    }
    @Test void missingExitGapAndUnknownIdentityInvalidateNesting(){
        ObjectNode model=Execution.build(trace(Json.arr(event(1,"METHOD_ENTER","root"),event(3,"METHOD_ENTER","child"),event(4,"METHOD_EXIT","child"),event(5,"METHOD_EXIT","root"))),200);
        assertFalse(model.path("nodes").get(0).path("complete").asBoolean());assertTrue(model.path("nodes").get(0).path("durationMs").isNull());
        assertTrue(model.path("nodes").get(1).path("parentId").isNull());assertTrue(warning(model,"sequence_missing_gap_or_reordering"));assertTrue(warning(model,"unmatched_method_exit"));
        ObjectNode skipped=Execution.build(trace(Json.arr(event(1,"METHOD_ENTER","root"),event(2,"METHOD_ENTER","child"),event(3,"METHOD_EXIT","root"))),200);
        assertFalse(skipped.path("nodes").get(0).path("complete").asBoolean());assertTrue(skipped.path("nodes").get(0).path("durationMs").isNull());assertTrue(warning(skipped,"missing_method_exit"));
        referencesResolve(model);referencesResolve(skipped);
    }
    @Test void exceptionsAreTerminalAndNeverLeakMessages(){
        ObjectNode exception=event(2,"EXCEPTION","fail");exception.set("attributes",Json.obj("exceptionType","java.lang.IllegalStateException","message","SECRET"));exception.put("message","SECRET");
        ObjectNode model=Execution.build(trace(Json.arr(event(1,"METHOD_ENTER","fail"),exception)),200);JsonNode node=model.path("nodes").get(0);
        assertEquals("threw",node.path("outcome").asText());assertTrue(node.path("complete").asBoolean());assertEquals("java.lang.IllegalStateException",node.path("exceptionType").asText());
        assertFalse(Json.stringify(model).contains("SECRET"));assertEquals(1,model.path("nodes").size());
    }
    @Test void executionCapsNodesBytesAndRetainsClosedVisiblePrefix(){
        ArrayNode events=Json.arr();String label=String.join("",Collections.nCopies(256,"界"));
        for(int i=0;i<500;i++){ObjectNode enter=event(i*2+1,"METHOD_ENTER","m"+i);enter.put("className",label);ObjectNode exit=event(i*2+2,"METHOD_EXIT","m"+i);exit.put("className",label);events.add(enter);events.add(exit);}
        ObjectNode model=Execution.build(trace(events),500);assertTrue(Evidence.canonical(model).length()<=Execution.MODEL_MAX_BYTES);assertTrue(warning(model,"model_byte_limit"));
        assertTrue(model.path("counts").path("nodesOmitted").asInt()>0);referencesResolve(model);
        ObjectNode small=Execution.build(trace(events),1);assertEquals(1,small.path("nodes").size());assertTrue(small.path("nodes").get(0).path("complete").asBoolean());assertTrue(warning(small,"node_display_limit"));
        assertThrows(IllegalArgumentException.class,()->Execution.build(trace(Json.arr()),0));
        assertThrows(IllegalArgumentException.class,()->Execution.build(trace(Json.arr()),501));
    }
    @Test void malformedExecutionEventsRemainExplicitAndBounded(){
        ObjectNode model=Execution.build(Json.obj("events","payload","status","ACTIVE","truncated",true),200);
        assertEquals(0,model.path("nodes").size());assertTrue(warning(model,"missing_or_invalid_events"));assertTrue(warning(model,"active_snapshot"));assertTrue(warning(model,"capture_truncated"));
        ArrayNode events=Json.arr();for(int i=0;i<65;i++){ObjectNode e=event(i+1,"METHOD_ENTER","m");e.put("thread","lane"+i);events.add(e);}
        ObjectNode lanes=Execution.build(trace(events),200);assertEquals(64,lanes.path("lanes").size());assertTrue(warning(lanes,"lane_processing_limit"));
    }
    @Test void dependencyProjectionRemovesCredentialsPayloadsAndUntrustedContainers(){
        ObjectNode e=boundary("publish");((ObjectNode)e.get("attributes")).put("destination","https://user:SECRET@queue.example:8443/orders?token=SECRET#fragment");((ObjectNode)e.get("attributes")).put("payload","SECRET");
        ObjectNode projected=Dependencies.boundarySummary(e);assertEquals("https://queue.example:8443/orders",projected.path("destination").asText());assertFalse(Json.stringify(projected).contains("SECRET"));
        assertNull(Dependencies.destination("https://queue.example/%22"));assertNull(Dependencies.destination("orders\nsecret"));
        assertThrows(IllegalArgumentException.class,()->Dependencies.validateAliases(Json.obj("https://user:pass@queue.example/orders","Unsafe")));
        ObjectNode legacy=Dependencies.boundarySummary(Json.obj("type","EXTERNAL_CALL","attributes",Json.obj("system","Wrapper","detail","password=SECRET","destination","secret-queue")));
        assertTrue(legacy.path("operation").isNull());assertTrue(legacy.path("destination").isNull());assertEquals("wrapper-entry",legacy.path("evidenceKind").asText());
        assertTrue(Dependencies.extractBoundaries(Json.obj()).path("externalCallsTruncated").asBoolean());
    }
    @Test void onlyAuditedEnclosingWrapperIsSuppressed(){
        ObjectNode wrapper=event(1,"METHOD_ENTER","get");wrapper.put("className","com.chargebee.ems.config.cache.ValkeyCacheService");
        ObjectNode client=boundary("outbound");client.set("attributes",Json.obj("boundaryKind","cache","system","redis","direction","outbound","operation","get","status","OBSERVED","evidenceKind","client-boundary"));
        ObjectNode extraction=Dependencies.extractBoundaries(Json.arr(wrapper,client));assertEquals(1,extraction.path("externalCalls").size());assertEquals("client-boundary",extraction.path("externalCalls").get(0).path("evidenceKind").asText());
        client.put("seq",3);ObjectNode gap=Dependencies.extractBoundaries(Json.arr(wrapper,client));assertEquals(2,gap.path("externalCalls").size());
    }
    @Test void graphGoldenIdsAndLocalDurationsMatchPython(){
        ObjectNode s=segment("root","app");ObjectNode call=Json.obj("callKey","app:call:0","ownerSegmentKey","app","httpMethod","GET","peer","ems:8082","path","/features","durationNanos","9123456","httpStatus","200");
        ObjectNode input=Json.obj("segments",Json.arr(s),"httpCalls",Json.arr(call));ObjectNode original=input.deepCopy(),model=Visualization.build(input);
        assertEquals(original,input);
        assertEquals("n_s_712ccb7b813a085dd03a089c",model.path("nodes").get(0).path("id").asText());
        assertEquals("n_c_0639eafb07150d71e56b783c",model.path("nodes").get(1).path("id").asText());
        assertEquals("e_2c357c27d03e153228e9deeb",model.path("edges").get(0).path("id").asText());
        assertEquals(9.123456,model.path("nodes").get(1).path("durationMs").asDouble());assertEquals("not-observed",model.path("nodes").get(1).path("downstreamState").asText());
    }
    @Test void onlyExplicitUnambiguousGraphLinksEstablishCausality(){
        ObjectNode app=segment("root","app"),ems=segment("child","ems");ems.put("parentSpanId","span");
        ObjectNode call=Json.obj("callKey","call","ownerSegmentKey","app","spanId","span","downstreamSegmentKeys",Json.arr("ems"));
        ObjectNode input=Json.obj("segments",Json.arr(app,ems),"httpCalls",Json.arr(call));ObjectNode noLink=Visualization.build(input);
        assertEquals(1,noLink.path("edges").size());assertEquals("not-observed",noLink.path("nodes").get(2).path("downstreamState").asText());
        input.set("links",Json.arr(Json.obj("fromCallKey","call","fromSegmentKey","app","toSegmentKey","ems")));ObjectNode linked=Visualization.build(input);
        assertEquals(2,linked.path("edges").size());assertEquals("observed",linked.path("nodes").get(2).path("downstreamState").asText());
        ((ArrayNode)input.get("segments")).add(app.deepCopy());ObjectNode duplicate=Visualization.build(input);assertEquals(0,duplicate.path("edges").size());assertTrue(duplicate.path("partial").asBoolean());
    }
    @Test void dependencyDirectionsDoNotCorrelateRemoteExecutionAndMermaidIsInert(){
        ObjectNode app=segment("root","app");ObjectNode pub=boundary("publish"),consume=boundary("consume");consume.put("seq",3);
        app.set("events",Json.arr(pub,consume));app.put("path","\"]\nclick bad \"javascript:alert(1)\"\n<img src=x onerror=alert(1)>");
        ObjectNode journey=Federation.joinTrace(TRACE,Json.arr(app),Json.arr()),model=Visualization.build(journey);
        assertEquals(3,model.path("nodes").size());assertEquals(2,model.path("edges").size());Set<String>kinds=new HashSet<>();for(JsonNode e:model.path("edges"))kinds.add(Json.text(e,"kind"));assertEquals(new HashSet<>(Arrays.asList("inbound","outbound")),kinds);
        String mermaid=Visualization.toMermaid(model);assertFalse(mermaid.contains("<img"));assertFalse(mermaid.contains("\nclick "));assertFalse(mermaid.contains("javascript:"));assertTrue(mermaid.contains("remote execution not observed"));
        for(JsonNode n:model.path("nodes"))if("dependency-call".equals(Json.text(n,"kind")))assertEquals("not-observed",n.path("downstreamState").asText());
    }
    @Test void visualizationBoundsRowsAndWarningsAndIsOrderIndependent(){
        ArrayNode rows=Json.arr();for(int i=0;i<200;i++){ObjectNode s=segment("s"+i,"service"+i);s.put("payload","SECRET");rows.add(s);}
        ObjectNode model=Visualization.build(Json.obj("segments",rows,"errors",Json.arr(Json.obj("service","offline","error","down","payload","SECRET"))));
        assertEquals(128,model.path("nodes").size());assertTrue(model.path("warnings").size()<=128);assertFalse(Json.stringify(model).contains("SECRET"));assertTrue(model.path("partial").asBoolean());
        ArrayNode reversed=Json.arr();for(int i=rows.size()-1;i>=0;i--)reversed.add(rows.get(i));
        assertEquals(model,Visualization.build(Json.obj("segments",reversed,"errors",Json.arr(Json.obj("service","offline","error","down","payload","SECRET")))));
        ObjectNode malformed=Visualization.build(Json.obj("segments","bad","httpCalls",Json.arr(42)));assertEquals(0,malformed.path("nodes").size());assertTrue(malformed.path("partial").asBoolean());
    }
    @Test void errorGroupGoldenIdentityAndSiblingRequestOutcomeMatchPython(){
        ObjectNode root=segment("root","app"),child=segment("child","ems");root.put("httpStatus",200);child.put("parentSpanId","parent");child.put("httpStatus",503);child.put("durationMs",12);
        ObjectNode result=ErrorGroups.build(Json.obj("traces",Json.arr(root,child)),null,NOW);
        assertFalse(result.path("partial").asBoolean());assertEquals(1,result.path("counts").path("matchingRequests").asInt());assertEquals(1,result.path("counts").path("matchingSegments").asInt());
        JsonNode request=result.path("requests").get(0);assertEquals(200,request.path("httpStatus").asInt());assertEquals(30d,request.path("durationMs").asDouble());assertEquals("error",request.path("outcome").asText());assertEquals(2,request.path("segments").size());
        ObjectNode golden=ErrorGroups.build(Json.obj("traces",Json.arr(segment("root","app"))),null,NOW);assertEquals("eg_4d5754dd013acde130bdd695",golden.path("groups").get(0).path("id").asText());
        assertEquals(1,ErrorGroups.select(golden,"eg_4d5754dd013acde130bdd695").path("requests").size());
    }
    @Test void duplicateAndConflictingSummariesCannotInventUniqueRoot(){
        ObjectNode row=segment("root","app");ObjectNode same=ErrorGroups.build(Json.obj("traces",Json.arr(row,row.deepCopy())),null,NOW);
        assertEquals(1,same.path("counts").path("loadedSegments").asInt());assertFalse(same.path("requests").get(0).path("rootMissing").asBoolean());assertTrue(same.path("partial").asBoolean());
        ObjectNode conflicting=row.deepCopy();conflicting.put("parentSpanId","contradictory");
        ObjectNode model=ErrorGroups.build(Json.obj("traces",Json.arr(row,conflicting)),null,NOW);JsonNode request=model.path("requests").get(0);
        assertTrue(request.path("rootMissing").asBoolean());assertTrue(request.path("httpStatus").isNull());assertTrue(request.path("durationMs").isNull());assertEquals(1,model.path("groups").get(0).path("segmentCount").asInt());
    }
    @Test void clippedRoutesNeverCollapseDistinctRawIdentities(){
        String prefix=String.join("",Collections.nCopies(2100,"a"));ObjectNode one=segment("one","app"),two=segment("two","app");
        one.put("routeTemplate",prefix+"one");two.put("routeTemplate",prefix+"two");two.put("traceId","22222222222222222222222222222222");
        ObjectNode result=ErrorGroups.build(Json.obj("traces",Json.arr(one,two)),null,NOW);
        assertEquals(2,result.path("groups").size());assertEquals(result.path("groups").get(0).path("route"),result.path("groups").get(1).path("route"));
        assertNotEquals(result.path("groups").get(0).path("id"),result.path("groups").get(1).path("id"));
    }
    @Test void errorFiltersMatchOneFailingSegmentAndIdentityPairOnOneSibling(){
        ObjectNode root=segment("root","app"),child=segment("child","ems");root.put("httpStatus",200);child.put("parentSpanId","parent");child.put("user","bob");child.put("siteId","43");
        ObjectNode doc=Json.obj("traces",Json.arr(root,child));
        assertEquals(0,ErrorGroups.build(doc,Json.obj("service","app"),NOW).path("groups").size());
        assertEquals(1,ErrorGroups.build(doc,Json.obj("service","ems","user","alice","site","42"),NOW).path("groups").size());
        assertEquals(0,ErrorGroups.build(doc,Json.obj("service","ems","user","alice","site","43"),NOW).path("groups").size());
        assertThrows(IllegalArgumentException.class,()->ErrorGroups.validateFilters(Json.obj("http_status",true)));
        assertThrows(IllegalArgumentException.class,()->ErrorGroups.validateFilters(Json.obj("since_minutes","0x1.0p1")));
        assertThrows(IllegalArgumentException.class,()->ErrorGroups.validateFilters(Json.obj("min_duration","1d")));
        assertThrows(IllegalArgumentException.class,()->ErrorGroups.validateFilters(Json.obj("min_duration",10,"max_duration",2)));
        assertThrows(IllegalArgumentException.class,()->ErrorGroups.validateFilters(Json.obj("query","bad\u202e")));
        assertThrows(IllegalArgumentException.class,()->ErrorGroups.validateFilters(Json.obj("unknown","x")));
    }
    @Test void schedulerFailureIsExplicitAndExceptionOnlySuccessIsNotFailure(){
        ObjectNode row=segment("root","app");row.put("httpStatus",200);row.put("filterException","java.lang.IllegalStateException");
        assertEquals(0,ErrorGroups.build(Json.obj("traces",Json.arr(row)),null,NOW).path("groups").size());
        row.putNull("httpStatus");row.put("entryChannel","SCHEDULER");row.set("attributes",Json.obj("jobOutcome","FAILED","jobAuditStatus","RESCHEDULE_FAILED","jobFailureType","java.lang.IllegalStateException","payload","SECRET"));
        ObjectNode failed=ErrorGroups.build(Json.obj("traces",Json.arr(row)),null,NOW);assertEquals("execution",failed.path("groups").get(0).path("category").asText());assertFalse(Json.stringify(failed).contains("SECRET"));
        ((ObjectNode)row.get("attributes")).put("jobOutcome","SKIPPED");assertEquals(0,ErrorGroups.build(Json.obj("traces",Json.arr(row)),null,NOW).path("groups").size());
    }
    @Test void registryAndFederationValidateTargetsAndNeverResolvePartialUniqueness(){
        assertThrows(IllegalArgumentException.class,()->new Service("bad/name","http://localhost",null));
        assertThrows(IllegalArgumentException.class,()->new Service("app","http://user:pass@localhost",null));
        assertThrows(IllegalArgumentException.class,()->new Service("app","http://localhost:99999",null));
        assertThrows(IllegalArgumentException.class,()->new Service("app","http://localhost","relative"));
        assertThrows(IllegalArgumentException.class,()->new Federation(Arrays.asList(new Service("app","http://localhost",null),new Service("other","http://localhost:80/",null))));
        Federation federation=new Federation(Collections.singletonList(new Service("app","http://localhost",null)));
        federation.clients.put("app",new DebugClient("http://localhost"){
            public ObjectNode traces(){return Json.obj("traces",Json.arr(segment("root","app")),"partial",true);}
            public ObjectNode export(String id){fail("must not resolve an incomplete lookup");return null;}
        });
        assertTrue(federation.traces(Json.obj("path","not-found")).path("partial").asBoolean());
        assertThrows(ToolException.class,()->federation.export("root",null,null));
    }
    @Test void distributedCorrelationNeverUsesTargetsAndDropsRawEvents(){
        ObjectNode root=segment("root","app");root.set("events",Json.arr(boundary("publish")));root.put("contextParams","SECRET");
        root.set("httpCalls",Json.arr(Json.obj("spanId","span","peer","ems:8082")));ObjectNode child=segment("child","ems");child.put("parentSpanId","span");child.set("events",Json.arr());
        ObjectNode journey=Federation.joinTrace(TRACE,Json.arr(root,child),Json.arr());
        assertEquals(1,journey.path("links").size());assertEquals(1,journey.path("externalCalls").size());assertFalse(Json.stringify(journey).contains("SECRET"));
        assertFalse(journey.path("segments").get(0).has("events"));assertEquals("not-observed",journey.path("externalCalls").get(0).path("downstreamState").asText());
        root.set("httpCalls",Json.arr(Json.obj("spanId","span"),Json.obj("spanId","span")));
        assertEquals(0,Federation.joinTrace(TRACE,Json.arr(root,child),Json.arr()).path("links").size());
    }
    @Test void distributedExactLookupPreservesUpstreamUncertaintyWithoutErrorPayloads(){
        Federation federation=new Federation(Collections.singletonList(new Service("app","http://localhost",null)));
        ObjectNode segment=segment("root","app");segment.set("events",Json.arr());segment.set("httpCalls",Json.arr());
        federation.clients.put("app",new DebugClient("http://localhost"){
            public ObjectNode findByTraceId(String id){return Json.obj("traces",Json.arr(segment),"limited",true,"errors",Json.arr(Json.obj("payload","SECRET")));}
            public ObjectNode export(String id){return segment;}
            public ObjectNode overview(String id){throw new ToolException(404,"Legacy agent");}
        });
        ObjectNode journey=federation.getDistributedTrace(TRACE);
        assertTrue(journey.path("partial").asBoolean());assertEquals(1,journey.path("segments").size());assertEquals(1,journey.path("errors").size());
        assertFalse(Json.stringify(journey).contains("SECRET"));
    }
}
