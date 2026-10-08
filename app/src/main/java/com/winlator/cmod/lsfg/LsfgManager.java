package com.winlator.cmod.lsfg;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import android.view.Choreographer;
import android.view.WindowManager;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lossless Scaling frame generation (LSFG) through the lsfg-vk Vulkan layer.
 *
 * The layer binary ships in jniLibs and its manifest in assets. Both are installed once into
 * the imagefs. The user's Lossless.dll is a single app-wide component (see {@link #getDllFile}),
 * with app-wide defaults for flow scale, performance mode and present mode. A container only
 * stores its multiplier ("Off" by default) and optional overrides of those defaults; its
 * conf.toml lives in the container's home. The manifest is placed outside every directory the
 * Vulkan loader searches by default, so the layer only loads when {@link #prepareLaunch} adds it
 * through VK_ADD_IMPLICIT_LAYER_PATH.
 */
public final class LsfgManager {
    private static final String TAG = "LsfgManager";

    /** Legacy per-container switch, folded into the multiplier ("Off") by {@link #getMultiplier}. */
    private static final String EXTRA_ENABLED = "lsfgEnabled";
    public static final String EXTRA_MULTIPLIER = "lsfgMultiplier";
    public static final String EXTRA_FLOW_SCALE = "lsfgFlowScale";
    public static final String EXTRA_PERFORMANCE_MODE = "lsfgPerformanceMode";
    public static final String EXTRA_PRESENT_MODE = "lsfgPresentMode";

    /** Frame generation off: the layer is not loaded at all. */
    public static final int MULTIPLIER_OFF = 0;
    public static final int DEFAULT_MULTIPLIER = MULTIPLIER_OFF;
    public static final float DEFAULT_FLOW_SCALE = 0.80f;
    private static final String PREF_FLOW_SCALE = "lsfg_flow_scale";
    private static final String PREF_PERFORMANCE_MODE = "lsfg_performance_mode";
    private static final String PREF_PRESENT_MODE = "lsfg_present_mode";
    public static final String PRESENT_MODE_MAILBOX = "mailbox";
    public static final String PRESENT_MODE_FIFO = "fifo";

    /** Bump whenever the bundled layer binary changes so existing installs are refreshed. */
    private static final String RUNTIME_VERSION = "lsfg-vk-android v1.0.4-android";

    private static final String LAYER_LIBRARY = "liblsfg-vk-layer.so";
    private static final String LAYER_MANIFEST = "VkLayer_LS_frame_generation.json";
    private static final String ASSET_MANIFEST = "lsfg_vk/" + LAYER_MANIFEST;
    /** Relative to the imagefs root. Deliberately not a vulkan/implicit_layer.d search path. */
    private static final String LAYER_DIR = "usr/share/lsfg-vk/layer";
    private static final String VERSION_FILE = ".version";

    /** Relative to the container root, which is what $HOME points to inside the guest. */
    private static final String CONFIG_FILE = ".config/lsfg-vk/conf.toml";
    private static final String VSYNC_FILE = ".config/lsfg-vk/vsync.txt";
    private static final String LEGACY_DLL_FILE = ".local/share/lsfg-vk/Lossless.dll";
    /** Under the app's files dir (not the imagefs) so it survives imagefs reinstalls. */
    private static final String GLOBAL_DLL_FILE = "lsfg-vk/Lossless.dll";

    /** Under Wine /proc/self/exe is the Wine loader, so the [[game]] profile matches this instead. */
    private static final String PROCESS_ID = "winlator-lsfg";

    private LsfgManager() {}

    // ---- Global defaults ---------------------------------------------------

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static float getDefaultFlowScale(Context context) {
        return clampFlowScale(prefs(context).getFloat(PREF_FLOW_SCALE, DEFAULT_FLOW_SCALE));
    }

    public static void setDefaultFlowScale(Context context, float flowScale) {
        prefs(context).edit().putFloat(PREF_FLOW_SCALE, clampFlowScale(flowScale)).apply();
    }

    public static boolean getDefaultPerformanceMode(Context context) {
        return prefs(context).getBoolean(PREF_PERFORMANCE_MODE, true);
    }

    public static void setDefaultPerformanceMode(Context context, boolean performanceMode) {
        prefs(context).edit().putBoolean(PREF_PERFORMANCE_MODE, performanceMode).apply();
    }

    /**
     * Mailbox by default: the layer already paces to vsync, and a FIFO queue underneath it
     * breaks the display cadence.
     */
    public static String getDefaultPresentMode(Context context) {
        return normalizePresentMode(prefs(context).getString(PREF_PRESENT_MODE, PRESENT_MODE_MAILBOX));
    }

    public static void setDefaultPresentMode(Context context, String presentMode) {
        prefs(context).edit().putString(PREF_PRESENT_MODE, normalizePresentMode(presentMode)).apply();
    }

    // ---- Container settings ------------------------------------------------

    /** 0 ({@link #MULTIPLIER_OFF}) or 2..4. There is no separate enable switch. */
    public static int getMultiplier(Container container) {
        migrateLegacySwitch(container);
        try {
            int value = Integer.parseInt(container.getExtra(EXTRA_MULTIPLIER, String.valueOf(DEFAULT_MULTIPLIER)));
            return value < 2 ? MULTIPLIER_OFF : Math.min(4, value);
        }
        catch (NumberFormatException e) {
            return DEFAULT_MULTIPLIER;
        }
    }

    public static void setMultiplier(Container container, int multiplier) {
        container.putExtra(EXTRA_ENABLED, null);
        container.putExtra(EXTRA_MULTIPLIER, String.valueOf(multiplier < 2 ? MULTIPLIER_OFF : Math.min(4, multiplier)));
        container.saveData();
    }

    /** The old per-container switch: switched off meant Off whatever the stored multiplier was. */
    private static void migrateLegacySwitch(Container container) {
        String legacy = container.getExtra(EXTRA_ENABLED, null);
        if (legacy == null) return;
        String multiplier = container.getExtra(EXTRA_MULTIPLIER, "2");
        container.putExtra(EXTRA_MULTIPLIER, parseBool(legacy) ? multiplier : String.valueOf(MULTIPLIER_OFF));
        container.putExtra(EXTRA_ENABLED, null);
        container.saveData();
    }

    /** Effective flow scale: the container's override, else the app-wide default. */
    public static float getFlowScale(Context context, Container container) {
        Float override = getFlowScaleOverride(container);
        return override != null ? override : getDefaultFlowScale(context);
    }

    /** @return the container's own flow scale, or null when it follows the default */
    public static Float getFlowScaleOverride(Container container) {
        try {
            String value = container.getExtra(EXTRA_FLOW_SCALE, null);
            return value == null ? null : clampFlowScale(Float.parseFloat(value));
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    /** @param flowScale null to follow the app-wide default */
    public static void setFlowScale(Container container, Float flowScale) {
        container.putExtra(EXTRA_FLOW_SCALE, flowScale == null ? null : formatFlowScale(flowScale));
        container.saveData();
    }

    public static boolean isPerformanceMode(Context context, Container container) {
        Boolean override = getPerformanceModeOverride(container);
        return override != null ? override : getDefaultPerformanceMode(context);
    }

    public static Boolean getPerformanceModeOverride(Container container) {
        String value = container.getExtra(EXTRA_PERFORMANCE_MODE, null);
        return value == null ? null : parseBool(value);
    }

    /** @param performanceMode null to follow the app-wide default */
    public static void setPerformanceMode(Container container, Boolean performanceMode) {
        container.putExtra(EXTRA_PERFORMANCE_MODE, performanceMode == null ? null : (performanceMode ? "1" : "0"));
        container.saveData();
    }

    public static String getPresentMode(Context context, Container container) {
        String override = getPresentModeOverride(container);
        return override != null ? override : getDefaultPresentMode(context);
    }

    public static String getPresentModeOverride(Container container) {
        String value = container.getExtra(EXTRA_PRESENT_MODE, null);
        return value == null ? null : normalizePresentMode(value);
    }

    /** @param presentMode null to follow the app-wide default */
    public static void setPresentMode(Container container, String presentMode) {
        container.putExtra(EXTRA_PRESENT_MODE, presentMode == null ? null : normalizePresentMode(presentMode));
        container.saveData();
    }

    // ---- Lossless.dll ------------------------------------------------------

    /** The single Lossless.dll shared by every container. */
    public static File getDllFile(Context context) {
        return new File(context.getFilesDir(), GLOBAL_DLL_FILE);
    }

    public static boolean hasDll(Context context) {
        File dll = getDllFile(context);
        return dll.isFile() && dll.length() > 0;
    }

    /** Frame generation runs only when the container has a multiplier and a Lossless.dll was imported. */
    public static boolean isArmed(Context context, Container container) {
        return getMultiplier(container) >= 2 && hasDll(context);
    }

    /**
     * Copies the user's Lossless.dll into the app. The DLL is proprietary to Lossless Scaling, so
     * it is never bundled with the app.
     *
     * @return null on success, otherwise a message describing why the file was rejected
     */
    public static String importDll(Context context, Uri uri) {
        File target = getDllFile(context);
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return "Unable to create " + parent;

        File tmp = new File(parent, target.getName() + ".tmp");
        if (!FileUtils.copy(context, uri, tmp)) {
            tmp.delete();
            return "Unable to read the selected file";
        }
        if (!isPortableExecutable(tmp)) {
            tmp.delete();
            return "Not a Windows DLL — select Lossless.dll from your Lossless Scaling install";
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            return "Unable to save Lossless.dll";
        }
        FileUtils.chmod(target, 0644);
        Log.i(TAG, "Imported Lossless.dll (" + target.length() + " bytes) into " + target);
        return null;
    }

    public static void removeDll(Context context) {
        File dll = getDllFile(context);
        if (dll.exists() && !dll.delete()) Log.w(TAG, "Unable to delete " + dll);
    }

    /**
     * Earlier versions kept one Lossless.dll per container. Moves the first one found into the
     * shared location (when none is imported yet) and deletes the per-container copies.
     * Blocking; call off the main thread.
     */
    public static void migrateLegacyDlls(Context context) {
        try {
            for (Container container : new ContainerManager(context).getContainers()) {
                File legacy = new File(container.getRootDir(), LEGACY_DLL_FILE);
                if (!legacy.isFile()) continue;
                if (!hasDll(context)) {
                    File target = getDllFile(context);
                    File parent = target.getParentFile();
                    if (parent != null && (parent.isDirectory() || parent.mkdirs())) {
                        FileUtils.copy(legacy, target);
                        if (target.length() == legacy.length()) {
                            FileUtils.chmod(target, 0644);
                            Log.i(TAG, "Moved Lossless.dll from " + legacy + " to " + target);
                        }
                        else target.delete();
                    }
                }
                if (hasDll(context)) legacy.delete();
            }
        }
        catch (RuntimeException e) {
            Log.w(TAG, "Unable to migrate per-container Lossless.dll files", e);
        }
    }

    private static boolean isPortableExecutable(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] header = new byte[2];
            return in.read(header) == 2 && header[0] == 'M' && header[1] == 'Z';
        }
        catch (IOException e) {
            return false;
        }
    }

    // ---- Launch ------------------------------------------------------------

    /**
     * Installs the layer, writes conf.toml and exposes the layer to the guest's Vulkan loader.
     * Does nothing when frame generation is not armed, leaving any user-set variables alone.
     *
     * @return true if the layer will be loaded
     */
    public static boolean prepareLaunch(Context context, ImageFs imageFs, Container container, EnvVars envVars) {
        if (!isArmed(context, container)) {
            if (getMultiplier(container) >= 2) Log.w(TAG, "LSFG multiplier set but no Lossless.dll imported; skipping");
            return false;
        }

        File layerDir = new File(imageFs.getRootDir(), LAYER_DIR);
        if (!ensureRuntimeInstalled(context, layerDir)) return false;
        if (!writeConfig(context, container, getMultiplier(container))) return false;

        envVars.put("LSFG_CONFIG", new File(container.getRootDir(), CONFIG_FILE).getAbsolutePath());
        envVars.put("LSFG_PROCESS", PROCESS_ID);

        String implicitPath = envVars.get("VK_ADD_IMPLICIT_LAYER_PATH");
        String layerPath = layerDir.getAbsolutePath();
        envVars.put("VK_ADD_IMPLICIT_LAYER_PATH", implicitPath.isEmpty() ? layerPath : implicitPath + ":" + layerPath);

        Log.i(TAG, String.format(Locale.US, "LSFG armed: multiplier=%d flowScale=%s performance=%b presentMode=%s",
                getMultiplier(container), formatFlowScale(getFlowScale(context, container)),
                isPerformanceMode(context, container), getPresentMode(context, container)));
        return true;
    }

    private static boolean ensureRuntimeInstalled(Context context, File layerDir) {
        File library = new File(layerDir, LAYER_LIBRARY);
        File manifest = new File(layerDir, LAYER_MANIFEST);
        File version = new File(layerDir, VERSION_FILE);

        if (library.isFile() && manifest.isFile() && version.isFile()
                && RUNTIME_VERSION.equals(FileUtils.readString(version).trim())) {
            return true;
        }

        File source = new File(context.getApplicationInfo().nativeLibraryDir, LAYER_LIBRARY);
        if (!source.isFile()) {
            Log.e(TAG, "Bundled layer not found: " + source);
            return false;
        }
        if (!layerDir.isDirectory() && !layerDir.mkdirs()) {
            Log.e(TAG, "Unable to create " + layerDir);
            return false;
        }
        // FileUtils.copy reports success even when the copy throws, so verify the size instead.
        FileUtils.copy(source, library);
        if (library.length() != source.length()) {
            Log.e(TAG, "Unable to copy " + source + " to " + library);
            library.delete();
            return false;
        }
        FileUtils.chmod(library, 0755);

        String manifestText = FileUtils.readString(context, ASSET_MANIFEST);
        if (manifestText == null || manifestText.isEmpty()) {
            Log.e(TAG, "Unable to read asset " + ASSET_MANIFEST);
            return false;
        }
        manifestText = manifestText.replaceFirst("\"library_path\"\\s*:\\s*\"[^\"]*\"",
                "\"library_path\": \"" + library.getAbsolutePath() + "\"");
        if (!FileUtils.writeString(manifest, manifestText) || !FileUtils.writeString(version, RUNTIME_VERSION)) {
            Log.e(TAG, "Unable to write the layer manifest");
            return false;
        }
        Log.i(TAG, "Installed " + RUNTIME_VERSION + " into " + layerDir);
        return true;
    }

    /** The layer rereads conf.toml when its mtime changes, so it must never see a partial write. */
    /** @param multiplier written verbatim; 1 keeps the layer resident but passes frames through. */
    private static boolean writeConfig(Context context, Container container, int multiplier) {
        StringBuilder toml = new StringBuilder();
        toml.append("version = 1\n\n");
        toml.append("[global]\n");
        toml.append("dll = ").append(tomlString(getDllFile(context).getAbsolutePath())).append('\n');
        toml.append("no_fp16 = false\n\n");
        toml.append("[[game]]\n");
        toml.append("exe = ").append(tomlString(PROCESS_ID)).append('\n');
        toml.append("multiplier = ").append(Math.max(1, Math.min(4, multiplier))).append('\n');
        toml.append("flow_scale = ").append(formatFlowScale(getFlowScale(context, container))).append('\n');
        toml.append("performance_mode = ").append(isPerformanceMode(context, container)).append('\n');
        toml.append("hdr_mode = false\n");
        toml.append("fps_limit = 0\n");
        toml.append("experimental_present_mode = ").append(tomlString(getPresentMode(context, container))).append('\n');

        return writeAtomic(new File(container.getRootDir(), CONFIG_FILE), toml.toString());
    }

    /** In-game "off": layer stays loaded, frames pass through unmodified. */
    public static final int RUNTIME_OFF_MULTIPLIER = 1;

    private static final ExecutorService runtimeWriter = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "lsfg-runtime");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Rewrites conf.toml for the running container so the layer reloads live. Multiplier and flow
     * scale changes force a swapchain recreation inside the layer; the file write happens off the
     * UI thread. Persists 2x-4x as the container's multiplier, but not the transient
     * {@link #RUNTIME_OFF_MULTIPLIER}, so a relaunch restores the last real multiplier. The flow
     * scale is stored as the container's own override.
     */
    public static void applyRuntimeConfig(Context context, Container container, int multiplier, float flowScale) {
        final int m = Math.max(RUNTIME_OFF_MULTIPLIER, Math.min(4, multiplier));
        setFlowScale(container, flowScale);
        if (m >= 2) setMultiplier(container, m);
        final Context appContext = context.getApplicationContext();
        runtimeWriter.execute(() -> writeConfig(appContext, container, m));
    }

    // ---- Vsync clock -------------------------------------------------------

    private static volatile Handler vsyncHandler;
    private static final ExecutorService vsyncWriter = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "lsfg-vsync");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Publishes the display's vsync timestamp and period to vsync.txt once a second so the
     * layer can phase-lock its pacing to the display instead of free-running against it.
     * Choreographer timestamps are CLOCK_MONOTONIC, the clock the layer paces with.
     */
    public static void startVsyncClock(Context context, Container container) {
        stopVsyncClock();
        if (!isArmed(context, container)) return;

        final File file = new File(container.getRootDir(), VSYNC_FILE);
        final WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        final Handler handler = new Handler(Looper.getMainLooper());
        vsyncHandler = handler;

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (vsyncHandler != handler) return;
                Choreographer.getInstance().postFrameCallback(frameTimeNanos -> {
                    if (vsyncHandler != handler) return;
                    float refreshRate = 60f;
                    try {
                        float rate = windowManager.getDefaultDisplay().getRefreshRate();
                        if (rate > 1f) refreshRate = rate;
                    }
                    catch (RuntimeException ignored) {}
                    final long periodNs = (long) (1_000_000_000.0 / refreshRate);
                    vsyncWriter.execute(() -> writeAtomic(file, "vsync_ns=" + frameTimeNanos + "\nperiod_ns=" + periodNs + "\n"));
                });
                handler.postDelayed(this, 1000);
            }
        });
    }

    public static void stopVsyncClock() {
        Handler handler = vsyncHandler;
        vsyncHandler = null;
        if (handler != null) handler.removeCallbacksAndMessages(null);
    }

    // ---- Helpers -----------------------------------------------------------

    private static boolean writeAtomic(File file, String text) {
        File parent = file.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return false;
        File tmp = new File(parent, file.getName() + ".tmp");
        if (!FileUtils.writeString(tmp, text)) return false;
        FileUtils.chmod(tmp, 0644);
        if (!tmp.renameTo(file)) {
            tmp.delete();
            return false;
        }
        return true;
    }

    private static String tomlString(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static float clampFlowScale(float value) {
        return Math.max(0.25f, Math.min(1.0f, value));
    }

    private static String formatFlowScale(float value) {
        return String.format(Locale.US, "%.2f", clampFlowScale(value));
    }

    private static String normalizePresentMode(String value) {
        return PRESENT_MODE_FIFO.equals(value) ? PRESENT_MODE_FIFO : PRESENT_MODE_MAILBOX;
    }

    private static boolean parseBool(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }
}
