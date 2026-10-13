package com.winlator.cmod.gameprofile;

import android.util.Log;

import com.winlator.cmod.core.Versions;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;

/**
 * Tells which version of a game a folder holds, with the detectors the profile lists for each version: Steam's build id, the
 * size or hash of files, and a version number read out of a small file. They look only at files, so a game that did not come
 * from Steam is recognised as well as one that did.
 */
public final class GameVersionMatcher {
    private static final String TAG = "GameVersionMatcher";

    /** A recognised version and how it was recognised ("steam build 24617469", "files", "version file 0.39.4.0", "default"). */
    public static final class Match {
        public final GameProfile.Version version;
        public final String how;

        Match(GameProfile.Version version, String how) {
            this.version = version;
            this.how = how;
        }
    }

    private GameVersionMatcher() {}

    /**
     * The first version, in the profile's order, that one of its detectors recognises; the profile's default version when
     * none is; null when the profile has no default either. {@code gameDir} may be null (nothing to look at) and
     * {@code steamBuildId} is -1 when the game is not known to Steam.
     */
    public static Match detect(GameProfile profile, File gameDir, long steamBuildId) {
        for (GameProfile.Version version : profile.versions) {
            if (version.match == null) continue;
            String how = matches(version.match, gameDir, steamBuildId);
            if (how != null) return new Match(version, how);
        }
        GameProfile.Version fallback = profile.defaultVersion();
        return fallback == null ? null : new Match(fallback, "default");
    }

    /** What recognised the version, or null when no detector of it does. */
    private static String matches(GameProfile.Match match, File gameDir, long steamBuildId) {
        if (match.hasBuildRange && steamBuildId >= 0) {
            boolean above = match.buildFrom == null || steamBuildId >= match.buildFrom;
            boolean below = match.buildUntil == null || steamBuildId < match.buildUntil;
            if (above && below) return "steam build " + steamBuildId;
        }
        if (gameDir == null || !gameDir.isDirectory()) return null;
        if (!match.files.isEmpty() && filesMatch(match, gameDir)) return "files";
        if (match.versionFile != null) {
            String found = versionIn(match.versionFile, gameDir);
            if (found != null && Versions.inRange(found, match.versionFile.from, match.versionFile.until)) return "version " + found;
        }
        return null;
    }

    private static boolean filesMatch(GameProfile.Match match, File gameDir) {
        for (GameProfile.FileCheck check : match.files) {
            File file = new File(gameDir, check.path);
            if (!file.isFile()) return false;
            if (check.size >= 0 && file.length() != check.size) return false;
            if (check.sha256 != null && !check.sha256.equals(sha256(file))) return false;
        }
        return true;
    }

    /** The version number the file holds, or null. The file is read from its start or its end, a few kilobytes at most. */
    public static String versionIn(GameProfile.VersionFile spec, File gameDir) {
        File file = new File(gameDir, spec.path);
        if (!file.isFile()) return null;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long length = in.length();
            int count = (int) Math.min(length, spec.tail > 0 ? spec.tail : spec.head);
            byte[] bytes = new byte[count];
            in.seek(spec.tail > 0 ? length - count : 0);
            in.readFully(bytes);
            Matcher matcher = spec.regex.matcher(new String(bytes, StandardCharsets.ISO_8859_1));
            // The last match of the tail is the one nearest the end, the first of the head the one nearest the start.
            String found = null;
            while (matcher.find()) {
                if (matcher.groupCount() < 1) return null;
                found = matcher.group(1);
                if (spec.head > 0) break;
            }
            return found;
        }
        catch (IOException | RuntimeException e) {
            Log.w(TAG, "Cannot read the version out of " + file, e);
            return null;
        }
    }

    private static String sha256(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        }
        catch (IOException | java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }
}
