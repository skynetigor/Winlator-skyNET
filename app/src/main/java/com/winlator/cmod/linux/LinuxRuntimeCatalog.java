package com.winlator.cmod.linux;

import android.util.Log;

import com.winlator.cmod.contents.Downloader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The Linux runtimes that can be installed. */
public final class LinuxRuntimeCatalog {
    private static final String TAG = "LinuxRuntimeCatalog";
    static final String BANNERLATOR_NAME = "Bannerlator (gamescope + ARM Steam)";
    private static final String CATALOG_URL =
            "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/linuxfs.json";

    public static final class Entry {
        public final String id;
        public final String name;
        public final String version;
        public final String url;
        public final String sha256;
        public final long size;
        public final String channel;
        /** One of the {@link LinuxPackages} kinds. */
        public final String kind;
        /** For emulators: "box64" or "fex"; null otherwise. */
        public final String emulator;

        Entry(String id, String name, String version, String url, String sha256, long size, String channel,
              String kind, String emulator) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
            this.channel = channel;
            this.kind = kind;
            this.emulator = emulator;
        }
    }

    private LinuxRuntimeCatalog() {}

    /** Blocking. Returns null when the catalog cannot be read. */
    public static List<Entry> fetch() {
        String json = Downloader.downloadString(CATALOG_URL);
        if (json == null) return null;
        try {
            JSONObject data = new JSONObject(json);
            List<Entry> entries = new ArrayList<>();
            JSONArray runtimes = data.optJSONArray("runtimes");
            if (runtimes != null) {
                for (int i = 0; i < runtimes.length(); i++) addEntry(entries, runtimes.getJSONObject(i), null);
            }
            else {
                // Bannerlator publishes a single release object.
                addEntry(entries, data, "bannerlator");
            }
            return entries;
        }
        catch (Exception e) {
            Log.e(TAG, "Bad runtime catalog", e);
            return null;
        }
    }

    private static void addEntry(List<Entry> entries, JSONObject item, String idPrefix) throws Exception {
        String url = item.getString("url");
        if (!url.startsWith("https://")) return;
        String version = item.getString("version");
        String id = idPrefix != null ? idPrefix + "-" + version : item.getString("id");
        String name = idPrefix != null ? BANNERLATOR_NAME + " " + version : item.optString("name", id);
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) return;
        entries.add(new Entry(id, name, version, url, item.getString("sha256"), item.optLong("size", 0),
                item.optString("channel", "prerelease"), LinuxPackages.KIND_RUNTIME, null));
    }

    private static final String SKYNET_CATALOG_URL =
            "https://raw.githubusercontent.com/skynetigor/winlator-skynet-components/main/linux.json";

    /** Blocking. The x86 emulators that can be installed; null when the catalog cannot be read. */
    public static List<Entry> fetchEmulators() {
        String json = Downloader.downloadString(SKYNET_CATALOG_URL);
        if (json == null) return null;
        try {
            List<Entry> entries = new ArrayList<>();
            JSONArray emulators = new JSONObject(json).optJSONArray("emulators");
            if (emulators == null) return entries;
            for (int i = 0; i < emulators.length(); i++) {
                JSONObject item = emulators.getJSONObject(i);
                String url = item.getString("url");
                String id = item.getString("id");
                String emulator = item.getString("emulator");
                if (!url.startsWith("https://") || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) continue;
                if (!emulator.equals("box64") && !emulator.equals("fex")) continue;
                String version = item.getString("version");
                entries.add(new Entry(id, item.optString("name", emulator) + " " + version, version, url,
                        item.getString("sha256"), item.optLong("size", 0), item.optString("channel", "prerelease"),
                        LinuxPackages.KIND_EMULATOR, emulator));
            }
            return entries;
        }
        catch (Exception e) {
            Log.e(TAG, "Bad Linux catalog", e);
            return null;
        }
    }
}
