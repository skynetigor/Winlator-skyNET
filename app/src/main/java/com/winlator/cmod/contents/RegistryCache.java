package com.winlator.cmod.contents;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.BuildConfig;
import com.winlator.cmod.core.FileUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The JSON files of the components repository (games.json, games/&lt;id&gt;.json, linux.json), each downloaded once per
 * time-to-live instead of by every screen that needs it.
 *
 * One lookup goes: the copy in memory, a fresh copy on disk, the network, an old copy on disk, the copy bundled in the app
 * (assets/registry). A download is kept only when it parses as JSON, and is written through a temporary file so a
 * dropped connection never leaves half a file. The disk copies are per branch of the components repository (debug builds
 * read the branch that carries the app's name), so switching branches never serves the other one's files.
 */
public final class RegistryCache {
    private static final String TAG = "RegistryCache";
    private static final String DIR = "registry-cache";
    private static final String ASSET_DIR = "registry/";

    public static final long TTL_INDEX = 6L * 60 * 60 * 1000;
    public static final long TTL_GAME = 24L * 60 * 60 * 1000;
    /** Always ask the network first (a "refresh" button). */
    public static final long TTL_NONE = 0;

    public enum Source { MEMORY, NETWORK, CACHE, STALE_CACHE, BUNDLED, NONE }

    /** What a lookup found, and where from. */
    public static final class Result {
        /** The JSON text, or null when nothing was available anywhere. */
        public final String json;
        public final Source source;
        /** When the copy was downloaded (milliseconds); 0 for the bundled copy and for no copy. */
        public final long time;

        Result(String json, Source source, long time) {
            this.json = json;
            this.source = source;
            this.time = time;
        }
    }

    private static final Object LOCK = new Object();
    private static final Map<String, Result> MEMORY = new HashMap<>();
    private static final Map<String, Result> LAST = new HashMap<>();

    private RegistryCache() {}

    /** The text of {@code relPath} (for example "games.json"), or null. Never blocks on the network when {@code allowNetwork} is false. */
    public static String get(Context context, String relPath, long ttlMs, boolean allowNetwork) {
        return load(context, relPath, ttlMs, allowNetwork).json;
    }

    public static Result load(Context context, String relPath, long ttlMs, boolean allowNetwork) {
        if (!validPath(relPath)) {
            Log.w(TAG, "Not a registry path: " + relPath);
            return new Result(null, Source.NONE, 0);
        }
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            Result memory = MEMORY.get(relPath);
            if (memory != null && ttlMs > 0 && now - memory.time < ttlMs) return remember(relPath, new Result(memory.json, Source.MEMORY, memory.time), false);

            File file = file(context, relPath);
            String disk = file.isFile() ? FileUtils.readString(file) : null;
            long diskTime = file.isFile() ? file.lastModified() : 0;
            if (disk != null && isJson(disk) && ttlMs > 0 && now - diskTime < ttlMs) {
                return remember(relPath, new Result(disk, Source.CACHE, diskTime), true);
            }

            if (allowNetwork) {
                String text = Downloader.downloadString(ContentsManager.COMPONENTS_BASE + relPath);
                if (text != null && isJson(text)) {
                    store(file, text);
                    return remember(relPath, new Result(text, Source.NETWORK, now), true);
                }
                Log.w(TAG, "Cannot download " + relPath + "; using what is on hand");
            }

            // An old or bundled copy is an answer for now, not for the next TTL: the next lookup tries the network again.
            if (disk != null && isJson(disk)) return remember(relPath, new Result(disk, Source.STALE_CACHE, diskTime), false);
            String bundled = bundled(context, relPath);
            if (bundled != null) return remember(relPath, new Result(bundled, Source.BUNDLED, 0), false);
            return remember(relPath, new Result(null, Source.NONE, 0), false);
        }
    }

    /** What the last lookup of this file found (for a status line), or null when it was never looked up. */
    public static Result last(String relPath) {
        synchronized (LOCK) {
            return LAST.get(relPath);
        }
    }

    /** Forgets every downloaded copy, so the next lookups download again or fall back to the bundled ones. */
    public static void clear(Context context) {
        synchronized (LOCK) {
            MEMORY.clear();
            LAST.clear();
            File dir = new File(context.getFilesDir(), DIR);
            if (dir.exists()) FileUtils.delete(dir);
        }
    }

    public static String branch() {
        return BuildConfig.COMPONENTS_BRANCH;
    }

    private static Result remember(String relPath, Result result, boolean keep) {
        if (keep && result.json != null) MEMORY.put(relPath, new Result(result.json, Source.MEMORY, result.time));
        LAST.put(relPath, result);
        return result;
    }

    private static boolean validPath(String relPath) {
        return relPath != null && relPath.matches("[A-Za-z0-9][A-Za-z0-9._/-]*") && !relPath.contains("..") && !relPath.endsWith("/");
    }

    private static File file(Context context, String relPath) {
        return new File(new File(new File(context.getFilesDir(), DIR), safe(BuildConfig.COMPONENTS_BRANCH)), relPath);
    }

    private static String safe(String branch) {
        return branch.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static boolean isJson(String text) {
        try {
            new JSONObject(text);
            return true;
        }
        catch (org.json.JSONException e) {
            return false;
        }
    }

    private static void store(File file, String text) {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
        File temp = new File(dir, file.getName() + ".tmp");
        if (!FileUtils.writeString(temp, text) || !temp.renameTo(file)) {
            temp.delete();
            Log.w(TAG, "Cannot keep " + file.getName());
        }
    }

    private static String bundled(Context context, String relPath) {
        try (InputStream in = context.getAssets().open(ASSET_DIR + relPath)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (java.io.IOException e) {
            return null;
        }
    }
}
