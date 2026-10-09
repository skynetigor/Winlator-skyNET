package com.winlator.cmod.linux;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one Linux runtime install running for the whole app. It outlives the screens: a screen
 * attaches a listener when it appears and is caught up with the current progress.
 */
public final class LinuxRuntimeInstallTask {
    public interface Listener {
        void onProgress(String id, LinuxRuntimeInstaller.Phase phase, int percent);

        /** error is null on success. */
        void onFinished(String id, String error);
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static String runningId;
    private static LinuxRuntimeInstaller.Phase phase;
    private static int percent;
    private static Listener listener;
    private static String finishedId;
    private static String finishedError;
    private static boolean hasFinished;

    private LinuxRuntimeInstallTask() {}

    public static synchronized String runningId() {
        return runningId;
    }

    /** Starts an install; does nothing if one is already running. */
    public static synchronized boolean start(Context context, LinuxRuntimeCatalog.Entry entry) {
        if (runningId != null) return false;
        runningId = entry.id;
        phase = LinuxRuntimeInstaller.Phase.DOWNLOAD;
        percent = 0;
        hasFinished = false;
        Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            String error = LinuxRuntimeInstaller.install(appContext, entry, (p, pct) -> main.post(() -> {
                synchronized (LinuxRuntimeInstallTask.class) {
                    phase = p;
                    percent = pct;
                    if (listener != null) listener.onProgress(entry.id, p, pct);
                }
            }));
            main.post(() -> {
                synchronized (LinuxRuntimeInstallTask.class) {
                    runningId = null;
                    finishedId = entry.id;
                    finishedError = error;
                    hasFinished = true;
                    if (listener != null) deliverFinished();
                }
            });
        });
        return true;
    }

    public static void cancel() {
        LinuxRuntimeInstaller.cancel();
    }

    /** Attach in onResume and detach (null) in onPause. A running install or an unseen result is replayed. */
    public static synchronized void setListener(Listener newListener) {
        listener = newListener;
        if (newListener == null) return;
        if (runningId != null) newListener.onProgress(runningId, phase, percent);
        else if (hasFinished) deliverFinished();
    }

    private static void deliverFinished() {
        hasFinished = false;
        listener.onFinished(finishedId, finishedError);
    }
}
