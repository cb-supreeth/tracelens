package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.chargebee.tracelens.tool.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeRequestEvidenceTest {
    @TempDir Path temporary;
    private static ObjectNode nativeEvent() {
        return obj("type","EXTERNAL_CALL","seq",2,"thread","callback-1","attributes",obj("boundaryKind","database","system","clickhouse",
                "direction","outbound","operation","query","evidenceKind","clickhouse-query","status","OBSERVED","sqlSource","native-query",
                "sql","SELECT * FROM usage WHERE site={site:String}","durationMs","12.5","clientThread","worker-1","completionKind","response-complete",
                "sqlParametersStatus","captured","sqlParameters",stringify(arr(obj("name","site","type","String","value","private-binding","status","captured")))));
    }
    @Test void nativeSqlHasDistinctBoundedProjectionAndCompactCapturesKeepItPrivate() {
        ObjectNode event=nativeEvent(),full=Dependencies.boundarySummary(event);
        assertTrue(Dependencies.clickhouseQuery(full));assertFalse(Dependencies.jdbcExecution(full));
        assertEquals("native-query",full.path("sqlSource").asText());assertEquals(12.5,full.path("durationMs").asDouble());
        assertEquals("site",full.path("sqlParameters").get(0).path("name").asText());
        assertEquals(full,Dependencies.sanitizeSummary(full).deepCopy().without("destinationName"));
        assertFalse(stringify(Captures.dependency(event)).contains("private-binding"));
        assertFalse(stringify(Dependencies.withoutSql(full)).contains("private-binding"));
        ((ObjectNode)event.get("attributes")).put("evidenceKind","client-boundary");
        assertFalse(Dependencies.boundarySummary(event).has("sql"));
    }
    @Test void malformedNativeBindingsAndLimitsNeverSmugglePayloadValues() {
        ObjectNode event=nativeEvent(),a=(ObjectNode)event.get("attributes");
        a.set("sqlParameters",arr(obj("index",1,"type","String","value","DO-NOT-PRINT","status","captured")));
        ObjectNode projected=Dependencies.boundarySummary(event);
        assertEquals("unavailable",projected.path("sqlParametersStatus").asText());assertTrue(projected.path("sqlParametersTruncated").asBoolean());
        assertFalse(stringify(projected).contains("DO-NOT-PRINT"));
        a.put("evidenceKind","clickhouse-query-limit").put("operation","query-limit").put("status","TRUNCATED").put("queryLimit","100");
        projected=Dependencies.boundarySummary(event);assertTrue(Dependencies.sqlLimit(projected));assertFalse(projected.has("sql"));assertFalse(projected.has("sqlParameters"));
    }
    @Test void nativeSubmissionOnlyEvidenceKeepsSqlButNeverClaimsDurationOrCompletion() {
        ObjectNode event=nativeEvent(),a=(ObjectNode)event.get("attributes");
        a.put("status","SUBMITTED").put("completionKind","capture-capacity");
        ObjectNode projected=Dependencies.boundarySummary(event);
        assertTrue(Dependencies.clickhouseQuery(projected));assertEquals("SUBMITTED",text(projected,"status"));
        assertEquals("capture-capacity",text(projected,"completionKind"));assertTrue(projected.path("durationMs").isNull());
        assertTrue(projected.hasNonNull("sql"));assertEquals(projected,Dependencies.sanitizeSummary(projected).deepCopy().without("destinationName"));
    }
    @Test void completionEvidenceDoesNotAcquireUnrelatedCallbackNesting() {
        ObjectNode trace=obj("events",arr(obj("type","METHOD_ENTER","seq",1,"thread","callback-1","className","sample.Unrelated","methodName","run","nanoTime",1),nativeEvent()));
        ObjectNode execution=Execution.build(trace,200);
        assertEquals(2,execution.path("nodes").size());assertTrue(execution.path("nodes").get(1).path("parentId").isNull());
        assertTrue(stringify(execution.path("limitations")).contains("response-future completion"));
    }
    @Test void fullSnapshotsKeepRequestEvidenceButDiscoveryAndCompactSearchNeverReceiveIt() throws Exception {
        ObjectNode row=CaptureWorkflowTest.segment();row.put("service","app");row.put("segmentKey",stringify(arr("app","instance-1",CaptureWorkflowTest.SEGMENT)));
        row.set("attributes",obj("path","/features","httpRequestBody","{\"name\":\"private-body\"}","httpRequestBodyStatus","captured",
                "httpRequestBodyFormat","json","httpQueryParameters","{\"id\":[\"private-query\"]}","httpQueryParametersStatus","captured",
                "httpPayloadRedaction","disabled"));
        ObjectNode journey=Federation.joinTrace(CaptureWorkflowTest.TRACE,arr(row),arr());
        assertFalse(stringify(journey).contains("private-body"));assertFalse(stringify(journey).contains("private-query"));
        ObjectNode input=CaptureWorkflowTest.snapshotInput();input.set("journey",journey);input.set("segments",arr(row));
        ViewerSnapshotStore store=new ViewerSnapshotStore(temporary.toRealPath().resolve("snapshots"),CaptureWorkflowTest.NAMESPACE);
        ObjectNode loaded=store.get(text(store.save(input),"id"));
        assertTrue(stringify(loaded.path("segments")).contains("private-body"));
        assertEquals("disabled",loaded.path("segments").get(0).path("attributes").path("httpPayloadRedaction").asText());
        assertFalse(stringify(store.list()).contains("private-body"));
        assertFalse(stringify(CaptureTools.projectViewerSnapshot(loaded,null,null)).contains("private-body"));
        assertFalse(stringify(Dependencies.withoutSql(loaded)).contains("private-query"));
        assertFalse(stringify(RequestEvidence.summary(row)).contains("private-body"));
        for (ObjectNode summary:new ObjectNode[]{journey,RequestEvidence.summary(row),CaptureTools.projectViewerSnapshot(loaded,null,null)})
            assertFalse(stringify(summary).contains("httpPayloadRedaction"));
    }
    @Test void requestPolicyIsRemovedRecursivelyFromSummariesWithoutModifyingSavedEvidence() {
        ObjectNode original=obj("attributes",obj("httpPayloadRedaction","disabled","httpPayloadRedacted","true"),
                "segments",arr(obj("attributes",obj("httpPayloadRedaction","enabled","path","/features"))));
        ObjectNode summary=RequestEvidence.summary(original);
        assertFalse(stringify(summary).contains("httpPayloadRedaction"));
        assertFalse(stringify(summary).contains("httpPayloadRedacted"));
        assertEquals("/features",summary.path("segments").get(0).path("attributes").path("path").asText());
        assertEquals("disabled",original.path("attributes").path("httpPayloadRedaction").asText());
        assertEquals("enabled",original.path("segments").get(0).path("attributes").path("httpPayloadRedaction").asText());
    }
}
