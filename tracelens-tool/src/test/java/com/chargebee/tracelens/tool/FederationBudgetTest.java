package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FederationBudgetTest {
    private static final String TRACE="trace";
    private Federation federation(int millis,int requests,int bytes) {
        return new Federation(Arrays.asList(new Service("busy","http://127.0.0.1:1",null),new Service("healthy","http://127.0.0.1:2",null)),millis,requests,bytes);
    }
    private static ObjectNode segment(String id) {
        return Json.obj("traceId",TRACE,"segmentId",id,"serviceInstanceId","instance","status","COMPLETED","events",Json.arr(),"httpCalls",Json.arr());
    }
    private static class Upstream extends DebugClient {
        final ArrayNode rows=Json.arr();final AtomicInteger reads=new AtomicInteger();
        Upstream(int count){super("http://127.0.0.1:1");for(int i=0;i<count;i++)rows.add(segment("segment"+i));}
        @Override public ObjectNode findByTraceId(String id){return Json.obj("traces",rows);}
        @Override public ObjectNode overview(String id){reads.incrementAndGet();return segment(id);}
    }
    @Test void busyServiceCannotConsumeAnotherServicesDetailBudget() {
        Federation federation=federation(3000,4,1024*1024);Upstream busy=new Upstream(20),healthy=new Upstream(1);
        federation.clients.put("busy",busy);federation.clients.put("healthy",healthy);
        ObjectNode result=federation.getDistributedTrace(TRACE);
        assertEquals(1,busy.reads.get());assertEquals(1,healthy.reads.get());
        assertTrue(result.path("partial").asBoolean());assertEquals(21,result.path("segments").size());
        assertTrue(result.path("errors").toString().contains("request budget"));
        assertEquals("healthy",result.path("segments").get(20).path("service").asText());
        assertFalse(result.path("segments").get(20).path("detailsAvailable").isBoolean());
    }
    @Test void slowServiceDoesNotDiscardFinishedHealthyEvidence() throws Exception {
        Federation federation=federation(200,16,1024*1024);CountDownLatch interrupted=new CountDownLatch(1);
        federation.clients.put("busy",new Upstream(1){@Override public ObjectNode findByTraceId(String id){
            try{new CountDownLatch(1).await();}catch(InterruptedException stopped){interrupted.countDown();Thread.currentThread().interrupt();}
            return Json.obj("traces",Json.arr());
        }});
        federation.clients.put("healthy",new Upstream(1));
        long started=System.nanoTime();ObjectNode result=federation.getDistributedTrace(TRACE);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<2000);
        assertTrue(interrupted.await(1,TimeUnit.SECONDS));assertEquals(1,result.path("segments").size());
        assertEquals("healthy",result.path("segments").get(0).path("service").asText());assertTrue(result.path("partial").asBoolean());
    }
    @Test void segmentBudgetIsSharedBeforeDetailsAreFetched() {
        Federation federation=federation(5000,1200,8*1024*1024);Upstream busy=new Upstream(600),healthy=new Upstream(1);
        federation.clients.put("busy",busy);federation.clients.put("healthy",healthy);
        ObjectNode result=federation.getDistributedTrace(TRACE);
        assertEquals(256,busy.reads.get());assertEquals(1,healthy.reads.get());assertEquals(257,result.path("segments").size());
        assertTrue(result.path("errors").toString().contains("fair service segment limit"));
    }
}
