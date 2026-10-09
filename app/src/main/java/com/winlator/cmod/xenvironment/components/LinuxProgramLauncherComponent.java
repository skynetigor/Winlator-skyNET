package com.winlator.cmod.xenvironment.components;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.linux.LinuxRuntime;
import com.winlator.cmod.linux.LinuxSession;
import com.winlator.cmod.xenvironment.EnvironmentComponent;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Starts a Linux session (proot plus the guest program) and takes the whole process tree down on stop. */
public class LinuxProgramLauncherComponent extends EnvironmentComponent {
    private static final String TAG = "LinuxProgramLauncher";
    private static final int SIGSTOP = 19;
    private static final int SIGCONT = 18;
    private static final int SIGTERM = 15;
    private static final int SIGKILL = 9;
    private static final long TERM_GRACE_MS = 1500;

    private final Container container;
    private final LinuxRuntime.Installed runtime;
    private final String[] guestCommand;
    private Callback<Integer> terminationCallback;
    private final Object lock = new Object();
    private int pid = -1;
    private Thread reaper;

    public LinuxProgramLauncherComponent(Container container, LinuxRuntime.Installed runtime, String[] guestCommand) {
        this.container = container;
        this.runtime = runtime;
        this.guestCommand = guestCommand;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    @Override
    public void start() {
        Context context = environment.getContext();
        ImageFs imageFs = environment.getImageFs();
        LinuxSession.Launch launch = LinuxSession.build(context, imageFs, container, runtime, guestCommand);
        synchronized (lock) {
            pid = ProcessHelper.exec(launch.argv, launch.hostEnv, launch.workingDir, status -> {
                synchronized (lock) {
                    pid = -1;
                }
                if (terminationCallback != null) terminationCallback.call(status);
            });
            if (pid == -1) Log.e(TAG, "The Linux session did not start");
        }
        applyCpuAffinity();
    }

    /**
     * Pins the session to the container's CPU cores. proot forks the guest's first process before we know
     * its pid, so the mask is applied to the whole tree a few times while the session starts up; processes
     * started after that inherit it.
     */
    private void applyCpuAffinity() {
        final int root;
        synchronized (lock) {
            root = pid;
        }
        final int mask = affinityMask(container);
        if (root == -1 || mask == 0) return;
        new Thread(() -> {
            long[] delays = {0, 200, 800, 2000};
            for (long delay : delays) {
                try {
                    Thread.sleep(delay);
                }
                catch (InterruptedException ignored) {}
                synchronized (lock) {
                    if (pid != root) return;
                }
                for (int p : processTree(root)) {
                    int error = ProcessHelper.setProcessAffinity(p, mask);
                    if (error != 0) Log.w(TAG, "sched_setaffinity(" + p + ") failed: " + error);
                }
            }
        }, "linux-session-affinity").start();
    }

    /** The mask for the container's cores, or 0 when it uses all of them (or the list is unusable). */
    private static int affinityMask(Container container) {
        String cpuList = container.getCPUList();
        if (cpuList == null || cpuList.isEmpty()) return 0;
        int mask;
        try {
            mask = ProcessHelper.getAffinityMask(cpuList);
        }
        catch (NumberFormatException e) {
            return 0;
        }
        int cores = Runtime.getRuntime().availableProcessors();
        int all = cores >= 32 ? -1 : (1 << cores) - 1;
        return mask == all ? 0 : mask;
    }

    @Override
    public void stop() {
        final int root;
        synchronized (lock) {
            root = pid;
            pid = -1;
        }
        final String runtimePath = LinuxRuntime.rootDir(environment.getContext(), runtime.id).getPath();
        if (root == -1) {
            killLeftovers(runtimePath);
            return;
        }

        List<Integer> tree = processTree(root);
        for (int p : tree) Process.sendSignal(p, SIGTERM);
        // Give the programs a moment to close their windows before the rest is killed.
        reaper = new Thread(() -> {
            try {
                Thread.sleep(TERM_GRACE_MS);
            }
            catch (InterruptedException ignored) {}
            for (int p : processTree(root)) Process.sendSignal(p, SIGKILL);
            Process.sendSignal(root, SIGKILL);
            killLeftovers(runtimePath);
        }, "linux-session-reaper");
        reaper.start();
    }

    /** Blocks until the grace period is over and everything has been killed. Call off the main thread. */
    public void awaitTeardown() {
        Thread thread = reaper;
        if (thread == null) return;
        try {
            thread.join(TERM_GRACE_MS + 3000);
        }
        catch (InterruptedException ignored) {}
    }

    public void suspendProcess() {
        signalTree(SIGSTOP);
    }

    public void resumeProcess() {
        signalTree(SIGCONT);
    }

    private void signalTree(int signal) {
        int root;
        synchronized (lock) {
            root = pid;
        }
        if (root == -1) return;
        for (int p : processTree(root)) Process.sendSignal(p, signal);
    }

    /** The root process and every descendant, read from /proc (the app can see its own uid's processes). */
    private static List<Integer> processTree(int root) {
        Map<Integer, List<Integer>> children = new HashMap<>();
        File[] entries = new File("/proc").listFiles();
        if (entries != null) {
            for (File entry : entries) {
                int pid;
                try {
                    pid = Integer.parseInt(entry.getName());
                }
                catch (NumberFormatException e) {
                    continue;
                }
                int parent = parentOf(pid);
                if (parent > 0) children.computeIfAbsent(parent, k -> new ArrayList<>()).add(pid);
            }
        }
        List<Integer> result = new ArrayList<>();
        result.add(root);
        for (int i = 0; i < result.size(); i++) {
            List<Integer> kids = children.get(result.get(i));
            if (kids != null) result.addAll(kids);
        }
        return result;
    }

    private static int parentOf(int pid) {
        try {
            String stat = new String(Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()));
            // "pid (comm) S ppid ...": comm can hold spaces and parentheses, so start after the last ')'.
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return Integer.parseInt(fields[1]);
        }
        catch (Exception e) {
            return -1;
        }
    }

    /** Processes that escaped the tree (reparented to init) but still run a binary from the runtime. */
    private static void killLeftovers(String runtimePath) {
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            try {
                int pid = Integer.parseInt(entry.getName());
                String exe = Files.readSymbolicLink(new File(entry, "exe").toPath()).toString();
                if (exe.startsWith(runtimePath + "/")) Process.sendSignal(pid, SIGKILL);
            }
            catch (Exception ignored) {}
        }
    }
}
