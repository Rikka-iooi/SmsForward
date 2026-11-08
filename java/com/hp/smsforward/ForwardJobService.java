package com.hp.smsforward;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background delivery with network constraints and exponential backoff. JobScheduler owns the
 * retry lifecycle, so a slow SMTP server or a temporarily offline phone does not lose messages.
 */
public class ForwardJobService extends JobService {
    private static final String TAG = "SmsForwarder";
    private static final int JOB_ID = 4101;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void schedule(Context context) {
        Context app = context.getApplicationContext();
        JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;

        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID,
                new ComponentName(app, ForwardJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(0)
                .setBackoffCriteria(JobInfo.BACKOFF_POLICY_EXPONENTIAL, 30_000);
        try {
            builder.setPersisted(true);
            scheduler.schedule(builder.build());
        } catch (Exception e) {
            // Some OEM builds reject persisted jobs. A normal job still handles this session.
            try {
                JobInfo.Builder fallback = new JobInfo.Builder(JOB_ID,
                        new ComponentName(app, ForwardJobService.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setOverrideDeadline(0)
                        .setBackoffCriteria(JobInfo.BACKOFF_POLICY_EXPONENTIAL, 30_000);
                scheduler.schedule(fallback.build());
            } catch (Exception ignored) {
                Log.e(TAG, "Could not schedule forwarding job", e);
            }
        }
    }

    public static void cancel(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getApplicationContext()
                .getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) scheduler.cancel(JOB_ID);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        executor.execute(() -> {
            boolean retry = false;
            Db db = new Db(getApplicationContext());
            try {
                db.resetStaleSending(System.currentTimeMillis() - 2 * 60 * 1000L);
                db.prune();
                Config config = Config.load(this);
                if (!config.enabled) {
                    jobFinished(params, false);
                    return;
                }
                String configError = config.validateForForwarding();
                List<SmsRecord> pending = db.pending(10);
                if (configError != null) {
                    for (SmsRecord record : pending) {
                        db.markFailed(record.id, "配置不完整：" + configError);
                    }
                    jobFinished(params, false);
                    return;
                }
                for (SmsRecord record : pending) {
                    if (!db.claim(record.id)) continue;
                    try {
                        Forwarder.send(config, record);
                        db.markSent(record.id, config.channel);
                        Log.i(TAG, "Forwarded SMS id=" + record.id);
                    } catch (Exception e) {
                        String message = userMessage(e);
                        db.markAttemptFailed(record.id, Forwarder.MAX_ATTEMPTS, message);
                        Log.w(TAG, "Forward failed id=" + record.id + ": " + message);
                    }
                }
                retry = db.hasPending();
            } catch (Throwable t) {
                retry = true;
                Log.e(TAG, "Forwarding job crashed", t);
            } finally {
                db.close();
            }
            jobFinished(params, retry);
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private static String userMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = throwable.getClass().getSimpleName();
        }
        message = message.replace('\r', ' ').replace('\n', ' ').trim();
        return message.length() > 900 ? message.substring(0, 900) : message;
    }
}
