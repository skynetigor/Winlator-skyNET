package com.winlator.cmod.linux;

import android.content.Context;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.fexcore.FEXCorePreset;
import com.winlator.cmod.fexcore.FEXCorePresetManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything FEX needs to start a program in a Linux container: a configuration file in the container's home and the
 * FEX_* variables of its preset. FEX reads $HOME/.config/fex-emu/Config.json by itself (HOME is /root in a session).
 *
 * Why a file and not variables for most of it: FEX takes any option from a FEX_<OPTION> variable, but splits the
 * variable at its last "=", so a value that holds an "=" (the guest's environment, "LD_PRELOAD=...") cannot be given
 * that way. The file also names the thunk libraries, which forward the guest's Vulkan and OpenGL calls to the
 * runtime's own libraries instead of emulating a driver.
 */
final class LinuxFex {
    private static final String CONFIG_DIR = ".config/fex-emu";
    static final String NEEDS_ROOTFS = "FEX needs an x86-64 root filesystem. Install one in Components, Linux Root "
            + "Filesystem, and choose it in the container's settings.";

    private LinuxFex() {}

    /** The preset used when a container names none: the one FEXCore's own Wine containers start with. */
    static String presetId(Container container) {
        String id = container.getExtra(LinuxEmulator.EXTRA_FEX_PRESET);
        return id.isEmpty() ? FEXCorePreset.INTERMEDIATE : id;
    }

    /** GPU thunks are on unless the container turned them off, and only when the package carries them. */
    static boolean thunksEnabled(Container container, LinuxEmulator.Installed emulator) {
        return !"0".equals(container.getExtra(LinuxEmulator.EXTRA_FEX_THUNKS)) && new File(emulator.dir, "share/fex-emu/ThunksDB.json").isFile()
                && new File(emulator.dir, "lib/fex-emu/HostThunks").isDirectory()
                && new File(emulator.dir, "share/fex-emu/GuestThunks").isDirectory();
    }

    /**
     * Writes the configuration and adds the preset's variables to the session's environment.
     *
     * @return null when FEX is ready to run, otherwise why it is not
     */
    static String prepare(Context context, Container container, LinuxEmulator.Installed emulator, Map<String, String> env) {
        LinuxRootfs.Installed rootfs = LinuxRootfs.resolve(context, container);
        if (rootfs == null) return NEEDS_ROOTFS;

        File configDir = new File(container.getLinuxHomeDir(), CONFIG_DIR);
        if (!configDir.isDirectory() && !configDir.mkdirs()) return "Cannot create " + configDir;

        boolean thunks = thunksEnabled(container, emulator);
        StringBuilder config = new StringBuilder("{\n  \"Config\": {\n");
        appendOption(config, "RootFS", rootfs.dir.getPath());
        if (thunks) {
            appendOption(config, "ThunkHostLibs", new File(emulator.dir, "lib/fex-emu/HostThunks").getPath());
            appendOption(config, "ThunkGuestLibs", new File(emulator.dir, "share/fex-emu/GuestThunks").getPath());
        }
        // The guest's own environment: our x86-64 shims (the gamepad's evdev ioctls and uevent socket, and CEF's
        // sandbox check) are the ones Box64 preloads. FEX falls back to the phone's file system for a path the root
        // filesystem does not have, so the files under the app's data folder can be named as they are.
        List<String> preload = new ArrayList<>();
        for (String name : new String[]{"libskyshim_x64.so", "libcefnosb_x64.so"}) {
            File shim = LinuxSession.stageAsset(context, "linux-shim", name);
            if (shim != null) preload.add(shim.getPath());
        }
        if (!preload.isEmpty()) appendOption(config, "Env", "LD_PRELOAD=" + String.join(":", preload));
        // Trailing commas are not JSON: the last option is the one written last, so close it here.
        int last = config.lastIndexOf(",\n");
        if (last >= 0 && last == config.length() - 2) config.setLength(last);
        config.append("\n  }");
        if (thunks) {
            // The overlays a thunk replaces are listed in the database; which of them are on is chosen here. Vulkan is
            // what a game draws with. The OpenGL thunk is left off: with it on BeamNG's start hung (every thread asleep
            // behind eight llvmpipe workers) and without it the game ran on.
            config.append(",\n  \"ThunksDB\": { \"Vulkan\": 1, \"GL\": 0, \"drm\": 0, \"asound\": 0, \"WaylandClient\": 0, \"cuda\": 0 }");
        }
        config.append("\n}\n");

        if (!FileUtils.writeString(new File(configDir, "Config.json"), config.toString())) return "Cannot write FEX's configuration";
        if (thunks) {
            File database = new File(emulator.dir, "share/fex-emu/ThunksDB.json");
            FileUtils.copy(database, new File(configDir, "ThunksDB.json"));
        }

        // FEX's thunk loader prints a line for every function it links (thousands for Vulkan), and a game's log is no
        // place for them. The container's own variables are applied after this, so FEX_SILENTLOG=0 there brings them back.
        env.put("FEX_SILENTLOG", "1");

        EnvVars preset = FEXCorePresetManager.getEnvVars(context, presetId(container));
        for (String pair : preset.toStringArray()) {
            int index = pair.indexOf('=');
            if (index > 0) env.put(pair.substring(0, index), pair.substring(index + 1));
        }
        return null;
    }

    /** One option of the file's Config block; a repeated name is how FEX's JSON holds a list ("Env" more than once). */
    private static void appendOption(StringBuilder out, String name, String value) {
        out.append("    \"").append(name).append("\": \"").append(escape(value)).append("\",\n");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
