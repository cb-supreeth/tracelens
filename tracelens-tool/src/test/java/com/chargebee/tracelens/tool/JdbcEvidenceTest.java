package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.chargebee.tracelens.tool.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** JDBC evidence remains local, bounded and distinct from routing or arbitrary external payloads. */
class JdbcEvidenceTest {
    private static final String SQL="SELECT id, '</script><img src=x onerror=alert(1)>'\nFROM features WHERE name = 'private-literal' AND site_id = ?";
    @TempDir Path temporary;

    static ObjectNode jdbc(int seq,String system,String sql) {
        return obj("seq",seq,"type","EXTERNAL_CALL","thread","http-1","nanoTime",seq*1000000L,
                "className","com.example.FeatureRepository","methodName","find","line",42,
                "attributes",obj("boundaryKind","database","system",system,"direction","outbound","operation","executeQuery",
                        "status","OBSERVED","evidenceKind","jdbc-execution","sql",sql,"sqlTruncated","false","sqlSource","prepared","durationMs","12.25"));
    }
    private static ObjectNode attrs(ObjectNode event){return (ObjectNode)event.get("attributes");}
    private static ObjectNode limit(int seq){ObjectNode event=jdbc(seq,"postgresql",SQL);attrs(event).put("evidenceKind","jdbc-query-limit").put("operation","query-limit").put("status","TRUNCATED").put("queryLimit","100");return event;}
    private static ObjectNode method(int seq,String type){return obj("seq",seq,"type",type,"thread","http-1","nanoTime",seq*1000000L,"className","com.example.FeatureRepository","methodName","find","line",42,"attributes",obj());}
    private static ObjectNode segment(ArrayNode events){ObjectNode row=CaptureWorkflowTest.segment();row.set("events",events);row.put("eventCount",events.size());row.put("service","app");row.put("segmentKey",stringify(arr("app","instance-1",CaptureWorkflowTest.SEGMENT)));return row;}
    private static ObjectNode parameter(int index,String type,String value,String status){return obj("index",index,"type",type,"value",value,"status",status);}
    private static ObjectNode withParameters(ObjectNode event){attrs(event).put("sqlParametersStatus","captured").put("sqlParameters",stringify(arr(parameter(1,"String","private-bind-value","captured"))));return event;}

