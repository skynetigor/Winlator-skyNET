package com.winlator.cmod.linux;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Builds the proot command line and environment that run a program inside a Linux runtime:
 * a glibc aarch64 rootfs, entered with the runtime's own proot, whose windows are drawn by the
 * app's X server.
 */
public final class LinuxSession {
    public static final class Launch {
        public final String[] argv;
        public final String[] hostEnv;
        public final File workingDir;

        Launch(String[] argv, String[] hostEnv, File workingDir) {
            this.argv = argv;
            this.hostEnv = hostEnv;
            this.workingDir = workingDir;
        }
    }

    private static final String TAG = "LinuxSession";
    /** Container extra: "sw" (default) draws Vulkan frames with a CPU copy, "native" leaves the driver's own presentation. */
    public static final String EXTRA_VULKAN_PRESENT = "linuxVulkanPresent";
    /** Container extra: "software" (default, llvmpipe) or "zink" (OpenGL on the Turnip Vulkan driver). */
    public static final String EXTRA_GL_DRIVER = "linuxGlDriver";
    private static final String HOST_DIR = "opt/android-host";
    private static final String PRELOAD = "/usr/local/lib/libblsession.so";
    private static final String FAKE_PROC_DIR = "etc/bannerlator/proc";
    private static final String EMPTY_DIR = "etc/bannerlator/empty";
    private static final String[] FAKE_PROC_FILES = {
            "stat", "version", "loadavg", "uptime", "vmstat", "bus/pci/devices"
    };

    private LinuxSession() {}

    /** A program started from a shortcut: scripts through bash, anything else executed from its own directory. */
    public static String[] programCommand(String path) {
        return new String[]{
                "/bin/bash", "-c",
                "chmod +x \"$1\" 2>/dev/null; cd \"$(dirname \"$1\")\" && case \"$1\" in *.sh) exec bash \"$1\";; *) exec \"$1\";; esac",
                "bannerlator-run", path
        };
    }

    public static Launch build(Context context, ImageFs imageFs, Container container,
                               LinuxRuntime.Installed runtime, String[] guestCommand) {
        File rt = LinuxRuntime.rootDir(context, runtime.id);
        File hostDir = new File(rt, HOST_DIR);
        File proot = new File(hostDir, "proot");
        File loader = new File(hostDir, "loader");
        FileUtils.chmod(proot, 0755);
        FileUtils.chmod(loader, 0755);

        int uid = Process.myUid();
        File cacheDir = context.getCacheDir();
        File shmDir = new File(cacheDir, "shm");
        File home = container.getLinuxHomeDir();
        File imageFsRoot = imageFs.getRootDir();

        FileUtils.delete(shmDir);
        shmDir.mkdirs();
        home.mkdirs();
        new File(rt, "tmp/.X11-unix").mkdirs();
        writeAccounts(rt, uid);
        writePreload(rt);

        List<String> argv = new ArrayList<>();
        argv.add(proot.getPath());
        argv.add("--kill-on-exit");
        argv.add("-i");
        argv.add(uid + ":" + uid);
        argv.add("-r");
        argv.add(rt.getPath());
        argv.add("-w");
        argv.add("/root");

        bind(argv, "/dev");
        bind(argv, "/proc");
        bind(argv, "/sys");
        bind(argv, "/dev/urandom", "/dev/random");
        bind(argv, "/proc/self/fd", "/dev/fd");
        for (int fd = 0; fd < 3; fd++) {
            bind(argv, "/proc/self/fd/" + fd, "/dev/" + new String[]{"stdin", "stdout", "stderr"}[fd]);
        }
        bind(argv, shmDir.getPath(), "/dev/shm");
        bindIfExists(argv, new File(rt, EMPTY_DIR), "/sys/fs/selinux");
        for (String name : FAKE_PROC_FILES) {
            File fake = new File(rt, FAKE_PROC_DIR + "/" + name);
            if (fake.exists() && !new File("/proc/" + name).canRead()) bind(argv, fake.getPath(), "/proc/" + name);
        }

        // The app's own directories at their own paths: the audio socket lives under the image fs.
        bind(argv, context.getFilesDir().getPath());
        bind(argv, cacheDir.getPath());
        bind(argv, imageFsRoot.getPath());
        bind(argv, "/storage/emulated/0");
        bind(argv, home.getPath(), "/root");
        bind(argv, new File(imageFsRoot, "usr/tmp/.X11-unix").getPath(), "/tmp/.X11-unix");

        argv.add("/usr/bin/env");
        argv.add("-i");
        argv.addAll(guestEnv(context, rt, imageFsRoot, container));
        for (String part : guestCommand) argv.add(part);

        EnvVars host = new EnvVars();
        host.put("PROOT_LOADER", loader.getPath());
        host.put("PROOT_TMP_DIR", cacheDir.getPath());
        // Android's linker does not search an executable's own directory, and proot needs libtalloc from there.
        host.put("LD_LIBRARY_PATH", hostDir.getPath());

        return new Launch(argv.toArray(new String[0]), host.toStringArray(), rt);
    }

