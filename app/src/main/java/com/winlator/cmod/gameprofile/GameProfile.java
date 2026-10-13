package com.winlator.cmod.gameprofile;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What the registry knows about one game (games/&lt;id&gt;.json of the components repository): how to start it and the defaults of
 * a shortcut for it, for the game as a whole and for ranges of its versions. The file format is described in the
 * repository's README ("Game profiles").
 *
 * A version entry holds, beside its id, label and detectors, only the settings that differ from the game's {@code defaults}
 * (executable, noSteam, args, env, resolution, extras); {@link #effective} merges the two.
 */
public final class GameProfile {
    private static final String TAG = "GameProfile";
    public static final String STATUS_WORKS = "works";
    public static final String STATUS_UNTESTED = "untested";
    public static final String STATUS_BROKEN = "broken";

    /**
     * The shortcut extras a profile may set. The same list is in the repository's scripts/games/validate.py; a key that is
     * not here is dropped when a profile is read, so a registry file can never reach the shortcut's path or launch options.
     */
    public static final Set<String> ALLOWED_EXTRAS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "graphicsFilterMode", "graphicsSharpness", "graphicsPostFXMode", "graphicsColorMode",
            "fullscreenStretched", "simTouchScreen", "lsfgMultiplier", "lsfgFlowScale", "steamMinimal")));

    /** Whether a game is started without Steam by default, and the argument that makes it so ("-nosteam"). */
    public static final class NoSteam {
        public final boolean enabled;
        public final String arg;

        NoSteam(boolean enabled, String arg) {
            this.enabled = enabled;
            this.arg = arg;
        }
    }

    /** The launch settings of a game or of one of its versions; null / empty where nothing is said. */
    public static final class Settings {
        /** Relative to the game's folder. */
        public String executable;
        public NoSteam noSteam;
        public List<String> args;
        /** A null value removes the key of the settings it is merged over. */
        public final Map<String, String> env = new LinkedHashMap<>();
        /** "native" or "WxH". */
        public String resolution;
        public final Map<String, String> extras = new LinkedHashMap<>();

        Settings copy() {
            Settings copy = new Settings();
            copy.executable = executable;
            copy.noSteam = noSteam;
            copy.args = args == null ? null : new ArrayList<>(args);
            copy.env.putAll(env);
            copy.resolution = resolution;
            copy.extras.putAll(extras);
            return copy;
        }
    }

    public static final class FileCheck {
        public final String path;
        /** -1 when the size is not checked. */
        public final long size;
        /** Lower-case hex, or null. */
        public final String sha256;

        FileCheck(String path, long size, String sha256) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    /** A version number found with a regular expression in the first or the last bytes of a small file. */
    public static final class VersionFile {
        public final String path;
        /** How many bytes to read from the start (head) or the end (tail); one of the two is 0. */
        public final int head;
        public final int tail;
        public final Pattern regex;
        public final String from;
        public final String until;

        VersionFile(String path, int head, int tail, Pattern regex, String from, String until) {
            this.path = path;
            this.head = head;
            this.tail = tail;
            this.regex = regex;
            this.from = from;
            this.until = until;
        }
    }

    /** How a version is recognised; it matches when any of the three detectors does. */
    public static final class Match {
        /** Steam build id range, from inclusive, until exclusive; null for no bound. */
        public final Long buildFrom;
        public final Long buildUntil;
        public final boolean hasBuildRange;
        /** Every listed file must be there with the size and hash given. */
        public final List<FileCheck> files;
        public final VersionFile versionFile;

        Match(Long buildFrom, Long buildUntil, boolean hasBuildRange, List<FileCheck> files, VersionFile versionFile) {
            this.buildFrom = buildFrom;
            this.buildUntil = buildUntil;
            this.hasBuildRange = hasBuildRange;
            this.files = files;
            this.versionFile = versionFile;
        }
    }

    public static final class Version {
        public final String id;
        public final String label;
        public final String status;
        public final String notes;
        public final String testedOn;
        /** The version used when none is recognised. */
        public final boolean isDefault;
        /** Null when the version is only chosen by hand (or as the default). */
        public final Match match;
        public final Settings settings;

        Version(String id, String label, String status, String notes, String testedOn, boolean isDefault, Match match, Settings settings) {
            this.id = id;
            this.label = label;
            this.status = status;
            this.notes = notes;
            this.testedOn = testedOn;
            this.isDefault = isDefault;
            this.match = match;
            this.settings = settings;
        }
    }

    public final String id;
    public final String name;
    /** The Steam app id, or "" for a game that is not on Steam. */
    public final String steamAppId;
    public final String steamInstallDir;
    public final Settings defaults;
    public final List<Version> versions;

    private GameProfile(String id, String name, String steamAppId, String steamInstallDir, Settings defaults, List<Version> versions) {
        this.id = id;
        this.name = name;
        this.steamAppId = steamAppId;
        this.steamInstallDir = steamInstallDir;
        this.defaults = defaults;
        this.versions = Collections.unmodifiableList(versions);
    }

    public Version version(String versionId) {
        if (versionId == null) return null;
        for (Version version : versions) if (version.id.equals(versionId)) return version;
        return null;
    }

    public Version defaultVersion() {
        for (Version version : versions) if (version.isDefault) return version;
        return null;
    }

    /** The game's defaults with the version's settings laid over them (the version may be null). */
    public Settings effective(Version version) {
        Settings merged = defaults.copy();
        if (version == null) return merged;
        Settings over = version.settings;
        if (over.executable != null) merged.executable = over.executable;
        if (over.noSteam != null) merged.noSteam = over.noSteam;
        if (over.args != null) merged.args = new ArrayList<>(over.args);
        if (over.resolution != null) merged.resolution = over.resolution;
        for (Map.Entry<String, String> entry : over.env.entrySet()) {
            if (entry.getValue() == null) merged.env.remove(entry.getKey());
            else merged.env.put(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : over.extras.entrySet()) {
            if (entry.getValue() == null) merged.extras.remove(entry.getKey());
            else merged.extras.put(entry.getKey(), entry.getValue());
        }
        return merged;
    }

    /** Reads a game file; null when it is not one (the reason is logged). Entries that cannot be read are dropped, not fatal. */
    public static GameProfile parse(String text) {
        try {
            JSONObject root = new JSONObject(text);
            String id = root.getString("id");
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) return null;
            JSONObject steam = root.optJSONObject("steam");
            long steamId = steam == null ? 0 : steam.optLong("appId", 0);
            String appId = steamId == 0 ? "" : String.valueOf(steamId);
            String installDir = steam == null ? "" : steam.optString("installDir", "");
            Settings defaults = settings(root.optJSONObject("defaults"));
            List<Version> versions = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            JSONArray array = root.optJSONArray("versions");
            for (int i = 0; array != null && i < array.length(); i++) {
                Version version = version(array.optJSONObject(i));
                if (version == null || !seen.add(version.id)) continue;
                versions.add(version);
            }
            return new GameProfile(id, root.optString("name", id), appId, installDir, defaults, versions);
        }
        catch (JSONException e) {
            Log.w(TAG, "Not a game profile", e);
            return null;
        }
    }

    private static Version version(JSONObject object) {
        if (object == null) return null;
        String id = object.optString("id", "");
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) return null;
        String status = object.optString("status", STATUS_UNTESTED);
        if (!status.equals(STATUS_WORKS) && !status.equals(STATUS_BROKEN)) status = STATUS_UNTESTED;
        return new Version(id, object.optString("label", id), status, object.optString("notes", ""), object.optString("testedOn", ""),
                object.optBoolean("default", false), match(object.optJSONObject("match")), settings(object));
    }

    private static Match match(JSONObject object) {
        if (object == null) return null;
        Long from = null, until = null;
        boolean hasBuild = false;
        JSONObject build = object.optJSONObject("steamBuildId");
        if (build != null) {
            hasBuild = true;
            if (build.has("from")) from = build.optLong("from");
            if (build.has("until")) until = build.optLong("until");
        }
        List<FileCheck> files = new ArrayList<>();
        JSONArray array = object.optJSONArray("files");
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject file = array.optJSONObject(i);
            if (file == null || !relative(file.optString("path", ""))) continue;
            long size = file.has("size") ? file.optLong("size", -1) : -1;
            String sha = file.optString("sha256", "").toLowerCase();
            if (!sha.matches("[0-9a-f]{64}")) sha = null;
            if (size < 0 && sha == null) continue;
            files.add(new FileCheck(file.optString("path"), size, sha));
        }
        VersionFile versionFile = null;
        JSONObject vf = object.optJSONObject("versionFile");
        if (vf != null && relative(vf.optString("path", ""))) {
            int head = Math.max(0, Math.min(65536, vf.optInt("head", 0)));
            int tail = Math.max(0, Math.min(65536, vf.optInt("tail", 0)));
            if ((head > 0) != (tail > 0)) {
                try {
                    versionFile = new VersionFile(vf.optString("path"), head, tail, Pattern.compile(vf.optString("regex", "")),
                            vf.optString("from", ""), vf.optString("until", ""));
                }
                catch (PatternSyntaxException e) {
                    Log.w(TAG, "Bad version regex in " + vf.optString("path"), e);
                }
            }
        }
        if (!hasBuild && files.isEmpty() && versionFile == null) return null;
        return new Match(from, until, hasBuild, files, versionFile);
    }

    private static Settings settings(JSONObject object) {
        Settings settings = new Settings();
        if (object == null) return settings;
        String executable = object.optString("executable", "");
        if (relative(executable)) settings.executable = executable;
        JSONObject noSteam = object.optJSONObject("noSteam");
        if (noSteam != null) {
            String arg = noSteam.optString("arg", "");
            if (!arg.isEmpty()) settings.noSteam = new NoSteam(noSteam.optBoolean("default", false), arg);
        }
        JSONArray args = object.optJSONArray("args");
        if (args != null) {
            settings.args = new ArrayList<>();
            for (int i = 0; i < args.length(); i++) settings.args.add(args.optString(i));
        }
        String resolution = object.optString("resolution", "");
        if (resolution.equals("native") || resolution.matches("\\d{3,5}x\\d{3,5}")) settings.resolution = resolution;
        readMap(object.optJSONObject("env"), settings.env, null);
        readMap(object.optJSONObject("extras"), settings.extras, ALLOWED_EXTRAS);
        return settings;
    }

    private static void readMap(JSONObject object, Map<String, String> into, Set<String> allowed) {
        if (object == null) return;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (allowed != null && !allowed.contains(key)) {
                Log.w(TAG, "Not an allowed setting of a game profile: " + key);
                continue;
            }
            if (allowed == null && !key.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
            into.put(key, object.isNull(key) ? null : object.optString(key));
        }
    }

    /** A path in the game's folder: relative, going nowhere else. */
    static boolean relative(String path) {
        return path != null && !path.isEmpty() && !path.startsWith("/") && !path.contains("..") && !path.contains("\\");
    }
}
