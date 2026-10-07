package dev.niannian.reminder;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Bounded network work; Android may defer it during Doze or battery restrictions. */
public final class ReminderSyncJob extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        ReminderCoordinator.background(this, () -> jobFinished(params, false));
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { return true; }
}
