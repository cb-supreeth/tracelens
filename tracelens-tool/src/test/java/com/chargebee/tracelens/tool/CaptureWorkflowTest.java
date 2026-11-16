package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import static com.chargebee.tracelens.tool.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class CaptureWorkflowTest {
    static final String TRACE="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", SEGMENT="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",OTHER="bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",NAMESPACE=String.join("",Collections.nCopies(64,"a"));
    @TempDir Path temp;
    @BeforeEach void canonicalTemporaryDirectory() throws IOException {temp=temp.toRealPath();}
    static ObjectNode event(int seq,String type,String method){return obj("seq",seq,"type",type,"className","com.example.FeatureService","methodName",method,"thread","private-user-thread","nanoTime",seq*1000000,"attributes",obj());}
    static ObjectNode segment(){
        ArrayNode events=arr(event(1,"METHOD_ENTER","retrieve"),event(2,"LINE",null).put("line",42),event(3,"METHOD_ENTER","lookup"),event(4,"METHOD_EXIT","lookup"),event(5,"METHOD_EXIT","retrieve"));
        return obj("traceId",TRACE,"segmentId",SEGMENT,"serviceInstanceId","instance-1","spanId","bbbbbbbbbbbbbbbb","parentSpanId",null,"status","COMPLETED","entryChannel","HTTP","httpMethod","GET","path","/features?token=secret-query","httpStatus",200,"startedAtMillis",System.currentTimeMillis(),"durationMs",12,
            "user","secret-user","contextParams",obj("auth","secret-context"),"source","secret-source","events",events,"eventCount",events.size(),"httpCalls",arr());
    }
    static class Client extends DebugClient {
        final List<ObjectNode>segments=new ArrayList<>();final List<String>calls=Collections.synchronizedList(new ArrayList<>());
        ObjectNode metadata=obj("agentActive",true,"packages",arr("com.example"),"httpIncludePaths",arr("/"),"requireLabels",false,"idleTtlMinutes",10);
        boolean partial;
        Client(ObjectNode...rows){super("http://127.0.0.1:9998");segments.addAll(Arrays.asList(rows));}
        @Override public ObjectNode request(String method,String path,int maxBytes,int timeout){
            calls.add(method+" "+path);assertEquals("GET",method);assertTrue(timeout<=5000);
            if(path.startsWith("/overview/"))throw new ToolException(404,"Legacy agent");
            if(path.equals("/metadata"))return metadata.deepCopy();
            if(path.equals("/summary"))return obj("agentActive",true,"recordingEnabled",true,"traceCount",segments.size(),"totalEvents",10,"bridge",obj("sinkRegistered",true),"httpIncludePaths",arr("/"));
            if(path.startsWith("/export/")){assertTrue(path.endsWith("?evict=false"));String id=path.substring(8,path.indexOf('?'));for(ObjectNode s:segments)if(id.equals(text(s,"segmentId")))return s.deepCopy();throw new ToolException(404,"secret-upstream-error");}
            if(path.equals("/traces")||path.startsWith("/find-by-trace-id/")){ArrayNode summaries=arr();for(ObjectNode s:segments){ObjectNode row=s.deepCopy();row.remove("events");summaries.add(row);}return obj("traces",summaries,"partial",partial);}
            throw new AssertionError(path);
        }
    }
    static Federation discovery(Client client){Federation f=new Federation(Collections.singletonList(new Service("app","http://127.0.0.1:9998",null)));f.clients.put("app",client);return f;}
    static ObjectNode capture(){return Captures.buildCapture(discovery(new Client(segment())),TRACE,"baseline","build-1");}
    static ObjectNode snapshotInput(){
        ObjectNode row=segment();row.put("service","app");row.put("segmentKey",stringify(arr("app","instance-1",SEGMENT)));ObjectNode summary=row.deepCopy();summary.remove("events");
        ObjectNode graph=obj("schemaVersion",1,"kind","request-flow","traceId",TRACE,"nodes",arr(obj("id","segment-node","kind","segment","service","app","serviceInstanceId","instance-1","segmentId",SEGMENT)),"edges",arr(),"roots",arr("segment-node"));
        return obj("name","Demo","traceId",TRACE,"capturedAt",1234567,"partial",false,"warnings",arr(),"request",obj("traceId",TRACE,"services",arr("app"),"segments",arr(summary),"attributes",obj("secret","runtime")),"journey",obj("traceId",TRACE,"segments",arr(summary),"links",arr(),"httpCalls",arr(),"visualization",graph),"segments",arr(row));
    }
    @Test void projectionIsPayloadFreeAndRoundTripsPythonCompatibleJson(){
        ObjectNode c=capture();Captures.validateCapture(c);Captures.validateCapture(parse(Captures.bytes(c)));String json=stringify(c);
        for(String secret:Arrays.asList("secret-user","secret-context","secret-query","secret-source","private-user-thread"))assertFalse(json.contains(secret),secret);
        JsonNode s=c.path("segments").get(0);assertEquals("/features",s.path("request").path("path").asText());assertEquals(2,s.path("methods").size());assertEquals(1,s.path("nesting").size());assertEquals(42,s.path("lines").get(0).path("line").asInt());
        assertFalse(s.path("partial").asBoolean(),stringify(s.path("warnings")));
    }
    @Test void projectionOmitsSecretsInDependenciesAndMarksIncompleteEvidence(){
        ObjectNode row=segment();((ArrayNode)row.get("events")).add(obj("seq",6,"type","EXTERNAL_CALL","attributes",obj("boundaryKind","messaging","system","kafka","direction","publish","destination","topic.features","operation","send","status","SUBMITTED","evidenceKind","client-boundary","payload","secret-payload")));
        row.put("eventCount",6);row.put("truncated",true);row.put("parentSegmentId",OTHER);
        row.set("httpCalls",arr(obj("peer","https://name:password@db.test/route?secret=query","httpMethod","GET","path","/features?secret=query","httpStatus",500,"status","ERROR","message","secret-message")));
        ObjectNode c=Captures.buildCapture(discovery(new Client(row)),TRACE,"before",null);Captures.validateCapture(parse(Captures.bytes(c)));String json=stringify(c);
        for(String secret:Arrays.asList("password","secret=query","secret-payload","secret-message"))assertFalse(json.contains(secret),secret);
        assertTrue(c.path("partial").asBoolean());assertTrue(json.contains("root_missing_or_ambiguous"));assertEquals(2,c.path("segments").get(0).path("dependencies").size());
    }
    @Test void validatesBeforeNetworkAndRejectsRawFields(){
        Client client=new Client(segment());assertThrows(ToolException.class,()->Captures.buildCapture(discovery(client),"../bad","okay",null));assertTrue(client.calls.isEmpty());
        ObjectNode c=capture();c.put("source","SECRET");assertThrows(ToolException.class,()->Captures.validateCapture(c));
        ObjectNode bad=capture();((ObjectNode)bad.path("segments").get(0).path("request")).put("extra","SECRET");assertThrows(ToolException.class,()->Captures.validateCapture(bad));
    }
    @Test void captureStorePrivateImmutableAndConcurrent()throws Exception{
        CaptureStore store=new CaptureStore(temp.resolve("captures"));assertEquals(0,store.list().path("count").asInt());assertFalse(Files.exists(store.root));
        ObjectNode original=capture();ObjectNode metadata=store.save(original);String id=metadata.path("captureId").asText();assertEquals(parse(Captures.bytes(original)),store.get(id));
        assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(store.root));assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(store.root.resolve(id+".json")));
        assertThrows(ToolException.class,()->store.save(original));assertThrows(ToolException.class,()->store.get("../escape"));
        ExecutorService pool=Executors.newFixedThreadPool(4);try{List<Future<?>>pending=new ArrayList<>();for(int i=0;i<7;i++)pending.add(pool.submit(()->store.save(capture())));for(Future<?>f:pending)f.get();}finally{pool.shutdownNow();}
        assertEquals(8,store.list().path("count").asInt());assertEquals(Files.size(store.root.resolve(id+".json")),metadata.path("sizeBytes").asLong());
    }
    @Test void storeRejectsLinksPublicFilesAndCorruption()throws Exception{
        CaptureStore store=new CaptureStore(temp.resolve("captures"));ObjectNode c=capture();store.save(c);Path file=store.root.resolve(text(c,"captureId")+".json"),hardlink=temp.resolve("hardlink");Files.createLink(hardlink,file);
        assertThrows(ToolException.class,store::list);Files.delete(hardlink);Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-r--r--"));assertThrows(ToolException.class,store::list);
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));Files.write(file,"{\"captureId\":\"one\",\"captureId\":\"two\"}".getBytes(StandardCharsets.UTF_8));assertThrows(ToolException.class,store::list);
        Path link=temp.resolve("link");Files.createSymbolicLink(link,store.root);assertThrows(ToolException.class,()->new CaptureStore(link).list());
    }
    @Test void sharedWritableAncestorIsRejectedBeforeStoreCreation()throws Exception{
        Path shared=temp.resolve("shared");Files.createDirectory(shared,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx")));
        Files.setPosixFilePermissions(shared,PosixFilePermissions.fromString("rwxrwxrwx"));
        CaptureStore store=new CaptureStore(shared.resolve("captures"));assertThrows(ToolException.class,()->store.save(capture()));assertFalse(Files.exists(store.root));
        Files.setPosixFilePermissions(shared,PosixFilePermissions.fromString("rwx------"));assertEquals(0,store.list().path("count").asInt());store.save(capture());assertEquals(1,store.list().path("count").asInt());
    }
    @Test void schemaVersionAndProjectionTimestampAreStrict(){
        ObjectNode bad=capture();bad.put("schemaVersion",4294967297L);assertThrows(ToolException.class,()->Captures.validateCapture(bad));
        ViewerSnapshotStore pins=new ViewerSnapshotStore(temp.resolve("pins"),NAMESPACE);ObjectNode snapshot=pins.save(snapshotInput());
        assertEquals("1970-01-01T00:20:34.567000+00:00",CaptureTools.projectViewerSnapshot(snapshot,null,null).path("createdAt").asText());
        ((ObjectNode)snapshot.path("journey").path("visualization")).put("schemaVersion","1");assertThrows(ToolException.class,()->ViewerSnapshotStore.validateSnapshot(snapshot));
    }
    @Test void pendingFilesConsumeQuotaAndNeverDisappear()throws Exception{
        CaptureStore store=new CaptureStore(temp.resolve("captures"));store.save(capture());Path pending=store.root.resolve(".pending-"+UUID.randomUUID());Files.createFile(pending,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));Files.write(pending,new byte[]{1,2,3});
        ObjectNode list=store.list();assertEquals(1,list.path("pendingFiles").asInt());assertEquals(3,list.path("pendingBytes").asInt());assertTrue(Files.exists(pending));assertEquals(1,list.path("warnings").size());
        for(int i=0;i<18;i++)store.save(capture());assertThrows(ToolException.class,()->store.save(capture()));assertEquals(19,store.list().path("count").asInt());
    }
    @Test void viewerPinsPreserveEvidenceRenameOnlyAndStayNamespaceLocal(){
        ViewerSnapshotStore store=new ViewerSnapshotStore(temp.resolve("pins"),NAMESPACE);ObjectNode input=snapshotInput(),saved=store.save(input);String id=text(saved,"id");
        ((ObjectNode)input.path("segments").get(0)).put("source","changed source");assertEquals("secret-source",store.get(id).path("segments").get(0).path("source").asText());
        ObjectNode metadata=(ObjectNode)store.list().path("snapshots").get(0);assertFalse(metadata.path("request").has("segments"));assertFalse(metadata.path("request").has("attributes"));assertFalse(stringify(metadata).contains("secret-source"));
        store.rename(id,"\u2003 renamed \u00a0");ObjectNode expected=saved.deepCopy();expected.put("name","renamed");assertEquals(expected,store.get(id));assertEquals(1234567,store.get(id).path("capturedAt").asLong());
        ViewerSnapshotStore other=new ViewerSnapshotStore(temp.resolve("pins"),String.join("",Collections.nCopies(64,"b")));assertEquals(0,other.list().path("count").asInt());assertThrows(ToolException.class,()->other.get(id));assertThrows(ToolException.class,()->other.rename(id,"missing"));assertFalse(Files.exists(other.root));
        assertTrue(store.delete(id).path("deleted").asBoolean());assertThrows(ToolException.class,()->store.get(id));
    }
    @Test void snapshotRejectsIdentityMismatchAndInvalidGraph(){
        ViewerSnapshotStore store=new ViewerSnapshotStore(temp.resolve("pins"),NAMESPACE);ObjectNode doc=snapshotInput();((ObjectNode)doc.path("segments").get(0)).put("segmentId",OTHER);assertThrows(ToolException.class,()->store.save(doc));assertFalse(Files.exists(store.root));
        ObjectNode graph=snapshotInput();((ObjectNode)graph.path("journey").path("visualization")).set("edges",arr(obj("from","segment-node","to","missing")));assertThrows(ToolException.class,()->store.save(graph));
    }
    @Test void offlineProjectionComparisonAndSearchRemainMinimized(){
        ViewerSnapshotStore pins=new ViewerSnapshotStore(temp.resolve("pins"),NAMESPACE);CaptureStore store=new CaptureStore(temp.resolve("captures"));ObjectNode saved=pins.save(snapshotInput());String id=text(saved,"id");
        ObjectNode exported=CaptureTools.exportToCaptureStore(pins,store,id,"baseline","v1");assertTrue(exported.path("saved").asBoolean());assertNotEquals(id,exported.path("capture").path("captureId").asText());
        ObjectNode search=CaptureTools.searchSavedEvidence(pins,store,"lookup","method",100);assertEquals(2,search.path("returned").asInt());assertFalse(stringify(search).contains("secret-source"));assertFalse(stringify(search).contains("private-user-thread"));
        ObjectNode comparison=CaptureTools.compareSavedSnapshots(pins,id,id);assertEquals(0,comparison.path("methods").path("returned").asInt());assertTrue(Captures.bytes(comparison).length<=Captures.MAX_COMPARISON_BYTES);
        assertEquals(text(exported.path("capture"),"captureId"),text(Workflow.resolveCapture(store,"baseline"),"captureId"));ObjectNode duplicate=capture();store.save(duplicate);assertThrows(ToolException.class,()->Workflow.resolveCapture(store,"baseline"));
    }
    @Test void investigationNeverChoosesAmbiguousOrPartialDiscovery(){
        ObjectNode first=segment();first.put("path","/features");ObjectNode second=first.deepCopy();second.put("traceId","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");second.put("segmentId",OTHER);
        Client client=new Client(first,second);ObjectNode result=Investigation.investigateRequest(discovery(client),obj("path","/features"));assertEquals("ambiguous",result.path("status").asText());assertFalse(client.calls.stream().anyMatch(s->s.contains("/export/")));
        Client one=new Client(first);one.partial=true;result=Investigation.investigateRequest(discovery(one),obj("path","/features"));assertEquals("ambiguous",result.path("status").asText());assertTrue(result.path("partial").asBoolean());
        Client unique=new Client(first);result=Investigation.investigateRequest(discovery(unique),obj("path","/features"));assertEquals("selected",result.path("status").asText());assertEquals(TRACE,result.path("selectedTraceId").asText());
    }
    @Test void diagnosisExactPathOnlyUsesBoundedSummaries(){
        ObjectNode first=segment();first.put("path","/features");Client client=new Client(first);ObjectNode result=GuidedInvestigation.diagnoseRequest(discovery(client),obj("path","/features","method","get"));
        assertEquals("retained_matches",result.path("status").asText());assertEquals(1,result.path("counts").path("matchingRequests").asInt());assertFalse(client.calls.stream().anyMatch(s->s.contains("/export/")));
        result=GuidedInvestigation.diagnoseRequest(discovery(client),obj("path","/feature"));assertEquals("no_retained_match",result.path("status").asText());
        int calls=client.calls.size();assertThrows(ToolException.class,()->GuidedInvestigation.diagnoseRequest(discovery(client),obj("path","/features?secret=true")));assertEquals(calls,client.calls.size());
    }
}
