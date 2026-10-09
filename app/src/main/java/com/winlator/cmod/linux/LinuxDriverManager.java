package com.winlator.cmod.linux;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.FileUtils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Vulkan drivers (Turnip) built for the Linux runtime: a glibc aarch64 libvulkan_freedreno.so plus an ICD
 * manifest. The Adrenotools drivers used by Wine containers are Android (bionic) builds and cannot be used here.
 */
public final class LinuxDriverManager {
    private static final String TAG = "LinuxDriverManager";
    private static final long MAX_LIBRARY_BYTES = 200L << 20;
    private static final String LIBRARY = "libvulkan_freedreno.so";
    private static final String ICD = "icd.json";
    private static final String META = "meta.json";
    public static final String EXTRA_DRIVER = "linuxVulkanDriver";

    public static final class Installed {
        public final String id;
        public final String name;
        public final String version;

        Installed(String id, String name, String version) {
            this.id = id;
            this.name = name;
            this.version = version;
        }

        public String label() {
            return version.isEmpty() ? name : name + " " + version;
        }
    }

    public static final class Result {
        /** The installed driver, or null with {@link #error} set. */
        public final Installed driver;
        public final String error;

        Result(Installed driver, String error) {
            this.driver = driver;
            this.error = error;
        }
    }

    private LinuxDriverManager() {}

    public static File driversDir(Context context) {
        return new File(context.getFilesDir(), "linux-drivers");
    }

    public static File icdFile(Context context, String id) {
        return new File(new File(driversDir(context), id), ICD);
    }

    /** True when the driver's library and manifest are both present. */
    public static boolean isInstalled(Context context, String id) {
        if (id == null || id.isEmpty()) return false;
        File dir = new File(driversDir(context), id);
        return new File(dir, LIBRARY).isFile() && new File(dir, ICD).isFile();
    }

    public static List<Installed> list(Context context) {
        List<Installed> result = new ArrayList<>();
        File[] dirs = driversDir(context).listFiles();
        if (dirs == null) return result;
        for (File dir : dirs) {
            if (!dir.isDirectory() || !isInstalled(context, dir.getName())) continue;
            result.add(readInfo(dir));
        }
        result.sort(Comparator.comparing((Installed d) -> d.name.toLowerCase()));
        return result;
    }

    public static Installed find(Context context, String id) {
        for (Installed driver : list(context)) if (driver.id.equals(id)) return driver;
        return null;
    }

    public static boolean remove(Context context, String id) {
        if (id == null || id.isEmpty() || id.startsWith(".") || id.contains("/")) return false;
        return FileUtils.delete(new File(driversDir(context), id));
    }

    /** Name of a Linux container that uses this driver, or null. */
    public static String containerUsing(Context context, String id) {
        for (Container container : new ContainerManager(context).getContainers()) {
            if (container.isLinux() && id.equals(container.getExtra(EXTRA_DRIVER))) return container.getName();
        }
        return null;
    }

    /** Imports a driver zip. Blocking; call off the main thread. */
    public static Result install(Context context, Uri uri, String zipName) {
        byte[] library = null;
        String metaName = null;
        String metaVersion = "";
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) return new Result(null, "Cannot open the file");
            try (ZipInputStream zip = new ZipInputStream(raw)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String base = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                    if (library == null && base.startsWith("libvulkan_freedreno") && base.endsWith(".so")) {
                        library = readLimited(zip, MAX_LIBRARY_BYTES);
                        if (library == null) return new Result(null, "The driver library is too large");
                    }
                    else if (base.equals(META)) {
                        try {
                            JSONObject meta = new JSONObject(new String(readLimited(zip, 1 << 20), StandardCharsets.UTF_8));
                            metaName = meta.optString("name", null);
                            metaVersion = meta.optString("driverVersion", "");
                        }
                        catch (Exception ignored) {}
                    }
                }
            }
        }
        catch (IOException e) {
            Log.e(TAG, "Cannot read the driver zip", e);
            return new Result(null, "Cannot read the zip: " + e.getMessage());
        }

        if (library == null) return new Result(null, "No libvulkan_freedreno*.so found in the zip");
        String problem = checkLibrary(library);
        if (problem != null) return new Result(null, problem);

        String baseName = zipName == null ? "linux-driver" : zipName.replaceAll("(?i)\\.zip$", "");
        String name = metaName != null && !metaName.isEmpty() ? metaName : baseName;
        String id = uniqueId(context, baseName);
        File dir = new File(driversDir(context), id);
        try {
            if (!dir.mkdirs()) return new Result(null, "Cannot create " + dir);
            File libraryFile = new File(dir, LIBRARY);
            try (FileOutputStream out = new FileOutputStream(libraryFile)) {
                out.write(library);
            }
            FileUtils.chmod(libraryFile, 0755);

            JSONObject icdInner = new JSONObject();
            icdInner.put("library_path", libraryFile.getPath());
            icdInner.put("api_version", "1.3.0");
            JSONObject icd = new JSONObject();
            icd.put("file_format_version", "1.0.0");
            icd.put("ICD", icdInner);
            FileUtils.writeString(new File(dir, ICD), icd.toString());

            JSONObject meta = new JSONObject();
            meta.put("name", name);
            meta.put("driverVersion", metaVersion);
            meta.put("importedAt", System.currentTimeMillis());
            FileUtils.writeString(new File(dir, META), meta.toString());
        }
        catch (Exception e) {
            Log.e(TAG, "Cannot store the driver", e);
            FileUtils.delete(dir);
            return new Result(null, "Cannot store the driver: " + e.getMessage());
        }
        return new Result(new Installed(id, name, metaVersion), null);
    }

    /** A usable Linux Vulkan driver is a 64-bit little-endian AArch64 ELF linked against glibc. */
    private static String checkLibrary(byte[] elf) {
        boolean isElf = elf.length > 64 && elf[0] == 0x7f && elf[1] == 'E' && elf[2] == 'L' && elf[3] == 'F';
        if (!isElf) return "The library is not an ELF file";
        if (elf[4] != 2 || elf[5] != 1) return "The library is not a 64-bit little-endian build";
        int machine = (elf[18] & 0xff) | ((elf[19] & 0xff) << 8);
        if (machine != 183) return "The library is not built for AArch64";
        if (indexOf(elf, "libc.so.6".getBytes(StandardCharsets.US_ASCII)) < 0) {
            return "This looks like an Android (bionic) driver. A Linux driver links against libc.so.6.";
        }
        return null;
    }

    private static int indexOf(byte[] data, byte[] needle) {
        outer:
        for (int i = 0; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (data[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1 << 16];
        long total = 0;
        int count;
        while ((count = in.read(buffer)) != -1) {
            total += count;
            if (total > limit) return null;
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static String uniqueId(Context context, String baseName) {
        String id = baseName.toLowerCase().replaceAll("[^a-z0-9._-]+", "-").replaceAll("^[-.]+", "");
        if (id.isEmpty()) id = "linux-driver";
        String candidate = id;
        for (int n = 2; new File(driversDir(context), candidate).exists(); n++) candidate = id + "-" + n;
        return candidate;
    }

    private static Installed readInfo(File dir) {
        try {
            JSONObject meta = new JSONObject(FileUtils.readString(new File(dir, META)));
            return new Installed(dir.getName(), meta.optString("name", dir.getName()), meta.optString("driverVersion", ""));
        }
        catch (Exception e) {
            return new Installed(dir.getName(), dir.getName(), "");
        }
    }
}
