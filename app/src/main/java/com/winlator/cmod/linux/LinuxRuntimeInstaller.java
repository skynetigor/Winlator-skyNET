package com.winlator.cmod.linux;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.core.FileUtils;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/** Downloads the Linux runtime tarball, checks it and swaps it into files/linuxfs. */
public final class LinuxRuntimeInstaller {
    private static final String TAG = "LinuxRuntimeInstaller";
    private static final String CATALOG_URL =
            "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/linuxfs.json";

    public enum Phase { DOWNLOAD, VERIFY, EXTRACT }

    public interface Progress {
        void onProgress(Phase phase, int percent);
    }

    public static final class Release {
        public final String version;
        public final String url;
        public final String sha256;
        public final long size;

        Release(String version, String url, String sha256, long size) {
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    private static volatile boolean installing;

    private LinuxRuntimeInstaller() {}

    public static Release fetchRelease() {
        String json = Downloader.downloadString(CATALOG_URL);
        if (json == null) return null;
        try {
            JSONObject data = new JSONObject(json);
            String url = data.getString("url");
            if (!url.startsWith("https://")) return null;
            return new Release(data.getString("version"), url, data.getString("sha256"),
                    data.optLong("size", 0));
        }
        catch (Exception e) {
            Log.e(TAG, "Bad runtime catalog", e);
            return null;
        }
    }

    /** Finishes or undoes a swap that was cut short. Safe to call at any time. */
    public static void recoverInterruptedSwap(Context context) {
        if (installing) return;
        File root = LinuxRuntime.rootDir(context);
        File old = new File(root.getPath() + ".old");
        if (!root.exists() && old.exists()) old.renameTo(root);
    }

    /** Blocking. Returns null on success or a short message describing what went wrong. */
    public static synchronized String install(Context context, Release release, Progress progress) {
        recoverInterruptedSwap(context);

        File filesDir = context.getFilesDir();
        if (release.size > 0 && filesDir.getUsableSpace() < release.size * 4) {
            return "Not enough free space: about " + (release.size * 4 >> 20) + " MB needed";
        }

        File archive = new File(context.getCacheDir(), "linuxfs.tar.zst");
        File staging = new File(filesDir, "linuxfs.new");
        File root = LinuxRuntime.rootDir(context);
        File old = new File(root.getPath() + ".old");

        installing = true;
        try {
            progress.onProgress(Phase.DOWNLOAD, 0);
            if (!Downloader.downloadFile(release.url, archive, p -> progress.onProgress(Phase.DOWNLOAD, p))) {
                return "Download failed";
            }

            progress.onProgress(Phase.VERIFY, 0);
            if (!release.sha256.equalsIgnoreCase(sha256(archive))) return "Checksum mismatch";

            FileUtils.delete(staging);
            if (!staging.mkdirs()) return "Cannot create " + staging;
            extract(archive, staging, progress);
            FileUtils.writeString(new File(staging, ".version"), release.version);

            FileUtils.delete(old);
            if (root.exists() && !root.renameTo(old)) return "Cannot replace the installed runtime";
            if (!staging.renameTo(root)) {
                old.renameTo(root);
                return "Cannot move the new runtime into place";
            }
            FileUtils.delete(old);
            return null;
        }
        catch (Exception e) {
            Log.e(TAG, "Install failed", e);
            return "Install failed: " + e.getMessage();
        }
        finally {
            installing = false;
            archive.delete();
            if (staging.exists()) FileUtils.delete(staging);
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /** A counter so extraction progress follows the compressed bytes read. */
    private static final class CountingStream extends InputStream {
        private final InputStream in;
        long count;

        CountingStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) count += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /**
     * Unlike the Wine image extractor this keeps hard links (a binary otherwise ends up empty)
     * and the exec bits, and refuses entries that would land outside the destination.
     */
    private static void extract(File archive, File destination, Progress progress) throws Exception {
        long total = Math.max(1, archive.length());
        String destPath = destination.getCanonicalPath() + File.separator;
        int lastPercent = -1;

        try (CountingStream counter = new CountingStream(new BufferedInputStream(new FileInputStream(archive), 1 << 16));
             TarArchiveInputStream tar = new TarArchiveInputStream(new ZstdCompressorInputStream(counter))) {
            TarArchiveEntry entry;
            byte[] buffer = new byte[1 << 16];
            while ((entry = tar.getNextTarEntry()) != null) {
                File raw = new File(destination, entry.getName());
                File parent = raw.getParentFile();
                if (parent == null) continue;
                File file = new File(parent.getCanonicalPath(), raw.getName());
                String filePath = file.getPath();
                if (filePath.equals(destPath.substring(0, destPath.length() - 1))) continue;
                if (!filePath.startsWith(destPath)) {
                    throw new IOException("Unsafe path in archive: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    file.mkdirs();
                    FileUtils.chmod(file, 0755);
                }
                else {
                    file.getParentFile().mkdirs();
                    if (entry.isSymbolicLink()) {
                        file.delete();
                        Os.symlink(entry.getLinkName(), file.getPath());
                    }
                    else if (entry.isLink()) {
                        File target = new File(destination, entry.getLinkName());
                        file.delete();
                        try {
                            Os.link(target.getPath(), file.getPath());
                        }
                        catch (Exception e) {
                            FileUtils.copy(target, file);
                        }
                    }
                    else {
                        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16)) {
                            int count;
                            while ((count = tar.read(buffer)) != -1) out.write(buffer, 0, count);
                        }
                        int mode = entry.getMode() & 0777;
                        FileUtils.chmod(file, (mode & 0111) != 0 ? 0755 : 0644);
                    }
                }

                int percent = (int)Math.min(100, counter.count * 100 / total);
                if (percent != lastPercent) {
                    lastPercent = percent;
                    progress.onProgress(Phase.EXTRACT, percent);
                }
            }
        }
    }
}