    @Test void jdbcBindingsAcceptRawAndCanonicalArraysWithoutInventingInterpolatedSql() {
        ArrayNode expected=arr(parameter(1,"String","O'Reilly ? <script>\n","captured"),parameter(2,"Null",null,"null"),
                parameter(3,"BinaryStream",null,"unsupported"),parameter(4,"String","","captured"));
        ObjectNode event=jdbc(2,"postgresql","SELECT ? WHERE id = ?");attrs(event).put("sqlParametersStatus","captured");
        attrs(event).put("sqlParameters",stringify(expected));ObjectNode result=Dependencies.boundarySummary(event);
        assertEquals(expected,result.path("sqlParameters"));assertEquals("SELECT ? WHERE id = ?",result.path("sql").asText());
        assertEquals("captured",result.path("sqlParametersStatus").asText());assertFalse(result.path("sqlParametersTruncated").asBoolean());
        assertEquals(result,Dependencies.sanitizeSummary(result).deepCopy().without("destinationName"));
        attrs(event).set("sqlParameters",expected);assertEquals(result,Dependencies.boundarySummary(event));
        attrs(event).put("sqlParametersStatus","unavailable");assertEquals(expected,Dependencies.boundarySummary(event).path("sqlParameters"));
        attrs(event).put("sqlParametersStatus","not-applicable");assertTrue(Dependencies.boundarySummary(event).path("sqlParameters").isNull());
    }
    @Test void jdbcBindingsRejectMalformedDuplicateOversizedAndNonSetterValues() {
        ObjectNode event=withParameters(jdbc(1,"mysql","SELECT ?"));
        ArrayNode tooMany=arr();for(int i=1;i<=129;i++)tooMany.add(parameter(i,"Int","1","captured"));
        ArrayNode tooLarge=arr();for(int i=1;i<=20;i++)tooLarge.add(parameter(i,"String",String.join("",Collections.nCopies(512,"x")),"captured"));
        for(JsonNode invalid:Arrays.asList(obj("private","payload"),arr(1),arr(parameter(0,"String","x","captured")),
                arr(parameter(129,"String","x","captured")),arr(parameter(1,"getObject","private","captured")),
                arr(parameter(1,"Object","private","unsupported")),arr(parameter(1,"Null","private","null")),
                arr(parameter(1,"String",null,"captured")),arr(parameter(1,"String","x","bad-status")),
                arr(parameter(1,"String","x","captured"),parameter(1,"String","y","captured")),
                arr(parameter(1,"String",String.join("",Collections.nCopies(513,"x")),"captured")),tooMany,tooLarge,
                Json.node("[{\"index\":1,\"index\":2,\"type\":\"Int\",\"value\":\"3\",\"status\":\"captured\"}]"),Json.node("not-json"))) {
            attrs(event).set("sqlParameters",invalid);ObjectNode projected=Dependencies.boundarySummary(event);
            assertTrue(projected.path("sqlParameters").isNull());assertEquals("unavailable",projected.path("sqlParametersStatus").asText());
            assertTrue(projected.path("sqlParametersTruncated").asBoolean());assertFalse(stringify(projected).contains("private"));
        }
        attrs(event).set("sqlParameters",arr(parameter(1,"String","a\u202eb\u001bc","truncated")));
        assertEquals("a b c",Dependencies.boundarySummary(event).path("sqlParameters").get(0).path("value").asText());
    }
    @Test void displaySanitizationMarksSingleAndBatchBindingsIncomplete() {
        ArrayNode source=arr(parameter(1,"String","a\u202eb\u001bc","captured"),parameter(2,"String","","captured"),
                parameter(3,"String","line\n\r\t\ud83d\ude00","captured"),parameter(4,"Null",null,"null"),
                parameter(5,"BinaryStream",null,"unsupported"));
        ArrayNode expected=source.deepCopy();((ObjectNode)expected.get(0)).put("value","a b c").put("status","truncated");
        for(boolean batch:new boolean[]{false,true})for(boolean serialized:new boolean[]{false,true}) {
            ObjectNode event=jdbc(1,"postgresql","SELECT ?");
            attrs(event).put("sqlParametersStatus",batch?"batch":"captured");
            ArrayNode bindings=batch?arr(source):source;
            attrs(event).set(batch?"sqlParameterSets":"sqlParameters",serialized?Json.node(stringify(bindings)):bindings);
            ObjectNode projected=Dependencies.boundarySummary(event);
            assertEquals(batch?arr(expected):expected,projected.path(batch?"sqlParameterSets":"sqlParameters"));
            assertTrue(projected.path("sqlParametersTruncated").asBoolean());
            assertEquals(projected,Dependencies.sanitizeSummary(projected).deepCopy().without("destinationName"));
        }
        assertEquals("captured",source.get(0).path("status").asText());
    }
    @Test void displaySanitizationMarksSqlIncompleteWithoutChangingSafeFormatting() {
        ObjectNode event=jdbc(1,"postgresql","SELECT '\u202e\u001b'\n\r\tFROM t");
        ObjectNode projected=Dependencies.boundarySummary(event);
        assertEquals("SELECT '  '\n\r\tFROM t",projected.path("sql").asText());
        assertTrue(projected.path("sqlTruncated").asBoolean());
        assertEquals(projected,Dependencies.sanitizeSummary(projected).deepCopy().without("destinationName"));
        for(String exact:Arrays.asList("SELECT '\ud83d\ude00'\n\r\tFROM t","")) {
            attrs(event).put("sql",exact);projected=Dependencies.boundarySummary(event);
            assertFalse(projected.path("sqlTruncated").asBoolean());
            if(!exact.isEmpty())assertEquals(exact,projected.path("sql").asText());
        }
    }
    @Test void jdbcBatchBindingSetsAreBoundedAndRemainSeparateFromCurrentBindings() {
        ObjectNode event=withParameters(jdbc(2,"mysql","INSERT INTO features VALUES (?)"));
        ArrayNode expected=arr(arr(parameter(1,"String","first","captured")),arr(parameter(1,"String","second","captured")));
        attrs(event).put("operation","executeBatch").put("sqlParametersStatus","batch").put("sqlParameterSets",stringify(expected)).put("sqlParameterSetsOmitted","3");
        attrs(event).remove("sqlParameters");
        ObjectNode projected=Dependencies.boundarySummary(event);
        assertEquals(expected,projected.path("sqlParameterSets"));assertTrue(projected.path("sqlParameters").isNull());
        assertEquals(3,projected.path("sqlParameterSetsOmitted").asInt());assertTrue(projected.path("sqlParametersTruncated").asBoolean());
        assertEquals(projected,Dependencies.sanitizeSummary(projected).deepCopy().without("destinationName"));
        attrs(event).put("sqlParametersStatus","unavailable");assertEquals(expected,Dependencies.boundarySummary(event).path("sqlParameterSets"));
        attrs(event).put("sqlParametersStatus","batch");
        ArrayNode excess=arr();for(int i=0;i<11;i++)excess.add(expected.get(0));attrs(event).set("sqlParameterSets",excess);
        projected=Dependencies.boundarySummary(event);assertTrue(projected.path("sqlParameterSets").isNull());assertEquals("unavailable",projected.path("sqlParametersStatus").asText());
        ObjectNode marker=withParameters(limit(3));assertFalse(Dependencies.boundarySummary(marker).has("sqlParameters"));
        ObjectNode route=withParameters(jdbc(4,"mysql",SQL));attrs(route).put("boundaryKind","cache");assertFalse(Dependencies.boundarySummary(route).has("sqlParameters"));
    }
    @Test void contradictoryBindingMetadataFailsClosed() {
        for(String status:Arrays.asList("captured","batch","unavailable","not-applicable","unknown")) {
            ObjectNode event=withParameters(jdbc(1,"mysql","SELECT ?"));
            attrs(event).put("sqlParametersStatus",status).set("sqlParameterSets",arr(arr(parameter(1,"Int","1","captured"))));
            ObjectNode result=Dependencies.boundarySummary(event);assertTrue(result.path("sqlParameters").isNull());assertTrue(result.path("sqlParameterSets").isNull());
            assertEquals("unavailable",result.path("sqlParametersStatus").asText());assertTrue(result.path("sqlParametersTruncated").asBoolean());
        }
        for(String omitted:Arrays.asList("-1","1.5","2147483648","invalid")) {
            ObjectNode event=withParameters(jdbc(1,"mysql","SELECT ?"));attrs(event).put("sqlParameterSetsOmitted",omitted);
            ObjectNode result=Dependencies.boundarySummary(event);assertTrue(result.path("sqlParameters").isNull());assertTrue(result.path("sqlParametersTruncated").asBoolean());
        }
    }

