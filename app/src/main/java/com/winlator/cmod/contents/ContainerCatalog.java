package com.winlator.cmod.contents;

import android.content.Context;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Ready-made container configs listed in the remote contents.json as entries of type "Container". */
public final class ContainerCatalog {
    private ContainerCatalog() {}

    private static final String TYPE_NAME = "Container";

    public static final class Entry {
        public final String id;
        public final String name;
        public final String gameId;
        public final String gameName;
        public final String gameVersion;
        public final String description;
        public final List<String> tags;
        /** "stable", "prerelease" or null. */
        public final String channel;
        /** Direct link to the .wcfg profile. */
        public final String url;
        /** Full names of the processors the config is known to work on, e.g. "Snapdragon 8 Elite Gen 5". May be empty. */
        public final List<String> compatibility;
        /** Full names of the devices it was tested on, e.g. "Samsung Galaxy Z Fold8 Ultra". May be empty. */
        public final List<String> testedOn;

        Entry(String id, String name, String gameId, String gameName, String gameVersion,
              String description, List<String> tags, String url, String channel,
              List<String> compatibility, List<String> testedOn) {
            this.compatibility = compatibility;
            this.testedOn = testedOn;
            this.id = id;
            this.name = name;
            this.gameId = gameId;
            this.gameName = gameName;
            this.gameVersion = gameVersion;
            this.description = description;
            this.tags = tags;
            this.url = url;
            this.channel = channel;
        }
    }

    /** A registry field that holds one name or a list of names. */
    private static List<String> strings(Object value) {
        ArrayList<String> out = new ArrayList<>();
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String item = array.optString(i, "").trim();
                if (!item.isEmpty()) out.add(item);
            }
        } else if (value instanceof String && !((String) value).trim().isEmpty()) {
            out.add(((String) value).trim());
        }
        return out;
    }

    /**
     * A processor's short name: "Snapdragon 8 Elite Gen 5" is "s8eg5", "Snapdragon 8 Gen 3" "s8g3". A name that is not a
     * Snapdragon's is returned as it is.
     */
    public static String shortProcessorName(String full) {
        String lower = full.trim().toLowerCase(java.util.Locale.ROOT).replace("qualcomm", "");
        if (!lower.contains("snapdragon")) return full.trim();
        String compact = lower.replace("snapdragon", "s").replaceAll("\\belite\\b", "e").replaceAll("\\bgen\\b", "g");
        return compact.replaceAll("[^a-z0-9]", "");
    }

    /**
     * Downloads the registry and returns its container configs in registry order. Blocking; call off
     * the main thread.
     *
     * @return null when the registry could not be fetched or parsed (an empty list means none are published)
     */
    public static List<Entry> load(Context context) {
        String registryUrl = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES);
        try (Response response = new OkHttpClient().newCall(new Request.Builder().url(registryUrl).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            JSONArray entries = new JSONArray(response.body().string());
            ArrayList<Entry> result = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject object = entries.optJSONObject(i);
                if (object == null || !TYPE_NAME.equalsIgnoreCase(object.optString("type"))) continue;
                String url = object.optString("remoteUrl", "");
                String gameName = object.optString("gameName", "").trim();
                String gameId = object.optString("gameId", "").trim();
                if (gameId.isEmpty()) gameId = gameName;
                if (gameName.isEmpty()) gameName = gameId;
                String gameVersion = object.optString("gameVersion", "").trim();
                String name = object.optString("name", "").trim();
                if (url.isEmpty() || gameId.isEmpty() || gameVersion.isEmpty() || name.isEmpty()) continue;

                ArrayList<String> tags = new ArrayList<>();
                JSONArray tagArray = object.optJSONArray("tags");
                if (tagArray != null) {
                    for (int t = 0; t < tagArray.length(); t++) {
                        String tag = tagArray.optString(t, "").trim();
                        if (!tag.isEmpty()) tags.add(tag);
                    }
                }
                result.add(new Entry(object.optString("id", url), name, gameId, gameName, gameVersion,
                        object.optString("description", "").trim(), tags, url,
                        ContentsManager.normalizeChannel(object.optString("channel", "")),
                        strings(object.opt("compatibility")), strings(object.opt("testedOn"))));
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }
}
