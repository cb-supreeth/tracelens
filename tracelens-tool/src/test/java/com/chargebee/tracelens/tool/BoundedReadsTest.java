package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedReadsTest {
    @Test void timeoutCancelsWorkAndAnExpiredOperationDoesNotReadAgain() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),interrupted=new CountDownLatch(1);
        BoundedReads.Budget budget=new BoundedReads.Budget(150,3,4096);
        long started=System.nanoTime();
        assertThrows(BoundedReads.Limit.class,()->budget.read(()->{
            entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException stopped){interrupted.countDown();throw stopped;}
            return Json.obj();
        },4096));
        assertTrue(entered.await(1,TimeUnit.SECONDS));assertTrue(interrupted.await(1,TimeUnit.SECONDS));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<2000);
        AtomicInteger calls=new AtomicInteger();
        assertThrows(BoundedReads.Limit.class,()->budget.read(()->{calls.incrementAndGet();return Json.obj();},4096));
        assertEquals(0,calls.get());
    }
    @Test void interruptedCallerCancelsItsReadAndRetainsTheInterruptFlag() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),cancelled=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean interrupted=new AtomicBoolean();
        Thread caller=new Thread(()->{
            try { new BoundedReads.Budget(5000,1,4096).read(()->{
                entered.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException stopped){cancelled.countDown();throw stopped;}
                return Json.obj();
            },4096); }
            catch(Throwable stopped){failure.set(stopped);interrupted.set(Thread.currentThread().isInterrupted());}
        });
        caller.setDaemon(true);caller.start();
        try {
            assertTrue(entered.await(1,TimeUnit.SECONDS));caller.interrupt();caller.join(2000);
            assertFalse(caller.isAlive());assertTrue(failure.get() instanceof BoundedReads.Limit);assertTrue(interrupted.get());
            assertTrue(cancelled.await(1,TimeUnit.SECONDS));
        }finally{caller.interrupt();}
    }
    @Test void requestAndCumulativeByteLimitsApplyToInjectableReaders() {
        BoundedReads.Budget count=new BoundedReads.Budget(2000,1,4096);
        assertEquals(Json.obj("ok",true),count.read(()->Json.obj("ok",true),4096));
        assertThrows(BoundedReads.Limit.class,()->count.read(()->Json.obj(),4096));
        ObjectNode value=Json.obj("value","a finite payload");int size=Captures.bytes(value).length;
        BoundedReads.Budget bytes=new BoundedReads.Budget(2000,10,size*2);
        bytes.read(()->value,4096);bytes.read(()->value,4096);
        assertThrows(BoundedReads.Limit.class,()->bytes.read(()->Json.obj(),4096));
    }
    @Test void scopedWireLimitDoesNotChangeNormalDebugReads() throws Exception {
        HttpServer upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] body=Json.stringify(Json.obj("padding",String.join("",java.util.Collections.nCopies(256,"x")))).getBytes(StandardCharsets.UTF_8);
        upstream.createContext("/tracelens/summary",exchange->{exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        upstream.start();
        try {
            DebugClient client=new DebugClient("http://127.0.0.1:"+upstream.getAddress().getPort());
            assertEquals(256,client.summary().path("padding").asText().length());
            assertThrows(BoundedReads.Limit.class,()->new BoundedReads.Budget(2000,1,64).read(client::summary,65536));
            BoundedReads.Budget cumulative=new BoundedReads.Budget(2000,3,body.length*2-1);
            cumulative.read(client::summary,65536);
            assertThrows(BoundedReads.Limit.class,()->cumulative.read(client::summary,65536));
            assertEquals(256,client.summary().path("padding").asText().length());
        }finally{upstream.stop(0);}
    }
}
