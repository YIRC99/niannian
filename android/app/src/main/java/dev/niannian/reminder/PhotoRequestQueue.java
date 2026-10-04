package dev.niannian.reminder;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Photo IO never occupies the task/authentication executor. Each page owns a generation. */
final class PhotoRequestQueue implements AutoCloseable {
    private final AtomicInteger generation=new AtomicInteger();
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,0L,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16));

    int generation(){return generation.get();}
    boolean isCurrent(int request){return request==generation.get()&&!executor.isShutdown();}

    boolean execute(int request,Runnable download){
        if(!isCurrent(request))return false;
        try{
            executor.execute(()->{if(isCurrent(request))download.run();});
            return true;
        }catch(RejectedExecutionException fullOrClosed){return false;}
    }

    void invalidate(){
        generation.incrementAndGet();
        executor.getQueue().clear();
    }

    @Override public void close(){invalidate();executor.shutdownNow();}
}
