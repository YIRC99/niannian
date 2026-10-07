package dev.niannian.reminder;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.HashSet;
import java.util.Set;

/** Bounded network work; Android may defer it during Doze or battery restrictions. */
public final class ReminderSyncJob extends JobService {
    private final Set<JobParameters> running = new HashSet<>();
    @Override public synchronized boolean onStartJob(JobParameters params) {
        running.add(params);
        ReminderCoordinator.background(this, retry -> {
            synchronized (ReminderSyncJob.this) {
                if (!running.remove(params)) return;
                jobFinished(params, retry);
            }
        });
        return true;
    }
    @Override public synchronized boolean onStopJob(JobParameters params) {
        running.remove(params);
        return true;
    }
}
