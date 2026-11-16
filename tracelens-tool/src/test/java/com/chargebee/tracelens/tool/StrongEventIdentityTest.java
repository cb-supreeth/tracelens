package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.TreeSet;
import static com.chargebee.tracelens.tool.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class StrongEventIdentityTest {
    private static ObjectNode event(int seq,String kind,String method,long thread,String descriptor) {
        ObjectNode event=CaptureWorkflowTest.event(seq,kind,method).put("thread","worker").put("threadId",thread);
        if(descriptor!=null)event.put("methodDescriptor",descriptor);return event;
    }
    private static ObjectNode segment(ObjectNode...events) {
        ObjectNode segment=CaptureWorkflowTest.segment();segment.set("events",arr((Object[])events));segment.put("eventCount",events.length);return segment;
    }
    private static ObjectNode capture(ObjectNode segment) {
        ObjectNode result=Captures.base(CaptureWorkflowTest.SEGMENT,CaptureWorkflowTest.TRACE,"test",null,"2026-10-28T00:00:00+00:00");
        ((ArrayNode)result.get("segments")).add(Captures.projectSegment("app",segment,1000));Captures.finish(result,new TreeSet<>());Captures.validateCapture(result);return result;
    }
    private static JsonNode nodeByMethod(JsonNode model,String name) {
        for(JsonNode node:model.path("nodes"))if(name.equals(node.path("methodName").asText())&&"invocation".equals(node.path("kind").asText()))return node;
        throw new AssertionError(name);
    }

    @Test void sameNameThreadsAreDifferentLanesWithoutInventedNesting() {
        ObjectNode model=Execution.build(segment(event(1,"METHOD_ENTER","first",1,"()V"),event(2,"METHOD_ENTER","second",2,"()V"),
                event(3,"METHOD_EXIT","first",1,"()V"),event(4,"METHOD_EXIT","second",2,"()V")),50);
        assertEquals(2,model.path("lanes").size());assertFalse(model.path("partial").asBoolean(),stringify(model.path("warnings")));
        for(JsonNode node:model.path("nodes")){assertTrue(node.path("parentId").isNull());assertTrue(node.path("complete").asBoolean());}
        assertEquals("runtime-thread-id",model.path("lanes").get(0).path("identity").asText());
        assertNotEquals(model.path("nodes").get(0).path("laneId"),model.path("nodes").get(1).path("laneId"));
    }

    @Test void renamedOrUnnamedRuntimeThreadStaysInTheSameLane() {
        ObjectNode first=event(1,"METHOD_ENTER","outer",1,"()V");first.remove("thread");
        ObjectNode model=Execution.build(segment(first,event(2,"METHOD_ENTER","inner",1,"(I)V").put("thread","renamed"),
                event(3,"LINE",null,1,null).put("line",12).put("thread","renamed-again"),
                event(4,"METHOD_EXIT","inner",1,"(I)V"),event(5,"METHOD_EXIT","outer",1,"()V")),50);
        assertEquals(1,model.path("lanes").size());assertFalse(model.path("partial").asBoolean(),stringify(model.path("warnings")));
        assertEquals(nodeByMethod(model,"outer").path("id"),nodeByMethod(model,"inner").path("parentId"));
        assertEquals(1,nodeByMethod(model,"inner").path("observedLines").path("count").asInt());
    }

    @Test void descriptorsDisambiguateOverloadedExitsAndDoNotInventMissingReturns() {
        ObjectNode model=Execution.build(segment(event(1,"METHOD_ENTER","read",1,"(I)V"),event(2,"METHOD_ENTER","read",1,"(Ljava/lang/String;)V"),
                event(3,"METHOD_EXIT","read",1,"(I)V")),50);
        JsonNode outer=model.path("nodes").get(0),inner=model.path("nodes").get(1);
        assertEquals(3,outer.path("exit").path("seq").asInt());assertFalse(outer.path("complete").asBoolean());assertTrue(outer.path("durationMs").isNull());
        assertTrue(inner.path("exit").isNull());assertTrue(model.path("warnings").toString().contains("missing_method_exit"));
        assertEquals("(I)V",outer.path("methodDescriptor").asText());
    }

    @Test void exactOverloadExceptionIsMatchedAndPaginatedIdentityIsPreserved() {
        ObjectNode enter=event(1,"METHOD_ENTER","read",1,"(I)V"),nested=event(2,"METHOD_ENTER","read",1,"()V"),
                thrown=event(3,"EXCEPTION","read",1,"()V"),exit=event(4,"METHOD_EXIT","read",1,"(I)V");
        thrown.set("attributes",obj("exceptionType","java.lang.IllegalStateException"));
        ObjectNode overview=segment(enter,nested,thrown,exit);overview.remove("events");
        JsonNode model=Execution.buildPages(overview,50,Execution.MODEL_MAX_BYTES,Arrays.asList(obj("events",arr(enter,nested)),obj("events",arr(thrown,exit))));
        assertEquals("threw",model.path("nodes").get(1).path("outcome").asText());assertEquals("returned",model.path("nodes").get(0).path("outcome").asText());
        assertFalse(model.path("partial").asBoolean(),stringify(model.path("warnings")));
    }

    @Test void legacyIdentityIsRetainedButNeverMatchedToAnEnrichedExit() {
        ObjectNode legacy=CaptureWorkflowTest.event(1,"METHOD_ENTER","read").put("thread","worker");
        ObjectNode model=Execution.build(segment(legacy,event(2,"METHOD_EXIT","read",1,"()V")),50);
        assertEquals(2,model.path("lanes").size());assertFalse(model.path("nodes").get(0).path("complete").asBoolean());
        assertTrue(model.path("warnings").toString().contains("mixed_thread_identity_precision"));
        ObjectNode sameLane=event(1,"METHOD_ENTER","read",1,null);
        model=Execution.build(segment(sameLane,event(2,"METHOD_EXIT","read",1,"()V")),50);
        assertFalse(model.path("nodes").get(0).path("complete").asBoolean());assertTrue(model.path("warnings").toString().contains("mixed_method_identity_precision"));
        ObjectNode old=Execution.build(segment(legacy,CaptureWorkflowTest.event(2,"METHOD_EXIT","read").put("thread","worker")),50);
        assertFalse(old.path("partial").asBoolean());assertEquals("thread-name",old.path("lanes").get(0).path("identity").asText());
        assertFalse(old.path("lanes").get(0).has("threadId"));assertFalse(old.path("nodes").get(0).has("methodDescriptor"));
    }

    @Test void invalidExplicitIdentityDoesNotFallBackToNamesOrNestAsLegacyEvidence() {
        ObjectNode invalid=event(2,"METHOD_EXIT","read",1,"()V").put("threadId","1");
        JsonNode model=Execution.build(segment(event(1,"METHOD_ENTER","read",1,"()V"),invalid),50);
        assertFalse(model.path("nodes").get(0).path("complete").asBoolean());assertTrue(model.path("warnings").toString().contains("invalid_thread_identity"));
        ObjectNode bad=segment(event(1,"METHOD_ENTER","outer",1,"()V"),event(2,"METHOD_ENTER","read",1,"broken"),event(3,"METHOD_EXIT","outer",1,"()V"));
        JsonNode projected=Captures.projectSegment("app",bad,100);
        assertEquals(1,projected.path("methods").size());assertEquals(0,projected.path("nesting").size());assertTrue(projected.path("warnings").toString().contains("invalid_method_descriptor_omitted"));
    }

    @Test void capturedMethodAndNestingIdentitiesRetainDescriptorsWithoutThreadNamesOrIds() {
        ObjectNode capture=capture(segment(event(1,"METHOD_ENTER","read",1,"(I)V"),event(2,"METHOD_ENTER","read",1,"()V"),
                event(3,"METHOD_EXIT","read",1,"()V"),event(4,"METHOD_EXIT","read",1,"(I)V")));
        JsonNode projected=capture.path("segments").get(0);assertEquals(2,projected.path("methods").size());
        assertEquals("(I)V",projected.path("nesting").get(0).path("parentMethodDescriptor").asText());assertEquals("()V",projected.path("nesting").get(0).path("methodDescriptor").asText());
        assertFalse(stringify(capture).contains("\"threadId\""));assertFalse(stringify(capture).contains("worker"));
        Captures.validateCapture(parse(Captures.bytes(capture)));assertTrue(capture.path("limitations").toString().contains("Captured JVM descriptors"));
        ObjectNode bad=capture.deepCopy();((ObjectNode)bad.path("segments").get(0).path("methods").get(0)).put("methodDescriptor","(V)V");assertThrows(ToolException.class,()->Captures.validateCapture(bad));
        Captures.validateCapture(CaptureWorkflowTest.capture());
    }

    @Test void comparisonKeepsOverloadsSeparateAndPreservesExactMethodReferences() {
        ObjectNode before=capture(segment(event(1,"METHOD_ENTER","read",1,"(I)V"),event(2,"METHOD_EXIT","read",1,"(I)V"),
                event(3,"METHOD_ENTER","read",1,"()V"),event(4,"METHOD_EXIT","read",1,"()V")));
        ObjectNode after=capture(segment(event(1,"METHOD_ENTER","read",1,"()V"),event(2,"METHOD_EXIT","read",1,"()V")));
        JsonNode methods=BehaviorComparison.build(before,after,"before","after").path("methods");assertEquals(1,methods.size());
        assertEquals("(I)V",methods.get(0).path("methodDescriptor").asText());assertEquals("(I)V",methods.get(0).path("before").get(0).path("methodDescriptor").asText());
        assertEquals("before_only",methods.get(0).path("disposition").asText());
        assertEquals("(I)V",Captures.compareCaptures(before,after).path("methods").path("noLongerObserved").get(0).path("methodDescriptor").asText());
    }

    @Test void parentGroupingDoesNotCollapseDistinctOverloadsOrGuessLegacyDescriptors() {
        ObjectNode before=capture(segment(event(1,"METHOD_ENTER","read",1,"(I)V"),event(2,"METHOD_ENTER","read",1,"()V"),
                event(3,"METHOD_EXIT","read",1,"()V"),event(4,"METHOD_EXIT","read",1,"(I)V")));
        ObjectNode after=capture(segment(event(1,"METHOD_ENTER","read",1,"(I)V"),event(2,"METHOD_EXIT","read",1,"(I)V")));
        JsonNode root=BehaviorComparison.build(before,after,"before","after").path("methods").get(0);
        assertEquals("(I)V",root.path("methodDescriptor").asText());assertEquals("descendants_changed",root.path("disposition").asText());
        assertEquals("()V",root.path("nestedChanges").get(0).path("methodDescriptor").asText());
        ObjectNode legacy=capture(segment(CaptureWorkflowTest.event(1,"METHOD_ENTER","read"),CaptureWorkflowTest.event(2,"METHOD_EXIT","read")));
        JsonNode mixed=BehaviorComparison.build(legacy,after,"before","after");assertEquals(2,mixed.path("methods").size());assertTrue(mixed.path("warnings").toString().contains("mixed_method_identity_precision"));
        ((ObjectNode)before.path("segments").get(0).path("nesting").get(0)).remove("parentMethodDescriptor");
        root=BehaviorComparison.build(before,after,"before","after").path("methods").get(0);
        assertEquals("()V",root.path("methodDescriptor").asText());assertEquals(0,root.path("nestedChangedCount").asInt());
    }

    @Test void descriptorsAreBoundedTypedIdentitiesNotArbitraryText() {
        for(String value:new String[]{"()V","(I)Ljava/lang/String;","([[I[Ljava/lang/String;)J","(ZBCSFDJ)D","()Lsample/Outer$Inner;"})assertEquals(value,Captures.methodDescriptor(node(value)),value);
        for(String value:new String[]{"","(V)V","()VV","([V)V","(Ljava/lang/String)V","()L;","()Ljava//lang;","()Ljava.lang.String;","()Lbad\u0000Name;","("+"I".repeat(3000)+")V"})assertNull(Captures.methodDescriptor(node(value)),value);
    }

    @Test void optionalRecordingManifestAndAnalysisCoverageAreStrictButLegacyCapturesRemainValid() {
        ObjectNode captured=CaptureWorkflowTest.capture();ObjectNode segment=(ObjectNode)captured.path("segments").get(0);
        segment.set("recordingManifest",obj("schemaVersion",1,"applicationVersion","local-build","agentBuildId","a".repeat(64),"configurationFingerprint","b".repeat(64)));
        segment.set("analysisCoverage",obj("savedEvents",100,"retainedEvents",5,"rawBytesRead",4096,"complete",false));
        Captures.validateCapture(captured);
        for(ObjectNode manifest:new ObjectNode[]{obj("schemaVersion",2),obj("schemaVersion",1,"agentBuildId","not-a-digest"),obj("schemaVersion",1,"applicationVersion","bad\nlabel"),obj("schemaVersion",1,"secret","value")}){
            ObjectNode invalid=captured.deepCopy();((ObjectNode)invalid.path("segments").get(0)).set("recordingManifest",manifest);assertThrows(ToolException.class,()->Captures.validateCapture(invalid));
        }
        for(ObjectNode coverage:new ObjectNode[]{obj("savedEvents",4,"retainedEvents",5,"rawBytesRead",0,"complete",false),obj("savedEvents",5,"retainedEvents",5,"rawBytesRead",-1,"complete",true),obj("savedEvents",5,"retainedEvents",5,"rawBytesRead",1,"complete","yes"),obj("savedEvents",5,"retainedEvents",5,"complete",true)}){
            ObjectNode invalid=captured.deepCopy();((ObjectNode)invalid.path("segments").get(0)).set("analysisCoverage",coverage);assertThrows(ToolException.class,()->Captures.validateCapture(invalid));
        }
        Captures.validateCapture(CaptureWorkflowTest.capture());
    }

    @Test void directCaptureProjectionPreservesOnlyAllowlistedRecordingProvenance() {
        ObjectNode raw=segment(event(1,"METHOD_ENTER","read",1,"()V"),event(2,"METHOD_EXIT","read",1,"()V"));
        raw.set("recordingManifest",obj("schemaVersion",1,"applicationVersion","local-build","agentBuildId","a".repeat(64),"configurationFingerprint","b".repeat(64),"secret","not-copied"));
        JsonNode projected=capture(raw).path("segments").get(0);assertEquals(4,projected.path("recordingManifest").size());assertFalse(projected.toString().contains("not-copied"));
        raw.set("recordingManifest",obj("schemaVersion",1,"applicationVersion","bad\nlabel","agentBuildId","not-a-digest"));
        assertEquals(obj("schemaVersion",1),capture(raw).path("segments").get(0).path("recordingManifest"));
    }

    @Test void cacheWrapperSuppressionKeepsRuntimeThreadsAndOverloadsSeparate() {
        ObjectNode wrapper=event(1,"METHOD_ENTER","get",1,"(I)V").put("className","com.chargebee.ems.config.cache.ValkeyCacheService");
        ObjectNode client=event(2,"EXTERNAL_CALL",null,2,null);client.set("attributes",obj("boundaryKind","cache","system","redis","direction","outbound","operation","get","status","OBSERVED","evidenceKind","client-boundary"));
        assertEquals(2,Dependencies.extractBoundaries(arr(wrapper,client)).path("externalCalls").size());
        client.put("threadId",1).put("thread","renamed");
        JsonNode calls=Dependencies.extractBoundaries(arr(wrapper,client)).path("externalCalls");assertEquals(1,calls.size());assertEquals(1,calls.get(0).path("threadId").asInt());
        assertEquals(1,Dependencies.sanitizeSummary(calls.get(0)).path("threadId").asInt());
        client.put("threadId","1");assertEquals(2,Dependencies.extractBoundaries(arr(wrapper,client)).path("externalCalls").size());
        client.remove("threadId");assertEquals(2,Dependencies.extractBoundaries(arr(wrapper,client)).path("externalCalls").size());
        ObjectNode nested=wrapper.deepCopy().put("seq",2).put("methodDescriptor","()V"),exit=wrapper.deepCopy().put("seq",3).put("type","METHOD_EXIT");
        client.put("seq",4).put("threadId",1);
        assertEquals(3,Dependencies.extractBoundaries(arr(wrapper,nested,exit,client)).path("externalCalls").size());
        exit.put("methodDescriptor","broken");assertEquals(3,Dependencies.extractBoundaries(arr(wrapper,nested,exit,client)).path("externalCalls").size());
        JsonNode model=Execution.build(segment(wrapper,client),50);JsonNode boundary=model.path("nodes").get(1).path("boundary");assertEquals(1,boundary.path("threadId").asInt());
    }
}
