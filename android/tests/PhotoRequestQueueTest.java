package dev.niannian.reminder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Slow downloads must stay bounded, and leaving the page must drop queued work. */
public final class PhotoRequestQueueTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        PhotoRequestQueue queue=new PhotoRequestQueue();
        CountDownLatch started=new CountDownLatch(2),release=new CountDownLatch(1),fresh=new CountDownLatch(1);
        AtomicInteger staleDownloads=new AtomicInteger();
        int old=queue.generation();
        try {
            for(int i=0;i<2;i++)check(queue.execute(old,()->{
                started.countDown();
                try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            }),"slow download accepted");
            check(started.await(2,TimeUnit.SECONDS),"two downloads can run together");
            for(int i=0;i<16;i++)check(queue.execute(old,staleDownloads::incrementAndGet),"waiting download accepted");
            check(!queue.execute(old,staleDownloads::incrementAndGet),"full queue rejects without running on caller");
            queue.invalidate();
            check(!queue.isCurrent(old),"old account or page becomes invalid");
            check(!queue.execute(old,staleDownloads::incrementAndGet),"stale submission never downloads");
            check(queue.execute(queue.generation(),fresh::countDown),"new page has room after invalidation");
            release.countDown();
            check(fresh.await(2,TimeUnit.SECONDS),"new page runs after in-flight requests finish");
            check(staleDownloads.get()==0,"queued photos from the old page were never downloaded");
        } finally {release.countDown();queue.close();}
        check(!queue.execute(queue.generation(),()->{}),"destroyed queue accepts no more work");
        System.out.println("PhotoRequestQueueTest: "+checks+" checks passed");
    }
    private static void check(boolean result,String message){checks++;if(!result)throw new AssertionError(message);}
}
