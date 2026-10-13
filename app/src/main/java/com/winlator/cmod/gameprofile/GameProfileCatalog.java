package com.winlator.cmod.gameprofile;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.contents.RegistryCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The games the registry has profiles for: games.json is a short index, and a game's own file (games/&lt;id&gt;.json) is only
 * fetched when something needs it, so the index stays small however many versions the games grow.
 *
 * Every method that may use the network ({@code allowNetwork}) blocks: call it off the main thread. The launch path
 * never allows the network and works from the cache or the copy bundled in the app.
 */
public final class GameProfileCatalog {
    private static final String TAG = "GameProfileCatalog";
    public static final String INDEX = "games.json";

    /** One line of the index. */
    public static final class IndexEntry {
        public final String id;
        public final String name;
        /** "" when the game is not on Steam. */
        public final String steamAppId;
        /** Path of the game's file, relative to the index. */
        public final String config;

        IndexEntry(String id, String name, String steamAppId, String config) {
            this.id = id;
            this.name = name;
            this.steamAppId = steamAppId;
            this.config = config;
        }
    }

    private GameProfileCatalog() {}

    public static List<IndexEntry> index(Context context, boolean allowNetwork) {
        return index(context, allowNetwork, RegistryCache.TTL_INDEX);
    }

    public static List<IndexEntry> index(Context context, boolean allowNetwork, long ttlMs) {
        List<IndexEntry> entries = new ArrayList<>();
        String text = RegistryCache.get(context, INDEX, ttlMs, allowNetwork);
        if (text == null) return entries;
        try {
            JSONArray games = new JSONObject(text).optJSONArray("games");
            for (int i = 0; games != null && i < games.length(); i++) {
                JSONObject game = games.optJSONObject(i);
                if (game == null) continue;
                String id = game.optString("id", "");
                String config = game.optString("config", "");
                // Only games/<id>.json: nothing the index says can name another file.
                if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || !config.equals("games/" + id + ".json")) continue;
                long appId = game.optLong("steamAppId", 0);
                entries.add(new IndexEntry(id, game.optString("name", id), appId == 0 ? "" : String.valueOf(appId), config));
            }
        }
        catch (org.json.JSONException e) {
            Log.w(TAG, "Bad game index", e);
        }
        return entries;
    }

    public static IndexEntry entry(Context context, String id, boolean allowNetwork) {
        for (IndexEntry entry : index(context, allowNetwork)) if (entry.id.equals(id)) return entry;
        return null;
    }

    /** The index entry of a Steam game, from what is on hand (never the network), or null. */
    public static IndexEntry bySteamAppId(Context context, String appId) {
        if (appId == null || appId.isEmpty()) return null;
        for (IndexEntry entry : index(context, false)) if (entry.steamAppId.equals(appId)) return entry;
        return null;
    }

    /** A game's profile; null when the registry has none by this id or it cannot be read. */
    public static GameProfile game(Context context, String id, boolean allowNetwork) {
        return game(context, id, allowNetwork, RegistryCache.TTL_GAME);
    }

    public static GameProfile game(Context context, String id, boolean allowNetwork, long ttlMs) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) return null;
        String text = RegistryCache.get(context, "games/" + id + ".json", ttlMs, allowNetwork);
        if (text == null) return null;
        GameProfile profile = GameProfile.parse(text);
        return profile != null && profile.id.equals(id) ? profile : null;
    }
}
