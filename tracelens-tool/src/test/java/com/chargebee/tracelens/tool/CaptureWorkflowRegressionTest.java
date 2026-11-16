package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import static com.chargebee.tracelens.tool.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class CaptureWorkflowRegressionTest {
    @TempDir Path temp;
    @BeforeEach void canonicalTemp()throws Exception {temp=temp.toRealPath();}
    private static ObjectNode segment(){return CaptureWorkflowTest.segment();}
    private static ObjectNode capture(){return CaptureWorkflowTest.capture();}
    private static Federation discovery(CaptureWorkflowTest.Client client){return CaptureWorkflowTest.discovery(client);}
    private static String namespace(){return CaptureWorkflowTest.NAMESPACE;}

    @Test void oldAgentLookupFallbackIsReadOnlyAndOnlyForNotFound(){
        CaptureWorkflowTest.Client legacy=new CaptureWorkflowTest.Client(segment()){
            @Override public ObjectNode request(String method,String path,int cap,int timeout){
                if(path.startsWith("/find-by-trace-id/")){calls.add(method+" "+path);throw new ToolException(404,"missing");}
                return super.request(method,path,cap,timeout);
            }
        };
        ObjectNode result=Captures.buildCapture(discovery(legacy),CaptureWorkflowTest.TRACE,"old agent",null);
        assertEquals(1,result.path("segments").size());
        assertTrue(legacy.calls.stream().anyMatch(s->s.equals("GET /traces")));
        assertTrue(legacy.calls.stream().filter(s->s.contains("/export/")).allMatch(s->s.endsWith("?evict=false")));
        CaptureWorkflowTest.Client denied=new CaptureWorkflowTest.Client(segment()){
            @Override public ObjectNode request(String method,String path,int cap,int timeout){
                calls.add(method+" "+path);throw new ToolException(403,"SECRET auth body");
            }
        };
        ToolException error=assertThrows(ToolException.class,()->Captures.buildCapture(discovery(denied),CaptureWorkflowTest.TRACE,"denied",null));
        assertFalse(error.getMessage().contains("SECRET"));assertEquals(1,denied.calls.size());
    }

    @Test void restartedServiceOrWrongExportCannotBeCapturedAsOriginal(){
        for(String field:Arrays.asList("traceId","segmentId","serviceInstanceId")){
            CaptureWorkflowTest.Client client=new CaptureWorkflowTest.Client(segment()){
                @Override public ObjectNode request(String method,String path,int cap,int timeout){
                    ObjectNode result=super.request(method,path,cap,timeout);
                    if(path.startsWith("/export/"))result.put(field,"changed");
                    return result;
                }
            };
            assertThrows(ToolException.class,()->Captures.buildCapture(discovery(client),CaptureWorkflowTest.TRACE,"identity test",null),field);
        }
    }

    @Test void duplicateAndInvalidSummariesAreWarningsNotExtraExports(){
        ObjectNode raw=segment(),bad=segment();bad.put("serviceInstanceId","");
        CaptureWorkflowTest.Client client=new CaptureWorkflowTest.Client(raw,raw.deepCopy(),bad);
        ObjectNode result=Captures.buildCapture(discovery(client),CaptureWorkflowTest.TRACE,"deduplicated",null);
        assertEquals(1,result.path("segments").size());assertTrue(result.path("partial").asBoolean());
        assertEquals(arr("app:duplicate_segment_summary","app:invalid_segment_identity"),result.path("warnings"));
        assertEquals(1,client.calls.stream().filter(s->s.contains("/export/")).count());
    }

    @Test void invalidMetadataNeverReadsOrCreatesStores(){
        CaptureStore store=new CaptureStore(temp.resolve("captures"));
        CaptureWorkflowTest.Client client=new CaptureWorkflowTest.Client(segment());
        for(String label:Arrays.asList(""," ","x\n",String.join("",Collections.nCopies(81,"x")),"\uE000")){
            assertThrows(ToolException.class,()->Workflow.saveRequestCapture(discovery(client),store,label,null,obj("path","features")));
            assertTrue(client.calls.isEmpty());assertFalse(Files.exists(store.root));
        }
        ViewerSnapshotStore pins=new ViewerSnapshotStore(temp.resolve("pins"),namespace());
        for(String query:Arrays.asList(""," ","\n","\uE000",String.join("",Collections.nCopies(129,"x"))))
            assertThrows(ToolException.class,()->CaptureTools.searchSavedEvidence(pins,store,query,null,10));
        for(int limit:new int[]{0,-1,101})assertThrows(ToolException.class,()->CaptureTools.searchSavedEvidence(pins,store,"feature",null,limit));
        assertThrows(ToolException.class,()->CaptureTools.searchSavedEvidence(pins,store,"feature","payload",10));
        assertFalse(Files.exists(pins.root));assertFalse(Files.exists(store.root));
    }

    @Test void interruptedPublicationPairStaysReadableAndConsumesQuota()throws Exception{
        CaptureStore store=new CaptureStore(temp.resolve("captures"));ObjectNode value=capture();store.save(value);
        Path original=store.root.resolve(value.path("captureId").asText()+".json");
        Path pending=store.root.resolve(".pending-"+UUID.randomUUID());Files.createLink(pending,original);
        assertEquals(value,store.get(value.path("captureId").asText()));
        ObjectNode list=store.list();assertEquals(1,list.path("count").asInt());assertEquals(1,list.path("pendingFiles").asInt());
        assertEquals(Files.size(original)*2,list.path("totalBytes").asLong());assertTrue(Files.exists(pending));
    }

    @Test void distinctStoreInstancesSerializeQuotaWithoutEvicting()throws Exception{
        Path root=temp.resolve("quota");CaptureStore first=new CaptureStore(root);ObjectNode original=capture();first.save(original);
        ExecutorService pool=Executors.newFixedThreadPool(6);List<Future<Boolean>>pending=new ArrayList<>();
        try{
            for(int i=0;i<24;i++)pending.add(pool.submit(()->{
                try{new CaptureStore(root).save(capture());return true;}
                catch(ToolException e){assertTrue(e.getMessage().contains("limit"),e.getMessage());return false;}
            }));
            int saved=1;for(Future<Boolean> result:pending)if(result.get())saved++;
            assertEquals(20,saved);assertEquals(20,new CaptureStore(root).list().path("count").asInt());
            assertEquals(original,new CaptureStore(root).get(original.path("captureId").asText()));
        }finally{pool.shutdownNow();}
    }

    @Test void legacyPinsMissingGraphPreserveSourceAndUnavailableSiblingsAcrossRestart(){
        ViewerSnapshotStore original=new ViewerSnapshotStore(temp.resolve("pins"),namespace());
        ObjectNode input=CaptureWorkflowTest.snapshotInput();
        ((ObjectNode)input.path("journey")).remove("visualization");
        ((ArrayNode)input.path("journey").path("segments")).add(obj("service","ems","serviceInstanceId","instance-ems",
            "segmentId",CaptureWorkflowTest.OTHER,"traceId",CaptureWorkflowTest.TRACE));
        input.put("partial",true);
        ObjectNode saved=original.save(input);String id=saved.path("id").asText();
        ViewerSnapshotStore restarted=new ViewerSnapshotStore(temp.resolve("pins"),namespace());
        assertEquals(saved,restarted.get(id));assertFalse(restarted.get(id).path("journey").has("visualization"));
        assertEquals("secret-source",restarted.get(id).path("segments").get(0).path("source").asText());
        ObjectNode projected=CaptureTools.projectViewerSnapshot(restarted.get(id),null,null);
        assertTrue(projected.path("warnings").toString().contains("viewer_snapshot_missing_exports"));
        assertTrue(projected.path("warnings").toString().contains("viewer_snapshot_partial"));
        assertFalse(stringify(projected).contains("secret-source"));
    }

    @Test void oldPrettyPrintedUtf8CaptureFilesAreReadableOffline()throws Exception{
        CaptureStore store=new CaptureStore(temp.resolve("captures"));ObjectNode original=capture();original.put("label","Before – café");
        store.save(original);Path file=store.root.resolve(original.path("captureId").asText()+".json");
        // Existing files are not required to use Java's sorted ASCII serialization.
        byte[] legacy=MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(original).getBytes(StandardCharsets.UTF_8);
        Files.write(file,legacy);
        CaptureStore restarted=new CaptureStore(store.root);
        assertEquals(original,restarted.get(original.path("captureId").asText()));
        assertEquals(legacy.length,restarted.list().path("captures").get(0).path("sizeBytes").asInt());
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));
    }

    @Test void unicodeLiteralCaseFoldingMatchesPythonAndDoesNotCollapseDotlessI(){
        assertEquals("strasse",Captures.casefold("Straße"));
        assertEquals("οσ",Captures.casefold("ΟΣ"));assertEquals("οσ",Captures.casefold("ος"));
        assertEquals("ffi",Captures.casefold("ﬃ"));assertEquals("ı",Captures.casefold("ı"));
        assertEquals("i\u0307",Captures.casefold("İ"));assertEquals("i",Captures.casefold("I"));
        assertEquals("\u13A0",Captures.casefold("\uAB70"));
    }

    @Test void comparisonUsesCountsNotRuntimeIdentityAndNamesRemainUnambiguous(){
        ObjectNode before=capture(),after=before.deepCopy();
        after.put("captureId",UUID.randomUUID().toString());after.put("label","candidate");
        ObjectNode s=(ObjectNode)after.path("segments").get(0);s.put("serviceInstanceId","restarted-instance");s.put("segmentId",UUID.randomUUID().toString());
        ObjectNode compared=Captures.compareCaptures(before,after);
        for(String category:Captures.CATEGORIES)assertEquals(0,compared.path(category).path("returned").asInt(),category);
        ((ObjectNode)s.path("methods").get(0)).put("count",2);
        compared=Captures.compareCaptures(before,after);assertEquals(1,compared.path("methods").path("countChanged").size());
        CaptureStore store=new CaptureStore(temp.resolve("captures"));store.save(before);store.save(after);
        assertEquals(after,Workflow.resolveCapture(store,"candidate"));
        assertEquals(before,Workflow.resolveCapture(store,before.path("captureId").asText()));
        ObjectNode duplicate=before.deepCopy();duplicate.put("captureId",UUID.randomUUID().toString());store.save(duplicate);
        assertThrows(ToolException.class,()->Workflow.resolveCapture(store,"baseline"));
    }

    @Test void searchReportsUnavailableStoreWithoutHidingOtherSavedEvidence()throws Exception{
        CaptureStore captures=new CaptureStore(temp.resolve("captures"));captures.save(capture());
        ViewerSnapshotStore pins=new ViewerSnapshotStore(temp.resolve("pins"),namespace());
        ObjectNode saved=pins.save(CaptureWorkflowTest.snapshotInput());
        Files.write(pins.root.resolve(saved.path("id").asText()+".json"),"not-json SECRET".getBytes(StandardCharsets.UTF_8));
        ObjectNode results=CaptureTools.searchSavedEvidence(pins,captures,"lookup","method",50);
        assertEquals(1,results.path("matched").asInt());assertEquals(1,results.path("returned").asInt());
        assertEquals("capture",results.path("results").get(0).path("storage").asText());
        assertTrue(results.path("limited").asBoolean());assertEquals(arr("viewer_store_unavailable"),results.path("warnings"));
        assertFalse(results.toString().contains("SECRET"));
    }

    @Test void ambiguousOrMissingCaptureSelectionNeverWritesAnEvidenceFile(){
        ObjectNode second=segment();second.put("traceId","b"+CaptureWorkflowTest.TRACE.substring(1));second.put("segmentId",CaptureWorkflowTest.OTHER);
        CaptureWorkflowTest.Client client=new CaptureWorkflowTest.Client(segment(),second);
        CaptureStore store=new CaptureStore(temp.resolve("captures"));
        ObjectNode ambiguous=Workflow.saveRequestCapture(discovery(client),store,"candidate",null,obj("path","features"));
        assertEquals("ambiguous",ambiguous.path("status").asText());assertFalse(ambiguous.path("saved").asBoolean());
        ObjectNode missing=Workflow.saveRequestCapture(discovery(client),store,"candidate",null,obj("path","missing"));
        assertEquals("not_found",missing.path("status").asText());assertFalse(missing.path("saved").asBoolean());
        assertFalse(Files.exists(store.root));assertFalse(client.calls.stream().anyMatch(s->s.contains("/export/")));
    }
}
