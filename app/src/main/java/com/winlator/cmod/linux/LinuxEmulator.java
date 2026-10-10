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

/** The x86 emulators (Box64, later FEX) installed for Linux containers, one directory each. */
public final class LinuxEmulator {
    public static final String NONE = "none";
    public static final String BOX64 = "box64";
    public static final String FEX = "fex";

    /** Container extras. */
    public static final String EXTRA_EMULATOR = "linuxEmulator";
    public static final String EXTRA_ID = "linuxEmulatorId";
    public static final String EXTRA_PRESET = "linuxEmulatorPreset";

    public static final class Installed {
        public final String id;
        public final String name;
        public final String version;
        public final String emulator;
        public final File dir;

        Installed(String id, String name, String version, String emulator, File dir) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.emulator = emulator;
            this.dir = dir;
        }
    }

    private LinuxEmulator() {}

    public static File baseDir(Context context) {
        return LinuxPackages.baseDir(context, LinuxPackages.KIND_EMULATOR);
    }

    public static boolean isInstalled(Context context, String id) {
        if (id == null || id.isEmpty()) return false;
        File dir = new File(baseDir(context), id);
        return new File(dir, "bin/box64").isFile() || new File(dir, "bin/FEXInterpreter").isFile();
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
        result.sort(Comparator.comparing((Installed e) -> e.name).reversed());
        return result;
    }

    public static List<Installed> list(Context context, String emulator) {
        List<Installed> result = new ArrayList<>();
        for (Installed installed : list(context)) if (installed.emulator.equals(emulator)) result.add(installed);
        return result;
    }

    public static Installed find(Context context, String id) {
        for (Installed installed : list(context)) if (installed.id.equals(id)) return installed;
        return null;
    }

    public static boolean remove(Context context, String id) {
        if (id == null || id.isEmpty() || id.startsWith(".") || id.contains("/")) return false;
        return FileUtils.delete(new File(baseDir(context), id));
    }

    /** The emulator choice of a container: one of {@link #NONE}, {@link #BOX64}, {@link #FEX}. */
    public static String choice(Container container) {
        String value = container.getExtra(EXTRA_EMULATOR);
        return value.equals(BOX64) || value.equals(FEX) ? value : NONE;
    }

    /** The installed build a container is set to use: its own choice, or the only one installed for that emulator. */
    public static Installed resolve(Context context, Container container) {
        String choice = choice(container);
        if (choice.equals(NONE)) return null;
        List<Installed> candidates = list(context, choice);
        String id = container.getExtra(EXTRA_ID);
        for (Installed installed : candidates) if (installed.id.equals(id)) return installed;
        return candidates.size() == 1 && id.isEmpty() ? candidates.get(0) : null;
    }

    /** Name of a Linux container that is set to use this build, or null. */
    public static String containerUsing(Context context, String id) {
        for (Container container : new ContainerManager(context).getContainers()) {
            if (container.isLinux() && id.equals(container.getExtra(EXTRA_ID))) return container.getName();
        }
        return null;
    }

    private static Installed readInfo(File dir) {
        try {
            JSONObject info = new JSONObject(FileUtils.readString(new File(dir, LinuxRuntime.INFO_FILE)));
            String emulator = info.optString("emulator", new File(dir, "bin/box64").isFile() ? BOX64 : FEX);
            return new Installed(dir.getName(), info.optString("name", dir.getName()), info.optString("version", ""),
                    emulator, dir);
        }
        catch (Exception e) {
            String emulator = new File(dir, "bin/box64").isFile() ? BOX64 : FEX;
            return new Installed(dir.getName(), dir.getName(), "", emulator, dir);
        }
    }
}
