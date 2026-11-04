package com.noticehub.app;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

public final class ScheduleSyncWorker extends Worker {
    public ScheduleSyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters) { super(context, parameters); }
    @NonNull @Override public Result doWork() { return new ScheduleRepository(getApplicationContext()).sync() ? Result.success() : Result.retry(); }
    public static void schedule(Context context, boolean immediate) {
        WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        if (immediate) {
            manager.enqueueUniqueWork("schedule-sync-now", ExistingWorkPolicy.APPEND_OR_REPLACE,
                new OneTimeWorkRequest.Builder(ScheduleSyncWorker.class).setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build());
        }
        manager.enqueueUniquePeriodicWork("schedule-sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
            new PeriodicWorkRequest.Builder(ScheduleSyncWorker.class, 15, TimeUnit.MINUTES).setConstraints(constraints).build());
    }
}
