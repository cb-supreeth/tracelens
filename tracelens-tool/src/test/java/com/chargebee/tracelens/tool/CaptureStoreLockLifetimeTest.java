package com.chargebee.tracelens.tool;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class CaptureStoreLockLifetimeTest {
    @TempDir Path temp;
    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<Path,Object> locks() throws Exception {
        Field field=CaptureStore.class.getDeclaredField("LOCKS");field.setAccessible(true);
        return (ConcurrentHashMap<Path,Object>)field.get(null);
    }
    @Test void missingPathsAndFailedOperationsDoNotRetainLockEntries() throws Exception {
        Path root=temp.toRealPath();ConcurrentHashMap<Path,Object> entries=locks();
        for(int i=0;i<100;i++) {
            CaptureStore missing=new CaptureStore(root.resolve(UUID.randomUUID().toString()));
            assertThatThrownBy(()->missing.locked(false,()->null)).isInstanceOf(CaptureStore.Missing.class);
            assertThat(entries).doesNotContainKey(missing.root);
        }
        CaptureStore existing=new CaptureStore(root.resolve("existing"));
        assertThatThrownBy(()->existing.locked(true,()->{throw new IOException("fixture");})).isInstanceOf(ToolException.class);
        assertThat(entries).doesNotContainKey(existing.root);
        assertThatThrownBy(()->existing.locked(false,()->{throw new IllegalStateException("fixture");})).isInstanceOf(IllegalStateException.class);
        assertThat(entries).doesNotContainKey(existing.root);
        existing.locked(false,()->null);assertThat(entries).doesNotContainKey(existing.root);
    }
    @Test void activeAndWaitingCallersKeepTheSameEntryAndRemainSerialized() throws Exception {
        CaptureStore store=new CaptureStore(temp.toRealPath().resolve("shared"));
        ConcurrentHashMap<Path,Object> entries=locks();ExecutorService pool=Executors.newFixedThreadPool(3);
        CountDownLatch firstEntered=new CountDownLatch(1),releaseFirst=new CountDownLatch(1),secondEntered=new CountDownLatch(1),releaseSecond=new CountDownLatch(1);
        AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger();
        try {
            Future<?> first=pool.submit(()->store.locked(true,()->{
                maximum.accumulateAndGet(active.incrementAndGet(),Math::max);firstEntered.countDown();
                try{await(releaseFirst);}finally{active.decrementAndGet();}return null;
            }));
            await(firstEntered);Object shared=entries.get(store.root);assertThat(shared).isNotNull();
            Future<?> second=pool.submit(()->new CaptureStore(store.root).locked(false,()->{
                maximum.accumulateAndGet(active.incrementAndGet(),Math::max);secondEntered.countDown();
                try{await(releaseSecond);}finally{active.decrementAndGet();}return null;
            }));
            awaitUsers(entries,store.root,2);assertThat(entries.get(store.root)).isSameAs(shared);
            assertThatThrownBy(()->second.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseFirst.countDown();first.get(3,TimeUnit.SECONDS);await(secondEntered);
            assertThat(entries.get(store.root)).isSameAs(shared);
            Future<?> third=pool.submit(()->new CaptureStore(store.root).locked(false,()->{
                maximum.accumulateAndGet(active.incrementAndGet(),Math::max);active.decrementAndGet();return null;
            }));
            awaitUsers(entries,store.root,2);assertThat(entries.get(store.root)).isSameAs(shared);
            assertThatThrownBy(()->third.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseSecond.countDown();second.get(3,TimeUnit.SECONDS);third.get(3,TimeUnit.SECONDS);
            assertThat(maximum.get()).isEqualTo(1);assertThat(entries).doesNotContainKey(store.root);
        }finally{releaseFirst.countDown();releaseSecond.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    private static void await(CountDownLatch latch) {
        try{if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("Fixture did not release");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }
    private static void awaitUsers(ConcurrentHashMap<Path,Object> entries,Path path,int expected) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);AtomicInteger count=new AtomicInteger();
        while(System.nanoTime()<deadline) {
            count.set(0);entries.computeIfPresent(path,(key,entry)->{
                try{Field users=entry.getClass().getDeclaredField("users");users.setAccessible(true);count.set(users.getInt(entry));}
                catch(ReflectiveOperationException failure){throw new AssertionError(failure);}return entry;
            });
            if(count.get()==expected)return;Thread.sleep(5);
        }
        assertThat(count.get()).isEqualTo(expected);
    }
}
