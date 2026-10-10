package com.winlator.cmod.linux;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import com.winlator.cmod.core.FileUtils;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Downloads a Linux runtime tarball (resuming a partial download), checks it and swaps it into
 * files/linux-runtimes/&lt;id&gt;. Blocking; run it off the main thread.
 */
public final class LinuxRuntimeInstaller {
    private static final String TAG = "LinuxRuntimeInstaller";
    public static final String CANCELLED = "Cancelled";

    public enum Phase { DOWNLOAD, VERIFY, EXTRACT }

    public interface Progress {
        void onProgress(Phase phase, int percent);
    }

    private static final class CancelledException extends IOException {
        CancelledException() {
            super(CANCELLED);
        }
    }

    private static volatile boolean cancelRequested;
    private static volatile boolean installing;

    private LinuxRuntimeInstaller() {}

    /** Disk space an install needs: the tarball, the unpacked tree and the swap copy. */
    public static long requiredBytes(LinuxRuntimeCatalog.Entry entry) {
        return entry.size * 4;
    }

    public static void cancel() {
        cancelRequested = true;
    }

    private static File downloadDir(Context context) {
        return new File(LinuxPackages.baseDir(context, LinuxPackages.KIND_RUNTIME), ".download");
    }

    /** True when a partial download for this runtime is waiting to be resumed. */
    public static boolean hasPartial(Context context, LinuxRuntimeCatalog.Entry entry) {
        return partFile(context, entry).length() > 0;
    }

    private static File partFile(Context context, LinuxRuntimeCatalog.Entry entry) {
        return new File(downloadDir(context), entry.id + ".tar.zst.part");
    }

    /** Finishes or undoes swaps that were cut short. Safe to call at any time. */
    public static void recoverInterruptedSwaps(Context context) {
        if (installing) return;
        for (String kind : new String[]{LinuxPackages.KIND_RUNTIME, LinuxPackages.KIND_EMULATOR, LinuxPackages.KIND_ROOTFS}) {
            File[] files = LinuxPackages.baseDir(context, kind).listFiles();
            if (files == null) continue;
            for (File file : files) {
                String name = file.getName();
                if (!name.endsWith(".old")) continue;
                File root = new File(file.getParentFile(), name.substring(0, name.length() - 4));
                if (!root.exists()) file.renameTo(root);
            }
        }
    }

    /** Returns null on success or a short message; {@link #CANCELLED} if the user cancelled. */
    public static synchronized String install(Context context, LinuxRuntimeCatalog.Entry entry, Progress progress) {
        cancelRequested = false;
        installing = true;
        File runtimes = LinuxPackages.baseDir(context, entry.kind);
        File root = LinuxPackages.rootDir(context, entry.kind, entry.id);
        File staging = new File(runtimes, entry.id + ".new");
        File old = new File(runtimes, entry.id + ".old");
        File part = partFile(context, entry);
        try {
            runtimes.mkdirs();
            downloadDir(context).mkdirs();

            long needed = requiredBytes(entry) - part.length();
            if (entry.size > 0 && context.getFilesDir().getUsableSpace() < needed) {
                return "Not enough free space: about " + (needed >> 20) + " MB needed";
            }

            if (!download(entry, part, progress)) return "Download failed";

            progress.onProgress(Phase.VERIFY, 0);
            if (!entry.sha256.equalsIgnoreCase(sha256(part, progress))) {
                part.delete();
                return "Checksum mismatch";
            }

            FileUtils.delete(staging);
            if (!staging.mkdirs()) return "Cannot create " + staging;
            extract(part, staging, progress);
            // An emulator package is one folder (box64-0.4.4-linux-aarch64/bin/...); its contents are the install.
            if (LinuxPackages.KIND_EMULATOR.equals(entry.kind)) flattenSingleFolder(staging);
            LinuxRuntime.writeInfo(staging, entry.id, entry.name, entry.version, entry.emulator);

            FileUtils.delete(old);
            if (root.exists() && !root.renameTo(old)) return "Cannot replace the installed runtime";
            if (!staging.renameTo(root)) {
                old.renameTo(root);
                return "Cannot move the new runtime into place";
            }
            FileUtils.delete(old);
            part.delete();
            return null;
        }
        catch (CancelledException e) {
            return CANCELLED;
        }
        catch (Exception e) {
            Log.e(TAG, "Install failed", e);
            return "Install failed: " + e.getMessage();
        }
        finally {
            installing = false;
            if (staging.exists()) FileUtils.delete(staging);
        }
    }

