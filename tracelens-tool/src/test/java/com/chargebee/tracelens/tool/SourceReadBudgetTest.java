package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SourceReadBudgetTest {
    @TempDir Path root;
    private void source(String name,int bytes)throws Exception{Files.write(root.resolve(name+".java"),new byte[bytes]);}
    private ObjectNode line(String name){return Json.obj("type","LINE","className",name,"line",1);}
    @Test void repeatedAndAlternatingClassesReadEachFileOncePerOperation() throws Exception {
        source("First",20);source("Second",20);AtomicInteger reads=new AtomicInteger();
        Source source=new Source(root,(path,max,deadline)->{reads.incrementAndGet();return Collections.singletonList(path.getFileName().toString());});
        ArrayNode events=Json.arr();for(int i=0;i<500;i++)events.add(line(i%2==0?"First":"Second"));
        events.add(line("First$Inner"));ObjectNode input=Json.obj("events",events);
        ObjectNode result=source.enrich(input);
        assertEquals(2,reads.get());assertEquals("First.java",result.path("events").get(500).path("sourceLine").asText());
        assertFalse(input.path("events").get(0).has("sourceLine"));
        source.enrichBounded(input,256*1024);assertEquals(4,reads.get(),"Caches must not survive an operation and serve stale checkout text");
    }
    @Test void operationFileAndByteLimitsOmitSourceNotEvents() throws Exception {
        ArrayNode events=Json.arr();for(int i=0;i<Source.MAX_OPERATION_FILES+1;i++){source("Class"+i,20);events.add(line("Class"+i));}
        AtomicInteger reads=new AtomicInteger();Source source=new Source(root,(path,max,deadline)->{reads.incrementAndGet();return Collections.singletonList("observed checkout");});
        ObjectNode result=source.enrichBounded(Json.obj("events",events),256*1024);
        assertEquals(Source.MAX_OPERATION_FILES,reads.get());assertTrue(result.path("sourcePartial").asBoolean());
        assertEquals(events.size(),result.path("events").size());assertFalse(result.path("events").get(events.size()-1).has("sourceLine"));
        source("Large1",3*1024*1024);source("Large2",3*1024*1024);source("Large3",3*1024*1024);reads.set(0);
        result=source.enrich(Json.obj("events",Json.arr(line("Large1"),line("Large2"),line("Large3"))));
        assertEquals(2,reads.get());assertTrue(result.path("sourcePartial").asBoolean());assertEquals(3,result.path("events").size());
    }
    @Test void aLaterOperationReadsTheUpdatedCheckout() throws Exception {
        Path file=root.resolve("Handler.java");Files.write(file,"before".getBytes(StandardCharsets.UTF_8));Source source=new Source(root);
        ObjectNode input=Json.obj("events",Json.arr(line("Handler"),line("Handler")));
        assertEquals("before",source.enrich(input).path("events").get(0).path("sourceLine").asText());
        Files.write(file,"after".getBytes(StandardCharsets.UTF_8));
        assertEquals("after",source.enrich(input).path("events").get(1).path("sourceLine").asText());
    }
}
