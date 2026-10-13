package com.winlator.cmod.linux;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
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
        return startAll(context, java.util.Collections.singletonList(entry));
    }

    /**
     * Installs several packages one after another, the small ones first; does nothing if an install is already running. It stops
     * at the first failure (or when the user cancels), and the listener hears {@code onFinished} once, with the id of the package
     * that failed (the last one when none did) and the error (null when all were installed).
     */
    public static synchronized boolean startAll(Context context, List<LinuxRuntimeCatalog.Entry> entries) {
        if (runningId != null || entries == null || entries.isEmpty()) return false;
        List<LinuxRuntimeCatalog.Entry> queue = new ArrayList<>(entries);
        queue.sort((a, b) -> Long.compare(a.size, b.size));
        runningId = queue.get(0).id;
        phase = LinuxRuntimeInstaller.Phase.DOWNLOAD;
        percent = 0;
        hasFinished = false;
        Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            String failed = null;
            String error = null;
            String lastId = queue.get(0).id;
            for (LinuxRuntimeCatalog.Entry entry : queue) {
                lastId = entry.id;
                main.post(() -> {
                    synchronized (LinuxRuntimeInstallTask.class) {
                        runningId = entry.id;
                        phase = LinuxRuntimeInstaller.Phase.DOWNLOAD;
                        percent = 0;
                        if (listener != null) listener.onProgress(entry.id, phase, 0);
                    }
                });
                error = LinuxRuntimeInstaller.install(appContext, entry, (p, pct) -> main.post(() -> {
                    synchronized (LinuxRuntimeInstallTask.class) {
                        phase = p;
                        percent = pct;
                        if (listener != null) listener.onProgress(entry.id, p, pct);
                    }
                }));
                if (error != null) {
                    failed = entry.id;
                    break;
                }
            }
            final String finalId = failed != null ? failed : lastId;
            final String finalError = error;
            main.post(() -> {
                synchronized (LinuxRuntimeInstallTask.class) {
                    runningId = null;
                    finishedId = finalId;
                    finishedError = finalError;
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
