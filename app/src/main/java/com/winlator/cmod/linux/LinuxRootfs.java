package com.winlator.cmod.linux;

import android.content.Context;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.FileUtils;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The x86-64 root filesystems FEX runs programs in, one directory each (files/linux-rootfs/&lt;id&gt;).
 *
 * FEX does not translate the system calls only: the emulated program also needs an x86-64 userland (a libc, a dynamic
 * loader, the libraries a game links against), and FEX looks everything up in this tree first and on the phone's own
 * file system second. Box64 needs none of it: it runs the program against the runtime's libraries through wrappers.
 */
public final class LinuxRootfs {
    public static final class Installed {
        public final String id;
        public final String name;
        public final String version;
        public final File dir;

        Installed(String id, String name, String version, File dir) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.dir = dir;
        }
    }

    private LinuxRootfs() {}

    public static File baseDir(Context context) {
        return LinuxPackages.baseDir(context, LinuxPackages.KIND_ROOTFS);
    }

    public static File rootDir(Context context, String id) {
        return new File(baseDir(context), id);
    }

    /** A tree that has a dynamic loader and a libc in it: enough to tell a finished install from a stray folder. */
    public static boolean isInstalled(Context context, String id) {
        if (id == null || id.isEmpty() || id.startsWith(".") || id.contains("/")) return false;
        File dir = rootDir(context, id);
        return new File(dir, "usr").isDirectory() && new File(dir, LinuxRuntime.INFO_FILE).isFile();
    }

    public static List<Installed> list(Context context) {
        List<Installed> result = new ArrayList<>();
        File[] dirs = baseDir(context).listFiles();
        if (dirs == null) return result;
        for (File dir : dirs) {
            if (!dir.isDirectory() || dir.getName().startsWith(".")) continue;
            if (dir.getName().endsWith(".new") || dir.getName().endsWith(".old")) continue;
            if (!isInstalled(context, dir.getName())) continue;
            result.add(readInfo(dir));
        }
        result.sort(Comparator.comparing((Installed r) -> r.name));
        return result;
    }

    public static Installed find(Context context, String id) {
        for (Installed installed : list(context)) if (installed.id.equals(id)) return installed;
        return null;
    }

    public static boolean remove(Context context, String id) {
        if (id == null || id.isEmpty() || id.startsWith(".") || id.contains("/")) return false;
        return FileUtils.delete(rootDir(context, id));
    }

    /** The root filesystem a container is set to use: its own choice, or the only one installed. */
    public static Installed resolve(Context context, Container container) {
        List<Installed> installed = list(context);
        String id = container.getExtra(LinuxEmulator.EXTRA_ROOTFS);
        for (Installed candidate : installed) if (candidate.id.equals(id)) return candidate;
        return installed.size() == 1 && id.isEmpty() ? installed.get(0) : null;
    }

    /** Name of a Linux container that is set to use this root filesystem, or null. */
    public static String containerUsing(Context context, String id) {
        for (Container container : new ContainerManager(context).getContainers()) {
            if (container.isLinux() && id.equals(container.getExtra(LinuxEmulator.EXTRA_ROOTFS))) return container.getName();
        }
        return null;
    }

    private static Installed readInfo(File dir) {
        try {
            JSONObject info = new JSONObject(FileUtils.readString(new File(dir, LinuxRuntime.INFO_FILE)));
            return new Installed(dir.getName(), info.optString("name", dir.getName()), info.optString("version", ""), dir);
        }
        catch (Exception e) {
            return new Installed(dir.getName(), dir.getName(), "", dir);
        }
    }
}