    private static List<String> guestEnv(Context context, File rt, File imageFsRoot, Container container) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOME", "/root");
        env.put("USER", "root");
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        env.put("TERM", "xterm-256color");
        env.put("LANG", "C.UTF-8");
        env.put("TZ", TimeZone.getDefault().getID());
        env.put("DISPLAY", ":0");
        env.put("XDG_RUNTIME_DIR", "/tmp");
        // Toolkits and SDL talk to the app's X server.
        env.put("GDK_BACKEND", "x11");
        env.put("QT_QPA_PLATFORM", "xcb");
        env.put("SDL_VIDEODRIVER", "x11");
        // MIT-SHM needs the app's bionic SysV shim; a glibc client sends images through the socket instead.
        env.put("QT_X11_NO_MITSHM", "1");
        env.put("_X11_NO_MITSHM", "1");
        if ("zink".equals(container.getExtra(EXTRA_GL_DRIVER))) {
            env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            env.put("GALLIUM_DRIVER", "zink");
        }
        else {
            env.put("LIBGL_ALWAYS_SOFTWARE", "1");
            env.put("GALLIUM_DRIVER", "llvmpipe");
        }
        env.put("PULSE_SERVER", "unix:" + new File(imageFsRoot, UnixSocketConfig.PULSE_SERVER_PATH).getPath());

        // Turnip: an imported Linux driver, or the one the runtime ships.
        String driverId = container.getExtra(LinuxDriverManager.EXTRA_DRIVER);
        if (!driverId.isEmpty() && LinuxDriverManager.isInstalled(context, driverId)) {
            String icd = LinuxDriverManager.icdFile(context, driverId).getPath();
            env.put("VK_DRIVER_FILES", icd);
            env.put("VK_ICD_FILENAMES", icd);
        }
        else {
            if (!driverId.isEmpty()) Log.w(TAG, "Linux driver " + driverId + " is not installed; using the runtime's");
            if (new File(rt, "usr/share/vulkan/icd.d/freedreno_icd.json").exists()) {
                env.put("VK_ICD_FILENAMES", "/usr/share/vulkan/icd.d/freedreno_icd.json");
            }
        }

        // The X server cannot take dma-bufs yet: with this on, Vulkan copies finished frames on the CPU.
        if (!"native".equals(container.getExtra(EXTRA_VULKAN_PRESENT))) env.put("MESA_VK_WSI_DEBUG", "sw");

        env.putAll(userEnv(container));

        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : env.entrySet()) result.add(entry.getKey() + "=" + entry.getValue());
        return result;
    }

    /** The container's own variables ("A=1 B=2"). The untouched Wine default means "none", as for older Linux containers. */
    private static Map<String, String> userEnv(Container container) {
        Map<String, String> result = new LinkedHashMap<>();
        String value = container.getEnvVars();
        if (value == null || value.trim().isEmpty() || value.equals(Container.DEFAULT_ENV_VARS)) return result;
        for (String token : value.trim().split("\\s+")) {
            int index = token.indexOf('=');
            if (index > 0) result.put(token.substring(0, index), token.substring(index + 1));
        }
        return result;
    }

    /** X access control and libc look the user up by uid, which is the app's, so "root" gets that uid. */
    private static void writeAccounts(File rt, int uid) {
        FileUtils.writeString(new File(rt, "etc/passwd"),
                "root:x:" + uid + ":" + uid + ":root:/root:/bin/bash\n"
                        + "nobody:x:65534:65534:nobody:/:/usr/bin/nologin\n");
        FileUtils.writeString(new File(rt, "etc/group"),
                "root:x:" + uid + ":\nnobody:x:65534:\n");
    }

    /** The runtime's shim library (SysV IPC and other things Android withholds) is loaded into every process. */
    private static void writePreload(File rt) {
        File preload = new File(rt, "etc/ld.so.preload");
        if (new File(rt, PRELOAD.substring(1)).exists()) FileUtils.writeString(preload, PRELOAD + "\n");
        else preload.delete();
    }

    private static void bind(List<String> argv, String path) {
        bind(argv, path, path);
    }

    private static void bind(List<String> argv, String host, String guest) {
        argv.add("-b");
        argv.add(host.equals(guest) ? host : host + ":" + guest);
    }

    private static void bindIfExists(List<String> argv, File host, String guest) {
        if (host.exists()) bind(argv, host.getPath(), guest);
    }
}
