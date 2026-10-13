package com.winlator.cmod.gameprofile;

import android.content.Context;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.linux.LinuxSession;
import com.winlator.cmod.linux.LinuxSteamGames;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a shortcut gets from the registry: the profile it belongs to, the version of the game in its folder and the settings
 * that follow, with the shortcut's own choices laid over them. It reads only what is on hand (the cache or the copy bundled
 * in the app), never the network, so it is safe to call when a game starts.
 *
 * A shortcut keeps four extras: {@link #EXTRA_PROFILE} (the game's id, or {@link #NONE} when the user took the profile off),
 * {@link #EXTRA_VERSION} (a version the user chose; empty means "recognise it"), {@link #EXTRA_ROOT} (the game's folder as
 * the guest sees it, for a game that is not installed by Steam) and {@link #EXTRA_NO_STEAM} (empty: the profile's choice,
 * "1" on, "0" off).
 */
public final class GameProfiles {
    public static final String EXTRA_PROFILE = "gameProfile";
    public static final String EXTRA_VERSION = "gameProfileVersion";
    public static final String EXTRA_ROOT = "gameRoot";
    public static final String EXTRA_NO_STEAM = "noSteam";
    public static final String NONE = "none";
    public static final String DEFAULT_NO_STEAM_ARG = "-nosteam";
    private static final String STEAM_COMMON_GUEST = "/root/.local/share/Steam/steamapps/common/";

    /** The settings that apply to one shortcut. */
    public static final class Effective {
        public final GameProfile profile;
        /** Null when no version was recognised and the profile names no default. */
        public final GameProfile.Version version;
        /** How the version was found: "chosen", "default" or what a detector saw; empty when there is none. */
        public final String how;
        /** The game's folder as the guest and the host see it; null when it is not known. */
        public final String rootGuest;
        public final File rootHost;
        /** The executable as the guest sees it; null when the profile names none or the folder is not known. */
        public final String executableGuest;
        /** True when the profile has a "-nosteam" rule at all (the shortcut editor shows the switch only then). */
        public final boolean hasNoSteam;
        /** The effective choice: started without Steam. */
        public final boolean noSteam;
        public final String noSteamArg;
        public final List<String> args;
        public final Map<String, String> env;
        /** "native", "WxH" or empty. */
        public final String resolution;
        public final Map<String, String> extras;

        Effective(GameProfile profile, GameProfile.Version version, String how, String rootGuest, File rootHost, GameProfile.Settings settings,
                  boolean noSteam) {
            this.profile = profile;
            this.version = version;
            this.how = how;
            this.rootGuest = rootGuest;
            this.rootHost = rootHost;
            this.executableGuest = rootGuest != null && settings.executable != null ? rootGuest + "/" + settings.executable : null;
            this.hasNoSteam = settings.noSteam != null;
            this.noSteam = noSteam;
            this.noSteamArg = settings.noSteam != null ? settings.noSteam.arg : DEFAULT_NO_STEAM_ARG;
            this.args = settings.args == null ? new ArrayList<>() : new ArrayList<>(settings.args);
            this.env = new LinkedHashMap<>(settings.env);
            this.resolution = settings.resolution == null ? "" : settings.resolution;
            this.extras = new LinkedHashMap<>(settings.extras);
        }

        /** A Steam game's shortcut starts the game's own executable instead of going through the client. */
        public boolean runsWithoutSteam() {
            return noSteam && executableGuest != null;
        }

        /** The arguments to give the program: the profile's, then the "no Steam" one when it is on. */
        public List<String> programArgs() {
            List<String> all = new ArrayList<>(args);
            if (noSteam && !noSteamArg.isEmpty()) all.add(noSteamArg);
            return all;
        }
    }

    private GameProfiles() {}

    /** The id of the profile a shortcut uses: its own extra, or the registry's profile for the Steam app; "" for none. */
    public static String profileId(Context context, Shortcut shortcut) {
        String own = shortcut.getExtra(EXTRA_PROFILE);
        if (own.equals(NONE)) return "";
        if (!own.isEmpty()) return own;
        if (!shortcut.isSteamGame()) return "";
        GameProfileCatalog.IndexEntry entry = GameProfileCatalog.bySteamAppId(context, LinuxSession.steamAppId(shortcut.path));
        return entry == null ? "" : entry.id;
    }

    /** The settings of a shortcut, or null when it has no profile (or the registry has none for it on hand). */
    public static Effective resolve(Context context, Container container, Shortcut shortcut) {
        if (container == null || !container.isLinux()) return null;
        String id = profileId(context, shortcut);
        if (id.isEmpty()) return null;
        GameProfile profile = GameProfileCatalog.game(context, id, false);
        if (profile == null) return null;
        return resolve(context, container, shortcut, profile);
    }

    public static Effective resolve(Context context, Container container, Shortcut shortcut, GameProfile profile) {
        return resolve(context, container, shortcut, profile, shortcut.getExtra(EXTRA_VERSION), shortcut.getExtra(EXTRA_NO_STEAM),
                shortcut.getExtra(EXTRA_ROOT));
    }

    /** As above, with the version, the "without Steam" choice and the game's folder given (the shortcut editor shows what they would do). */
    public static Effective resolve(Context context, Container container, Shortcut shortcut, GameProfile profile,
                                    String chosenVersion, String noSteamExtra, String rootExtra) {
        String rootGuest = null;
        File rootHost = null;
        long buildId = -1;
        if (shortcut.isSteamGame()) {
            LinuxSteamGames.Game game = LinuxSteamGames.game(container, LinuxSession.steamAppId(shortcut.path));
            String dir = game != null && !game.installDir.isEmpty() ? game.installDir : profile.steamInstallDir;
            if (game != null) buildId = game.buildId;
            if (!dir.isEmpty() && !dir.contains("/") && !dir.contains("..")) {
                rootGuest = STEAM_COMMON_GUEST + dir;
                rootHost = new File(LinuxSteamGames.steamRoot(container), "steamapps/common/" + dir);
            }
        }
        else {
            String root = rootExtra == null ? "" : rootExtra.trim();
            if (root.startsWith("/")) {
                rootGuest = root.endsWith("/") && root.length() > 1 ? root.substring(0, root.length() - 1) : root;
                rootHost = LinuxSession.hostFile(context, container, rootGuest);
            }
        }

        GameProfile.Version version = null;
        String how = "";
        String chosen = chosenVersion == null ? "" : chosenVersion;
        if (!chosen.isEmpty()) {
            version = profile.version(chosen);
            if (version != null) how = "chosen";
        }
        if (version == null) {
            GameVersionMatcher.Match match = GameVersionMatcher.detect(profile, rootHost, buildId);
            if (match != null) {
                version = match.version;
                how = match.how;
            }
        }

        GameProfile.Settings settings = profile.effective(version);
        if (noSteamExtra == null) noSteamExtra = "";
        boolean noSteam = noSteamExtra.equals("1")
                || (noSteamExtra.isEmpty() && settings.noSteam != null && settings.noSteam.enabled);
        return new Effective(profile, version, how, rootGuest, rootHost, settings, noSteam);
    }

    /**
     * The size of the X screen a Linux shortcut starts with. A session that runs the Steam client (the client's shortcut, a
     * game started through it) is always native: the game picks its own mode through RandR or the user sets it in Steam.
     * Otherwise the shortcut's own size, then the profile's, then the container's.
     */
    public static String screenSize(Context context, Container container, Shortcut shortcut, Effective effective) {
        if (LinuxSession.isSteam(shortcut.path)) return "native";
        if (shortcut.isSteamGame() && (effective == null || !effective.runsWithoutSteam())) return "native";
        String own = shortcut.getExtra("screenSize");
        if (!own.isEmpty()) return own;
        if (effective != null && !effective.resolution.isEmpty()) return effective.resolution;
        return container.getScreenSize();
    }

    /** A setting of the shortcut: its own value, or else the profile's, or else "". */
    public static String extra(Shortcut shortcut, Effective effective, String key) {
        String own = shortcut.getExtra(key);
        if (!own.isEmpty()) return own;
        if (effective != null) {
            String value = effective.extras.get(key);
            if (value != null) return value;
        }
        return "";
    }
}