    /** Downloads to {@code part}, continuing from its current length. The partial file survives failures. */
    private static boolean download(LinuxRuntimeCatalog.Entry entry, File part, Progress progress) throws IOException {
        long existing = part.length();
        HttpURLConnection connection = open(entry.url, existing);
        try {
            int code = connection.getResponseCode();
            if (code == 416) {
                // The server has nothing past our offset: either the file is whole, or the partial is bogus.
                if (entry.size > 0 && existing == entry.size) {
                    progress.onProgress(Phase.DOWNLOAD, 100);
                    return true;
                }
                part.delete();
                connection.disconnect();
                existing = 0;
                connection = open(entry.url, 0);
                code = connection.getResponseCode();
            }
            boolean append = code == 206 && existing > 0;
            if (code != 200 && code != 206) {
                Log.e(TAG, "Download answered HTTP " + code);
                return false;
            }
            if (!append) existing = 0;

            long total = entry.size > 0 ? entry.size : connection.getContentLengthLong() + existing;
            long done = existing;
            int lastPercent = -1;
            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(part, append)) {
                byte[] buffer = new byte[1 << 16];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (cancelRequested) throw new CancelledException();
                    out.write(buffer, 0, count);
                    done += count;
                    int percent = total > 0 ? (int)Math.min(100, done * 100 / total) : 0;
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        progress.onProgress(Phase.DOWNLOAD, percent);
                    }
                }
            }
            if (entry.size > 0 && done < entry.size) {
                Log.e(TAG, "Download ended early at " + done + " of " + entry.size);
                return false;
            }
            return true;
        }
        catch (CancelledException e) {
            throw e;
        }
        catch (IOException e) {
            Log.e(TAG, "Download interrupted", e);
            return false;
        }
        finally {
            connection.disconnect();
        }
    }

    /** Opens the URL, following redirects by hand so the Range header survives them (GitHub redirects). */
    private static HttpURLConnection open(String address, long offset) throws IOException {
        String current = address;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection connection = (HttpURLConnection)new URL(current).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Redirect without a location");
                current = new URL(new URL(current), location).toString();
                if (!current.startsWith("https://")) throw new IOException("Redirect to a non-HTTPS address");
                continue;
            }
            return connection;
        }
        throw new IOException("Too many redirects");
    }

    /** If the directory holds exactly one folder, moves that folder's contents up into it. */
    private static void flattenSingleFolder(File dir) throws IOException {
        File[] children = dir.listFiles();
        if (children == null || children.length != 1 || !children[0].isDirectory()) return;
        File top = children[0];
        File[] inner = top.listFiles();
        if (inner != null) {
            for (File file : inner) {
                if (!file.renameTo(new File(dir, file.getName()))) throw new IOException("Cannot move " + file);
            }
        }
        top.delete();
    }

    private static String sha256(File file, Progress progress) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = Math.max(1, file.length());
        long done = 0;
        int lastPercent = -1;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (cancelRequested) throw new CancelledException();
                digest.update(buffer, 0, count);
                done += count;
                int percent = (int)(done * 100 / total);
                if (percent != lastPercent) {
                    lastPercent = percent;
                    progress.onProgress(Phase.VERIFY, percent);
                }
            }
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
                if (cancelRequested) throw new CancelledException();

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
