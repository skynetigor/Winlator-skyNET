package com.winlator.cmod.linux;

import android.content.Context;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.FileUtils;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** The installed Linux userlands (glibc aarch64 rootfs) that Linux containers run in, one directory each. */
public final class LinuxRuntime {
    public static final String INFO_FILE = ".runtime.json";

    public static final class Installed {
        public final String id;
        public final String name;
        public final String version;

        Installed(String id, String name, String version) {
            this.id = id;
            this.name = name;
            this.version = version;
        }
    }

    private LinuxRuntime() {}

    public static File runtimesDir(Context context) {
        return new File(context.getFilesDir(), "linux-runtimes");
    }

    public static File rootDir(Context context, String id) {
        return new File(runtimesDir(context), id);
    }

    public static boolean isInstalled(Context context, String id) {
        if (id == null || id.isEmpty()) return false;
        File root = rootDir(context, id);
        return new File(root, "usr/bin/gamescope").exists()
                && new File(root, "usr/local/bin/bannerlator-session").exists()
                && new File(root, "opt/android-host/proot").exists();
    }

    /** Installed runtimes, newest name first. Also moves a step-1 {@code files/linuxfs} into place. */
    public static synchronized List<Installed> listInstalled(Context context) {
        migrateLegacy(context);
        LinuxRuntimeInstaller.recoverInterruptedSwaps(context);
        List<Installed> result = new ArrayList<>();
        File[] dirs = runtimesDir(context).listFiles();
        if (dirs == null) return result;
        for (File dir : dirs) {
            if (!dir.isDirectory() || dir.getName().startsWith(".")) continue;
            if (dir.getName().endsWith(".new") || dir.getName().endsWith(".old")) continue;
            if (!isInstalled(context, dir.getName())) continue;
            result.add(readInfo(dir));
        }
        result.sort(Comparator.comparing((Installed i) -> i.name).reversed());
        return result;
    }

    public static Installed find(Context context, String id) {
        for (Installed runtime : listInstalled(context)) if (runtime.id.equals(id)) return runtime;
        return null;
    }

    /** The runtime a container uses, or null if it is not installed. A container with none chosen gets the only one. */
    public static Installed resolve(Context context, Container container) {
        List<Installed> installed = listInstalled(context);
        for (Installed runtime : installed) if (runtime.id.equals(container.getLinuxRuntime())) return runtime;
        if (container.getLinuxRuntime().isEmpty() && installed.size() == 1) return installed.get(0);
        return null;
    }

    public static boolean remove(Context context, String id) {
        if (id == null || id.isEmpty() || id.startsWith(".") || id.contains("/")) return false;
        return FileUtils.delete(rootDir(context, id));
    }

    /**
     * Name of a Linux container that runs in this runtime, or null. A container with no runtime chosen
     * (made before runtimes were selectable) counts as using the runtime when it is the only one.
     */
    public static String containerUsing(Context context, String id) {
        int installedCount = listInstalled(context).size();
        for (Container container : new ContainerManager(context).getContainers()) {
            if (!container.isLinux()) continue;
            String chosen = container.getLinuxRuntime();
            if (id.equals(chosen) || (chosen.isEmpty() && installedCount == 1)) return container.getName();
        }
        return null;
    }

    private static Installed readInfo(File dir) {
        try {
            JSONObject info = new JSONObject(FileUtils.readString(new File(dir, INFO_FILE)));
            return new Installed(dir.getName(), info.optString("name", dir.getName()), info.optString("version", ""));
        }
        catch (Exception e) {
            return new Installed(dir.getName(), dir.getName(), "");
        }
    }

    static void writeInfo(File dir, String id, String name, String version) {
        writeInfo(dir, id, name, version, null);
    }

    /** {@code emulator} (for example "box64") is only set for x86 emulator packages. */
    static void writeInfo(File dir, String id, String name, String version, String emulator) {
        try {
            JSONObject info = new JSONObject();
            info.put("id", id);
            info.put("name", name);
            info.put("version", version);
            if (emulator != null) info.put("emulator", emulator);
            FileUtils.writeString(new File(dir, INFO_FILE), info.toString());
        }
        catch (Exception e) {}
    }

    private static void migrateLegacy(Context context) {
        File legacy = new File(context.getFilesDir(), "linuxfs");
        if (!legacy.isDirectory()) return;
        File versionFile = new File(legacy, ".version");
        String version = versionFile.isFile() ? FileUtils.readString(versionFile).trim() : "";
        String id = "bannerlator-" + (version.isEmpty() ? "legacy" : version);
        File target = rootDir(context, id);
        runtimesDir(context).mkdirs();
        if (target.exists() || !legacy.renameTo(target)) return;
        writeInfo(target, id, LinuxRuntimeCatalog.BANNERLATOR_NAME + (version.isEmpty() ? "" : " " + version), version);
    }
}
