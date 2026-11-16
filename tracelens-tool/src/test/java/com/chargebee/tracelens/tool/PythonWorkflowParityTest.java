package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import static com.chargebee.tracelens.tool.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/** Recorded outputs from the Python implementation; Java test/runtime needs no Python. */
class PythonWorkflowParityTest {
    @TempDir Path temp;
    private int directory;
    private static final String NAMESPACE=String.join("",Collections.nCopies(64,"c"));
    private Federation registry(Collection<String> names) {
        List<Service> services=new ArrayList<>();int port=20000;
        for(String name:names)services.add(new Service(name,"http://127.0.0.1:"+(port++),null));
        return new Federation(services);
    }
    @TestFactory Collection<DynamicTest> pythonReferenceWorkflows() throws Exception {
        JsonNode root;
        try(InputStream stream=getClass().getResourceAsStream("/workflows-python-reference.json")){
            // Trusted reference includes a deliberately huge Python integer.
            // Production wire parsing remains stricter than this fixture loader.
            assertNotNull(stream);root=new com.fasterxml.jackson.databind.ObjectMapper().readTree(stream);
        }
        List<DynamicTest> tests=new ArrayList<>();
        for(JsonNode fixture:root.path("cases"))tests.add(DynamicTest.dynamicTest(
            fixture.path("kind").asText()+": "+fixture.path("name").asText(),()->{
                if(fixture.has("error")){
                    RuntimeException error=assertThrows(RuntimeException.class,()->run(fixture));
                    assertEquals(fixture.path("error").asText(),error.getMessage());
                }else sameJson(fixture.get("expected"),run(fixture),"$");
            }));
        return tests;
    }
    private ObjectNode run(JsonNode fixture)throws Exception {
        JsonNode input=fixture.path("inputs");
        switch(fixture.path("kind").asText()){
            case "projection":return Captures.projectSegment("app",input.get("row"),input.path("budget").asInt());
            case "snapshot":return CaptureTools.projectViewerSnapshot(input.get("snapshot"),null,null,input.path("budget").asInt());
            case "comparison":return Captures.compareCaptures(input.get("before"),input.get("after"));
            case "investigation":{
                List<String> reads=new ArrayList<>();
                ObjectNode document=copy(input.path("flags"));document.set("traces",input.path("rows"));
                try{return Investigation.investigateRequest(registry(Arrays.asList("app","ems")),
                    copy(input.get("filters")),input.path("now").asDouble(),()->{reads.add("traces");return document.deepCopy();},
                    id->{reads.add("journey:"+id);return input.path("journeys").path(id).deepCopy();});}
                finally {sameJson(input.path("reads"),node(reads),"$.reads");}
            }
            case "diagnosis":{
                List<String>names=new ArrayList<>();input.path("docs").fieldNames().forEachRemaining(names::add);
                Federation discovery=registry(names);
                for(String name:names){
                    JsonNode docs=input.path("docs").path(name);
                    discovery.clients.put(name,new DebugClient(discovery.services.get(name).url){
                        @Override public ObjectNode request(String method,String path,int maxBytes,int timeout){
                            assertEquals("GET",method);assertTrue(timeout<=5000);
                            assertTrue(Arrays.asList("/summary","/metadata","/traces").contains(path),path);
                            return copy(docs.path(path.substring(1)));
                        }
                    });
                }
                return GuidedInvestigation.diagnoseRequest(discovery,copy(input.get("filters")));
            }
            case "search":{
                Path root=temp.toRealPath().resolve("search-"+directory++);
                ViewerSnapshotStore snapshots=new ViewerSnapshotStore(root.resolve("pins"),NAMESPACE);
                CaptureStore captures=new CaptureStore(root.resolve("captures"));
                Files.createDirectories(snapshots.root,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                for(JsonNode snapshot:input.path("snapshots")){
                    Path file=snapshots.root.resolve(snapshot.path("id").asText()+".json");
                    Files.createFile(file,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    Files.write(file,Captures.bytes(snapshot));
                }
                for(JsonNode capture:input.path("captures"))captures.save(capture);
                return CaptureTools.searchSavedEvidence(snapshots,captures,text(input,"query"),text(input,"category"),input.path("limit").asInt());
            }
            default:throw new AssertionError("unknown workflow fixture");
        }
    }
    private static void sameJson(JsonNode expected,JsonNode actual,String path){
        assertNotNull(actual,path);
        if(expected.isNumber()&&actual.isNumber()){
            assertEquals(0,expected.decimalValue().compareTo(actual.decimalValue()),path+" number");return;
        }
        if(expected.isObject()){
            assertTrue(actual.isObject(),path);Set<String>a=new TreeSet<>(),b=new TreeSet<>();
            expected.fieldNames().forEachRemaining(a::add);actual.fieldNames().forEachRemaining(b::add);
            assertEquals(a,b,path+" keys");for(String key:a)sameJson(expected.get(key),actual.get(key),path+"."+key);
        }else if(expected.isArray()){
            assertTrue(actual.isArray(),path);assertEquals(expected.size(),actual.size(),path+" length");
            for(int i=0;i<expected.size();i++)sameJson(expected.get(i),actual.get(i),path+"["+i+"]");
        }else assertEquals(expected,actual,path);
    }
}
