package com.winlator.cmod.steam;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.RegistryCache;
import com.winlator.cmod.linux.LinuxDriverManager;
import com.winlator.cmod.linux.LinuxEmulator;
import com.winlator.cmod.linux.LinuxRuntime;
import com.winlator.cmod.linux.LinuxRuntimeCatalog;
import com.winlator.cmod.linux.LinuxRuntimeInstaller;
import com.winlator.cmod.linux.LinuxSession;
import com.winlator.cmod.linux.LinuxSteamGames;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The app's one Steam container. The user does not create it: it appears when the components Steam needs (the Linux runtime,
 * Box64 for the games' x86-64 code, a Turnip driver) are installed, built from a configuration the app defines
 * ({@link Defaults}, the "steam" block of the registry's linux.json). It is marked with the container extra
 * {@link #EXTRA_MANAGED}, can be configured but not removed, renamed or duplicated from the container list, and owns the Steam
 * shortcut in the library and the shortcuts of the games installed in its Steam.
 */
public final class SteamContainer {
    private static final String TAG = "SteamContainer";
    /** Container extra: {@link #MANAGED_STEAM} for the app's Steam container. */
    public static final String EXTRA_MANAGED = "linuxManaged";
    public static final String MANAGED_STEAM = "steam";
    public static final String NAME = "Steam";
    private static final Object LOCK = new Object();

    /** What a new Steam container is made of. */
    public static final class Defaults {
        /** The runtime the app prefers (an id of the runtime catalog). */
        public final String runtime;
        public final String emulator;
        public final String emulatorPreset;
        public final String driver;
        public final String glDriver;
        public final String vulkanPresent;

        Defaults(String runtime, String emulator, String emulatorPreset, String driver, String glDriver, String vulkanPresent) {
            this.runtime = runtime;
            this.emulator = emulator;
            this.emulatorPreset = emulatorPreset;
            this.driver = driver;
            this.glDriver = glDriver;
            this.vulkanPresent = vulkanPresent;
        }
    }

    /** What has to be downloaded for Steam to run, and how much it is. */
    public static final class Plan {
        public final List<LinuxRuntimeCatalog.Entry> install;
        /** Download size in bytes. */
        public final long download;
        /** Disk space the installs need while they run (the largest one at a time, with its unpacked copy). */
        public final long space;
        /** Why a plan could not be made (no network, a package missing from the catalog); null when it was. */
        public final String error;

        Plan(List<LinuxRuntimeCatalog.Entry> install, long download, long space, String error) {
            this.install = install;
            this.download = download;
            this.space = space;
            this.error = error;
        }
    }

    private SteamContainer() {}

    public static boolean isManaged(Container container) {
        return container != null && container.isLinux() && MANAGED_STEAM.equals(container.getExtra(EXTRA_MANAGED));
    }

    public static Container find(ContainerManager manager) {
        for (Container container : manager.getContainers()) if (isManaged(container)) return container;
        return null;
    }

    /** The configuration of a new Steam container: the registry's "steam" block, or the built-in one when it cannot be read. */
    public static Defaults defaults(Context context, boolean allowNetwork) {
        Defaults builtIn = new Defaults("bannerlator-r9", "box64-0.4.4", "PERFORMANCE", "turnip-v26.3.0-20261011-a8xx-linux", "zink", "native");
        String text = RegistryCache.get(context, "linux.json", RegistryCache.TTL_INDEX, allowNetwork);
        if (text == null) return builtIn;
        try {
            JSONObject steam = new JSONObject(text).optJSONObject("steam");
            if (steam == null) return builtIn;
            return new Defaults(
                    id(steam, "runtime", builtIn.runtime), id(steam, "emulator", builtIn.emulator),
                    steam.optString("emulatorPreset", builtIn.emulatorPreset), id(steam, "driver", builtIn.driver),
                    steam.optString("glDriver", builtIn.glDriver).equals("software") ? "software" : "zink",
                    steam.optString("vulkanPresent", builtIn.vulkanPresent).equals("sw") ? "sw" : "native");
        }
        catch (JSONException e) {
            Log.w(TAG, "Bad steam block in linux.json", e);
            return builtIn;
        }
    }

    private static String id(JSONObject object, String key, String fallback) {
        String value = object.optString(key, fallback);
        return value.matches("[A-Za-z0-9][A-Za-z0-9._-]*") ? value : fallback;
    }

    private static boolean runtimeReady(Context context) {
        return LinuxRuntime.anyInstalled(context);
    }

    private static boolean emulatorReady(Context context) {
        return !LinuxEmulator.list(context, LinuxEmulator.BOX64).isEmpty();
    }

    private static boolean driverReady(Context context, Defaults defaults) {
        return LinuxDriverManager.isInstalled(context, defaults.driver) || !LinuxDriverManager.list(context).isEmpty();
    }

    /** Everything Steam needs is installed (cheap: looks at the disk only). */
    public static boolean componentsReady(Context context, Defaults defaults) {
        return runtimeReady(context) && emulatorReady(context) && driverReady(context, defaults);
    }

    /**
     * What is still to be downloaded. Blocking: it reads the catalogs, so call it off the main thread. An empty plan means
     * everything is installed.
     */
    public static Plan plan(Context context) {
        Defaults defaults = defaults(context, true);
        List<LinuxRuntimeCatalog.Entry> install = new ArrayList<>();
        if (!runtimeReady(context)) {
            List<LinuxRuntimeCatalog.Entry> runtimes = LinuxRuntimeCatalog.fetch();
            LinuxRuntimeCatalog.Entry entry = pick(runtimes, defaults.runtime);
            if (entry == null) return failed("The Linux runtime catalog cannot be read. Check the connection.");
            install.add(entry);
        }
        if (!emulatorReady(context)) {
            List<LinuxRuntimeCatalog.Entry> emulators = boxOnly(LinuxRuntimeCatalog.fetchEmulators());
            LinuxRuntimeCatalog.Entry entry = pick(emulators, defaults.emulator);
            if (entry == null) return failed("Box64 is not in the catalog. Check the connection.");
            install.add(entry);
        }
        if (!driverReady(context, defaults)) {
            LinuxRuntimeCatalog.Entry entry = pick(LinuxRuntimeCatalog.fetchDrivers(), defaults.driver);
            if (entry == null) return failed("The Turnip driver is not in the catalog. Check the connection.");
            install.add(entry);
        }
        long download = 0, largest = 0;
        for (LinuxRuntimeCatalog.Entry entry : install) {
            download += entry.size;
            largest = Math.max(largest, LinuxRuntimeInstaller.requiredBytes(entry));
        }
        return new Plan(install, download, largest, null);
    }

    private static Plan failed(String error) {
        return new Plan(new ArrayList<>(), 0, 0, error);
    }

    /** The entry with this id, or the first one when the catalog does not have it (null for no entries at all). */
    private static LinuxRuntimeCatalog.Entry pick(List<LinuxRuntimeCatalog.Entry> entries, String preferred) {
        if (entries == null || entries.isEmpty()) return null;
        for (LinuxRuntimeCatalog.Entry entry : entries) if (entry.id.equals(preferred)) return entry;
        return entries.get(0);
    }

    private static List<LinuxRuntimeCatalog.Entry> boxOnly(List<LinuxRuntimeCatalog.Entry> entries) {
        List<LinuxRuntimeCatalog.Entry> result = new ArrayList<>();
        if (entries == null) return result;
        for (LinuxRuntimeCatalog.Entry entry : entries) if (LinuxEmulator.BOX64.equals(entry.emulator)) result.add(entry);
        return result;
    }

    /**
     * Brings the Steam container and its shortcut in line with what is installed. Idempotent and cheap (it only looks at
     * the disk), so it is called whenever the library is loaded and when the components finish installing:
     * every component installed and no Steam container yet creates it; the Steam shortcut exists only while the container's
     * runtime does. The container itself is never removed here. Do not call while iterating the manager's containers.
     */
    public static Container ensure(Context context, ContainerManager manager) {
        synchronized (LOCK) {
            Container steam = find(manager);
            if (steam == null) {
                Defaults defaults = defaults(context, false);
                if (!componentsReady(context, defaults)) return null;
                steam = create(context, manager, defaults);
                if (steam == null) return null;
            }
            ContainerManager.syncSteamShortcut(steam, LinuxRuntime.resolve(context, steam) != null);
            return steam;
        }
    }

    private static Container create(Context context, ContainerManager manager, Defaults defaults) {
        // The configuration names builds by id; an installed build of the same kind stands in for one that is not there.
        List<LinuxRuntime.Installed> runtimes = LinuxRuntime.listInstalled(context);
        String runtime = LinuxRuntime.isInstalled(context, defaults.runtime) ? defaults.runtime : runtimes.isEmpty() ? "" : runtimes.get(0).id;
        List<LinuxEmulator.Installed> boxes = LinuxEmulator.list(context, LinuxEmulator.BOX64);
        String box64 = LinuxEmulator.isInstalled(context, defaults.emulator) ? defaults.emulator : boxes.isEmpty() ? "" : boxes.get(0).id;
        List<LinuxDriverManager.Installed> drivers = LinuxDriverManager.list(context);
        String driver = LinuxDriverManager.isInstalled(context, defaults.driver) ? defaults.driver : drivers.isEmpty() ? "" : drivers.get(0).id;
        if (runtime.isEmpty() || box64.isEmpty()) return null;
        try {
            JSONObject data = new JSONObject();
            data.put("name", NAME);
            // Steam always runs at the device's own size.
            data.put("screenSize", "native");
            data.put("linuxRuntime", runtime);
            data.put("envVars", "");
            Container container = manager.createLinuxContainerFromData(data);
            if (container == null) return null;
            container.putExtra(EXTRA_MANAGED, MANAGED_STEAM);
            container.putExtra(LinuxEmulator.EXTRA_EMULATOR, LinuxEmulator.BOX64);
            container.putExtra(LinuxEmulator.EXTRA_ID, box64);
            container.putExtra(LinuxEmulator.EXTRA_PRESET, defaults.emulatorPreset);
            container.putExtra(LinuxDriverManager.EXTRA_DRIVER, driver);
            container.putExtra(LinuxSession.EXTRA_GL_DRIVER, defaults.glDriver);
            container.putExtra(LinuxSession.EXTRA_VULKAN_PRESENT, defaults.vulkanPresent);
            container.putExtra(LinuxSession.EXTRA_TOUCHSCREEN, "1");
            container.putExtra(LinuxSession.EXTRA_KEYBOARD_BUTTON, "1");
            container.putExtra(LinuxSession.EXTRA_AUTO_KEYBOARD, "1");
            container.saveData();
            Log.i(TAG, "Created the Steam container " + container.id);
            return container;
        }
        catch (JSONException e) {
            Log.e(TAG, "Cannot create the Steam container", e);
            return null;
        }
    }

    /** Removes the Steam container with everything in it (the login, the downloaded games) and makes a new one from the defaults. */
    public static Container reset(Context context, ContainerManager manager) {
        synchronized (LOCK) {
            Container steam = find(manager);
            if (steam != null) manager.removeManagedContainer(steam);
            return ensure(context, manager);
        }
    }

    /** The games installed in Steam, for the status line ("3 games"). */
    public static int gameCount(Container steam) {
        return steam == null ? 0 : LinuxSteamGames.count(steam);
    }
}
