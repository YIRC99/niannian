package dev.niannian.reminder;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Pure decisions for finite, server-authorized device execution windows. */
final class LocalReminderFlow {
    record Step(String id, int index, String channel, long scheduled, long expires) { }
    static long nextAttempt(long now, long scheduled, long expires, long validUntil, long lastActual, int intervalMinutes, boolean predecessorsProcessed) {
        return nextAttempt(now,scheduled,expires,validUntil,lastActual,intervalMinutes,predecessorsProcessed,0L,0);
    }
    static long nextAttempt(long now,long scheduled,long expires,long validUntil,long lastActual,int intervalMinutes,boolean predecessorsProcessed,long previousFinished,int waitMinutes){
        long end = Math.min(expires, validUntil);
        if (scheduled <= 0L || end <= scheduled || now >= end) return -1L;
        long earliest = Math.max(now, scheduled);
        if (lastActual > 0L) earliest = Math.max(earliest, lastActual + Math.max(1, intervalMinutes) * 60_000L);
        if(previousFinished>0L)earliest=Math.max(earliest,previousFinished+Math.max(Math.max(1,intervalMinutes),waitMinutes)*60_000L);
        if (!predecessorsProcessed) earliest = Math.max(earliest, now + 1000L);
        return earliest < end ? earliest : -1L;
    }
    static boolean terminal(String status) { return "delivered".equals(status) || "failed".equals(status) || "cancelled".equals(status); }
    static boolean lostPlayback(String status,boolean currentGrant){return "executing".equals(status)&&!currentGrant;}
    static boolean valid(String id, String task, long validUntil, List<Step> steps) {
        if (id == null || id.isEmpty() || task == null || task.isEmpty() || steps == null || steps.isEmpty() || steps.size() > 6) return false;
        Set<String> ids = new HashSet<>(); long previousAt = 0L; int previousIndex = -1;
        for (Step step : steps) {
            if (step == null || step.id() == null || step.id().isEmpty() || !ids.add(step.id())
                    || step.index() <= previousIndex || step.scheduled() <= previousAt || step.expires() <= step.scheduled()
                    || step.expires() > validUntil || !("notification".equals(step.channel()) || "alarm".equals(step.channel()))) return false;
            previousAt = step.scheduled(); previousIndex = step.index();
        }
        return true;
    }
}