    @Test void onlyDriverExecutionRetainsRawSqlWithoutBindingsRowsOrMessages() {
        for(String system:Arrays.asList("postgresql","mysql")) {
            ObjectNode event=jdbc(2,system,SQL);attrs(event).put("payload","private-payload").put("errorMessage","private-error");
            attrs(event).set("bindings",arr("private-bind"));attrs(event).set("rows",arr(obj("name","private-row")));
            ObjectNode result=Dependencies.boundarySummary(event);
            assertEquals(SQL,result.path("sql").asText());assertEquals(12.25,result.path("durationMs").asDouble());
            assertEquals("jdbc-execution",result.path("evidenceKind").asText());assertEquals("prepared",result.path("sqlSource").asText());
            assertFalse(result.path("sqlTruncated").asBoolean());
            for(String unwanted:Arrays.asList("private-payload","private-error","private-bind","private-row"))assertFalse(stringify(result).contains(unwanted));
            assertEquals(result,Dependencies.sanitizeSummary(result).deepCopy().without("destinationName"));
        }
    }
    @Test void arbitraryOrForgedDependencyKindsDoNotAcquireSqlFields() {
        for(String[] change:new String[][]{{"boundaryKind","cache"},{"system","redis"},{"system","PostgreSQL"},{"direction","publish"},
                {"evidenceKind","client-boundary"},{"evidenceKind","wrapper-entry"},{"operation","prepareStatement"},{"status","SUBMITTED"}}) {
            ObjectNode event=jdbc(2,"postgresql",SQL);attrs(event).put(change[0],change[1]);ObjectNode result=Dependencies.boundarySummary(event);
            assertFalse(result.has("sql"),Arrays.toString(change));assertFalse(result.has("durationMs"));assertFalse(result.has("sqlSource"));
        }
        ObjectNode route=jdbc(2,"postgresql",SQL);route.put("type","DB_ROUTE");attrs(route).put("route","TEST_MASTER");
        assertEquals("routing-marker",Dependencies.boundarySummary(route).path("evidenceKind").asText());assertFalse(Dependencies.boundarySummary(route).has("sql"));
        ObjectNode legacy=jdbc(2,"postgresql",SQL);attrs(legacy).remove("boundaryKind");assertFalse(Dependencies.boundarySummary(legacy).has("sql"));
    }
    @Test void sqlAndMetadataAreBoundedAndMalformedValuesCannotEscapeTheirTypes() {
        String longSql=String.join("",Collections.nCopies(Dependencies.MAX_SQL_CHARS,"x"))+"private-tail";
        ObjectNode event=jdbc(2,"mysql",longSql);attrs(event).put("batchSize","2147483647").put("errorType","java.sql.SQLException").put("durationMs","1.2E-4");
        ObjectNode result=Dependencies.boundarySummary(event);
        assertEquals(Dependencies.MAX_SQL_CHARS,result.path("sql").asText().length());assertTrue(result.path("sqlTruncated").asBoolean());
        assertEquals(0.00012,result.path("durationMs").asDouble());assertEquals(Integer.MAX_VALUE,result.path("batchSize").asInt());
        assertEquals("java.sql.SQLException",result.path("errorType").asText());assertFalse(stringify(result).contains("private-tail"));
        attrs(event).put("sql","SELECT '\u001b[31m'\n\tFROM t").put("sqlTruncated","false").put("durationMs","NaN").put("batchSize","-1").put("errorType","SQL failed: private-error").put("sqlSource","private-source");
        result=Dependencies.boundarySummary(event);assertEquals("SELECT ' [31m'\n\tFROM t",result.path("sql").asText());
        assertTrue(result.path("durationMs").isNull());assertTrue(result.path("batchSize").isNull());assertTrue(result.path("errorType").isNull());assertTrue(result.path("sqlSource").isNull());
        attrs(event).set("sql",obj("private","value"));attrs(event).set("durationMs",arr(1));attrs(event).put("sqlTruncated","truthy");
        result=Dependencies.boundarySummary(event);assertTrue(result.path("sql").isNull());assertFalse(result.path("sqlTruncated").asBoolean());assertTrue(result.path("durationMs").isNull());
    }
    @Test void graphAndExecutionPreserveQueryEvidenceAndCallingCoordinatesWithoutInventingDatabaseReturns() {
        ObjectNode route=method(2,"DB_ROUTE");attrs(route).put("route","TEST_MASTER");
        ObjectNode row=segment(arr(method(1,"METHOD_ENTER"),route,withParameters(jdbc(3,"postgresql",SQL)),method(4,"METHOD_EXIT")));
        ObjectNode journey=Federation.joinTrace(CaptureWorkflowTest.TRACE,arr(row),arr()),graph=Visualization.build(journey);
        assertEquals(2,journey.path("externalCalls").size());assertEquals(3,graph.path("nodes").size());assertEquals(2,graph.path("edges").size());
        JsonNode query=null,routing=null;for(JsonNode n:graph.path("nodes")){if(Dependencies.jdbcExecution(n))query=n;if("database-route".equals(text(n,"boundaryKind")))routing=n;}
        assertNotNull(query);assertNotNull(routing);assertEquals(SQL,query.path("sql").asText());assertEquals(3,query.path("eventSeq").asInt());
        assertEquals("private-bind-value",query.path("sqlParameters").get(0).path("value").asText());
        assertEquals(CaptureWorkflowTest.SEGMENT,query.path("segmentId").asText());assertEquals("SQL query · postgresql executeQuery",query.path("label").asText());
        assertEquals("not-observed",query.path("downstreamState").asText());assertEquals(routing.path("ownerId"),query.path("ownerId"));
        assertFalse(Visualization.toMermaid(graph).contains("private-literal"));assertFalse(Visualization.toMermaid(graph).contains("<img"));
        ObjectNode execution=Execution.build(row,200);JsonNode marker=execution.path("nodes").get(2);
        assertEquals(SQL,marker.path("boundary").path("sql").asText());assertEquals("n_0",marker.path("parentId").asText());
        assertEquals(query.path("sqlParameters"),marker.path("boundary").path("sqlParameters"));
        assertEquals("com.example.FeatureRepository",marker.path("className").asText());assertEquals("find",marker.path("methodName").asText());
        assertEquals(obj("index",2,"seq",3L),marker.path("evidence"));
        assertTrue(stringify(execution.path("limitations")).contains("does not prove transaction commit"));
    }
    @Test void explicitQueryLimitIsNotAnotherQueryAndNeverRetainsInjectedSqlOrDuration() {
        ObjectNode event=limit(2),projected=Dependencies.boundarySummary(event);assertTrue(Dependencies.jdbcLimit(projected));assertFalse(Dependencies.jdbcExecution(projected));
        assertEquals(100,projected.path("queryLimit").asInt());assertFalse(projected.has("sql"));assertFalse(projected.has("durationMs"));
        for(String invalid:Arrays.asList("0","-1","1001","1.5","999999999999999999999")){attrs(event).put("queryLimit",invalid);assertTrue(Dependencies.boundarySummary(event).path("queryLimit").isNull());}
        ObjectNode row=segment(arr(method(1,"METHOD_ENTER"),limit(2),method(3,"METHOD_EXIT")));
        assertTrue(stringify(Execution.build(row,200).path("warnings")).contains("jdbc_query_capture_limit"));
        ObjectNode graph=Visualization.build(Federation.joinTrace(CaptureWorkflowTest.TRACE,arr(row),arr()));
        assertTrue(stringify(graph.path("warnings")).contains("JDBC query capture limit reached"));assertFalse(stringify(graph).contains("private-literal"));
    }
    @Test void executionKeepsSqlBeyondNormalDisplayLabelLengthButStillHonorsModelByteBudget() {
        String sql="SELECT '"+String.join("",Collections.nCopies(4096,"a"))+"'";
        ObjectNode model=Execution.build(segment(arr(jdbc(1,"mysql",sql))),200);
        assertEquals(sql,model.path("nodes").get(0).path("boundary").path("sql").asText());
        ArrayNode events=arr();for(int i=1;i<=100;i++)events.add(jdbc(i,"mysql",sql));
        model=Execution.build(segment(events),200);assertTrue(Evidence.canonical(model).length()<=Execution.MODEL_MAX_BYTES);
        assertTrue(model.path("counts").path("nodesOmitted").asInt()>0);assertTrue(stringify(model.path("warnings")).contains("model_byte_limit"));
    }
    @Test void compactCapturesRemainSqlFreeWithTypedQueryMetadataAndLimitWarnings() {
        ObjectNode row=segment(arr(method(1,"METHOD_ENTER"),withParameters(jdbc(2,"mysql",SQL)),limit(3),method(4,"METHOD_EXIT")));
        ObjectNode capture=Captures.buildCapture(CaptureWorkflowTest.discovery(new CaptureWorkflowTest.Client(row)),CaptureWorkflowTest.TRACE,"jdbc",null);
        Captures.validateCapture(capture);Captures.validateCapture(parse(Captures.bytes(capture)));
        assertFalse(stringify(capture).contains("private-literal"));assertTrue(stringify(capture).contains("jdbc_query_capture_limit"));
        assertFalse(stringify(capture).contains("private-bind-value"));
        JsonNode query=null;for(JsonNode d:capture.path("segments").get(0).path("dependencies"))if(Dependencies.jdbcExecution(d))query=d;
        assertNotNull(query);assertEquals(12.25,query.path("durationMs").asDouble());assertEquals("prepared",query.path("sqlSource").asText());
        assertFalse(query.has("sql"));
    }
    @Test void explicitViewerSnapshotRetainsSqlButItsListingAndCompactProjectionDoNot() throws Exception {
        ObjectNode input=CaptureWorkflowTest.snapshotInput(),row=segment(arr(method(1,"METHOD_ENTER"),withParameters(jdbc(2,"postgresql",SQL)),method(3,"METHOD_EXIT")));
        ObjectNode journey=Federation.joinTrace(CaptureWorkflowTest.TRACE,arr(row),arr());journey.set("visualization",Visualization.build(journey));
        input.set("journey",journey);input.set("segments",arr(row));
        ViewerSnapshotStore store=new ViewerSnapshotStore(temporary.toRealPath().resolve("snapshots"),CaptureWorkflowTest.NAMESPACE);
        ObjectNode saved=store.save(input),loaded=store.get(text(saved,"id"));
        assertEquals(SQL,loaded.path("segments").get(0).path("events").get(1).path("attributes").path("sql").asText());
        assertEquals(SQL,loaded.path("journey").path("externalCalls").get(0).path("sql").asText());
        assertEquals("private-bind-value",loaded.path("journey").path("externalCalls").get(0).path("sqlParameters").get(0).path("value").asText());
        assertFalse(stringify(store.list()).contains("private-literal"));
        assertFalse(stringify(store.list()).contains("private-bind-value"));
        ObjectNode compact=CaptureTools.projectViewerSnapshot(loaded,null,null);Captures.validateCapture(compact);assertFalse(stringify(compact).contains("private-literal"));
        assertTrue(Dependencies.withoutSql(loaded).path("journey").path("externalCalls").get(0).hasNonNull("durationMs"));
        assertFalse(stringify(Dependencies.withoutSql(loaded)).contains("private-literal"));assertTrue(stringify(loaded).contains("private-literal"));
        assertFalse(stringify(compact).contains("private-bind-value"));assertFalse(stringify(Dependencies.withoutSql(loaded)).contains("private-bind-value"));
    }
    @Test void terminalOutputNeverCollapsesDifferentQueriesOrPrintsSqlLiterals() {
        ObjectNode first=jdbc(1,"mysql",SQL),second=jdbc(2,"mysql","DELETE FROM other WHERE value='private-second'");attrs(second).put("operation","executeUpdate");
        String rendered=Renderer.render(segment(arr(first,second,limit(3))),null,false,true);
        assertTrue(rendered.contains("JDBC mysql executeQuery"));assertTrue(rendered.contains("JDBC mysql executeUpdate"));assertTrue(rendered.contains("query-limit"));
        assertTrue(rendered.contains("12.25 ms client-side"));assertFalse(rendered.contains("private-literal"));assertFalse(rendered.contains("private-second"));assertFalse(rendered.contains("(x"));
    }
}
