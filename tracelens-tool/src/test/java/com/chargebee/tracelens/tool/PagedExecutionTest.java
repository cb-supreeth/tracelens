package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PagedExecutionTest {
    private ObjectNode event(int seq,String type,String method){
        return Json.obj("seq",seq,"eventIndex",seq-1,"type",type,"thread","worker",
                "className","example.Service","methodName",method,"line",42,"nanoTime",seq*1000000L);
    }
    private ObjectNode overview(int count){return Json.obj("eventCount",count,"status","COMPLETED","segmentId","segment");}
    private ObjectNode build(ObjectNode metadata,Iterable<JsonNode> pages){
        return Execution.buildPages(metadata,200,Execution.VIEWER_MODEL_MAX_BYTES,pages);
    }
    @Test void methodsAndLinesRemainNestedAcrossPages(){
        JsonNode a=Json.obj("events",Json.arr(event(1,"METHOD_ENTER","outer"),event(2,"METHOD_ENTER","inner")));
        JsonNode b=Json.obj("events",Json.arr(event(3,"LINE",null)));
        JsonNode c=Json.obj("events",Json.arr(event(4,"METHOD_EXIT","inner"),event(5,"METHOD_EXIT","outer")));
        ObjectNode model=build(overview(5),Arrays.asList(a,b,c));
        assertFalse(model.path("partial").asBoolean());assertTrue(model.path("paged").asBoolean());
        assertEquals("n_0",model.path("nodes").get(1).path("parentId").asText());
        assertEquals(4d,model.path("nodes").get(0).path("durationMs").asDouble());
        assertEquals(2d,model.path("nodes").get(1).path("durationMs").asDouble());
        assertEquals(2,model.path("nodes").get(1).path("observedLines").path("firstEvidence").path("index").asInt());
    }
    @Test void skippedOversizeEventBreaksNestingHonestly(){
        JsonNode a=Json.obj("events",Json.arr(event(1,"METHOD_ENTER","outer")));
        JsonNode b=Json.obj("events",Json.arr(event(3,"METHOD_ENTER","child"),event(4,"METHOD_EXIT","child")),
                "partial",true,"warnings",Json.arr("event exceeds page budget"));
        ObjectNode model=build(overview(4),Arrays.asList(a,b));
        assertTrue(model.path("partial").asBoolean());
        assertFalse(model.path("nodes").get(0).path("complete").asBoolean());
        assertTrue(model.path("nodes").get(1).path("parentId").isNull());
        assertEquals(1,model.path("counts").path("eventsOmitted").asInt());
    }
    @Test void pagedSqlKeepsPreviewAndAnchorButLoadsValuesSeparately(){
        ObjectNode sql=event(1,"EXTERNAL_CALL",null);
        sql.set("attributes",Json.obj("boundaryKind","database","system","mysql","evidenceKind","jdbc-execution","direction","outbound","status","OBSERVED",
                "operation","executeQuery","sqlSource","prepared","sql","SELECT * FROM users WHERE id = ?",
                "sqlParametersStatus","captured","sqlParameters",Json.arr(Json.obj("index",1,"type","String","status","captured","value","private-value"))));
        ObjectNode model=build(overview(1),Collections.singletonList(Json.obj("events",Json.arr(sql))));
        JsonNode boundary=model.path("nodes").get(0).path("boundary");
        assertEquals("SELECT * FROM users WHERE id = ?",boundary.path("sqlPreview").asText());
        assertEquals(1,boundary.path("eventSeq").asInt());
        assertTrue(boundary.path("sqlDetailsAvailable").asBoolean());
        assertFalse(boundary.has("sql"));assertFalse(boundary.has("sqlParameters"));
        assertFalse(model.toString().contains("private-value"));
        ObjectNode legacy=overview(1);legacy.set("events",Json.arr(sql));
        assertTrue(Execution.build(legacy,200).path("nodes").get(0).path("boundary").hasNonNull("sql"));
        assertEquals("private-value",Execution.build(legacy,200).path("nodes").get(0).path("boundary").path("sqlParameters").get(0).path("value").asText());
    }
    @Test void retainedExceptionCannotCloseAMethodAcrossUncapturedDetail(){
        ObjectNode thrown=event(2,"EXCEPTION","outer");thrown.put("detailGapBefore",true);
        ObjectNode model=build(overview(2),Collections.singletonList(Json.obj("events",Json.arr(event(1,"METHOD_ENTER","outer"),thrown))));
        assertTrue(model.path("partial").asBoolean());
        assertFalse(model.path("nodes").get(0).path("complete").asBoolean());
        assertTrue(model.path("nodes").get(0).path("durationMs").isNull());
        assertEquals("marker",model.path("nodes").get(1).path("kind").asText());
        assertTrue(model.path("nodes").get(1).path("parentId").isNull());
    }
    @Test void largeTimelineIsConsumedLazilyAndClosesFirstInvocation(){
        int count=38138;AtomicInteger loaded=new AtomicInteger();
        Iterable<JsonNode> pages=()->new Iterator<JsonNode>(){
            int seq=1;
            public boolean hasNext(){return seq<=count;}
            public JsonNode next(){
                loaded.incrementAndGet();ArrayNode events=Json.arr();
                for(int i=0;i<500&&seq<=count;i++,seq++)
                    events.add(event(seq,seq==1?"METHOD_ENTER":seq==count?"METHOD_EXIT":"LINE","run"));
                return Json.obj("events",events);
            }
        };
        ObjectNode model=build(overview(count),pages);
        assertEquals(77,loaded.get());assertEquals(count,model.path("counts").path("eventsProcessed").asInt());
        assertTrue(model.path("nodes").get(0).path("complete").asBoolean());
        assertEquals(count-2,model.path("nodes").get(0).path("observedLines").path("count").asInt());
        assertTrue(Captures.bytes(model).length<Execution.VIEWER_MODEL_MAX_BYTES);
    }
    @Test void processingCeilingDoesNotFetchAnotherPage(){
        AtomicInteger loaded=new AtomicInteger();
        Iterable<JsonNode> pages=()->new Iterator<JsonNode>(){
            int seq=1;
            public boolean hasNext(){return true;}
            public JsonNode next(){
                if(loaded.incrementAndGet()>200)fail("Fetched beyond processing budget");
                ArrayNode events=Json.arr();
                for(int i=0;i<500;i++,seq++)events.add(event(seq,seq==1?"METHOD_ENTER":"LINE","run"));
                return Json.obj("events",events);
            }
        };
        ObjectNode model=build(overview(120000),pages);
        assertEquals(200,loaded.get());assertTrue(model.path("partial").asBoolean());
        assertEquals(20000,model.path("counts").path("eventsOmitted").asInt());
    }
    @Test void pageRequestBudgetKeepsObservedNodesButDoesNotInventAnExit(){
        AtomicInteger reads=new AtomicInteger();
        BoundedEventPages pages=new BoundedEventPages(new BoundedReads.Budget(2000,1,4096),after->{
            reads.incrementAndGet();return Json.obj("events",Json.arr(event(1,"METHOD_ENTER","outer")),"nextAfter",1,"hasMore",true);
        });
        ObjectNode model=pages.describeLimit(build(overview(2),pages));
        assertEquals(1,reads.get());assertEquals(1,model.path("nodes").size());assertTrue(model.path("partial").asBoolean());
        assertFalse(model.path("nodes").get(0).path("complete").asBoolean());assertTrue(model.path("nodes").get(0).path("durationMs").isNull());
        assertTrue(model.path("warnings").toString().contains("read_budget_exhausted"));
    }
    @Test void emptyProgressingPagesCannotBypassReadBudgets(){
        AtomicInteger reads=new AtomicInteger();
        BoundedEventPages pages=new BoundedEventPages(new BoundedReads.Budget(2000,3,4096),after->{
            reads.incrementAndGet();return Json.obj("events",Json.arr(),"nextAfter",after+1,"hasMore",true);
        });
        ObjectNode model=pages.describeLimit(build(overview(100),pages));
        assertEquals(3,reads.get());assertTrue(model.path("warnings").toString().contains("read_budget_exhausted"));
        assertEquals(100,model.path("counts").path("eventsOmitted").asInt());
    }
    @Test void slowPageReturnsTheEarlierEvidenceWithinTheOperationDeadline(){
        AtomicInteger reads=new AtomicInteger();
        BoundedEventPages pages=new BoundedEventPages(new BoundedReads.Budget(150,5,4096),after->{
            if(reads.incrementAndGet()==1)return Json.obj("events",Json.arr(event(1,"METHOD_ENTER","outer")),"nextAfter",1,"hasMore",true);
            try{new java.util.concurrent.CountDownLatch(1).await();}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}
            return Json.obj("events",Json.arr(),"nextAfter",2,"hasMore",false);
        });
        long started=System.nanoTime();ObjectNode model=pages.describeLimit(build(overview(2),pages));
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<2000);
        assertEquals(1,model.path("nodes").size());assertTrue(model.path("warnings").toString().contains("read_budget_exhausted"));
    }
}
