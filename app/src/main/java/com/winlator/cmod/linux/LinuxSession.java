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
    /** "1" when the Steam client is started with the container. */
    public static final String EXTRA_STEAM = "linuxSteam";
    private static final String HOST_DIR = "opt/android-host";
    private static final String PRELOAD = "/usr/local/lib/libblsession.so";
    private static final String FAKE_PROC_DIR = "etc/bannerlator/proc";
    private static final String EMPTY_DIR = "etc/bannerlator/empty";
    private static final String[] FAKE_PROC_FILES = {
            "stat", "version", "loadavg", "uptime", "vmstat", "bus/pci/devices"
    };

    private LinuxSession() {}

    /**
     * The X display number the guest uses. libxcb tries the abstract socket "@/tmp/.X11-unix/X<n>" before the file, and
     * abstract sockets are shared by every app on the device: another app's X server on display 0 (Bannerlator, for
     * one) answers first and refuses us, and libxcb does not fall back. A number of our own avoids that clash.
     */
    public static int displayNumber() {
        return 100 + Process.myUid() % 800;
    }

    /** The ELF header's machine type of a file: 62 is x86-64, 3 is 32-bit x86, 183 is AArch64; -1 if not an ELF file. */
    public static int elfMachine(File file) {
        if (file == null || !file.isFile()) return -1;
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            byte[] header = new byte[20];
            if (in.read(header) < 20) return -1;
            if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F') return -1;
            return (header[18] & 0xff) | ((header[19] & 0xff) << 8);
        }
        catch (java.io.IOException e) {
            return -1;
        }
    }

    /** Where a program, given as the guest sees its path, lives on the host (null if it cannot be told). */
    private static File hostFile(Context context, Container container, String guestPath) {
        if (guestPath.startsWith("/root/")) return new File(container.getLinuxHomeDir(), guestPath.substring(6));
        if (guestPath.startsWith("/storage/") || guestPath.startsWith("/data/")) return new File(guestPath);
        LinuxRuntime.Installed runtime = LinuxRuntime.resolve(context, container);
        return runtime == null ? null : new File(LinuxRuntime.rootDir(context, runtime.id), guestPath.substring(1));
    }

    /** A command that only prints why the program cannot start, so the failure dialog explains it. */
    private static String[] refusal(String message) {
        return new String[]{"/bin/sh", "-c", "echo \"$1\" >&2; exit 127", "bannerlator-run", message};
    }

    /**
     * A program started from a shortcut. Scripts run through bash and ARM64 programs run natively; an x86-64 program
     * is started through the container's emulator, or refused with a message when there is none to use.
     */
    public static String[] programCommand(Context context, Container container, String path) {
        return programCommand(context, container, path, null);
    }

    /** As above, with the shortcut's launch options (see {@link LinuxLaunchOptions}): its variables, wrapper and arguments. */
    public static String[] programCommand(Context context, Container container, String path, String launchOptions) {
        if (isSteam(path)) return steamCommand();
        LinuxLaunchOptions options = LinuxLaunchOptions.parse(launchOptions);
        String emulatorPath = "";
        int machine = elfMachine(hostFile(context, container, path));
        if (machine == 62) {
            String choice = LinuxEmulator.choice(container);
            LinuxEmulator.Installed emulator = LinuxEmulator.resolve(context, container);
            if (choice.equals(LinuxEmulator.NONE)) {
                return refusal("This is an x86-64 program. Choose an x86 emulator (Box64 or FEX) in the container's settings.");
            }
            if (emulator == null) {
                return refusal("The selected x86 emulator is not installed. Install it in Components, Linux Emulator.");
            }
            if (emulator.emulator.equals(LinuxEmulator.FEX) && LinuxRootfs.resolve(context, container) == null) {
                return refusal(LinuxFex.NEEDS_ROOTFS);
            }
            emulatorPath = LinuxEmulator.binary(emulator).getPath();
        }
        else if (machine == 3) {
            return refusal("This is a 32-bit x86 program. Only x86-64 programs are supported for now.");
        }
        // $1 the program, $2 the emulator (empty for a native program), $3 and $4 how many variables and wrapper words
        // follow, then those, then the program's arguments. The variables go through env so they reach the emulator and,
        // through it, the program; the wrapper (taskset, ...) starts the emulator.
        List<String> command = new ArrayList<>(java.util.Arrays.asList(
                "/bin/bash", "-c",
                "chmod +x \"$1\" 2>/dev/null; cd \"$(dirname \"$1\")\" || exit 1; "
                        + "prog=\"$1\"; emu=\"$2\"; nenv=\"$3\"; nwrap=\"$4\"; shift 4; "
                        + "envs=(\"${@:1:$nenv}\"); shift \"$nenv\"; wrap=(\"${@:1:$nwrap}\"); shift \"$nwrap\"; "
                        + "case \"$prog\" in *.sh) exec env \"${envs[@]}\" \"${wrap[@]}\" bash \"$prog\" \"$@\";; "
                        + "*) if [ -n \"$emu\" ]; then exec env \"${envs[@]}\" \"${wrap[@]}\" \"$emu\" \"$prog\" \"$@\"; "
                        + "else exec env \"${envs[@]}\" \"${wrap[@]}\" \"$prog\" \"$@\"; fi;; esac",
                "bannerlator-run", path, emulatorPath,
                String.valueOf(options.env.size()), String.valueOf(options.wrapper.size())));
        for (Map.Entry<String, String> entry : options.env.entrySet()) command.add(entry.getKey() + "=" + entry.getValue());
        command.addAll(options.wrapper);
        command.addAll(options.args);
        return command.toArray(new String[0]);
    }

    /** The program of a container's Steam shortcut: not a file, the runtime's Steam client. */
    public static final String STEAM_PROGRAM = "steam://client";

    public static boolean isSteam(String path) {
        return STEAM_PROGRAM.equals(path);
    }

    /**
     * Runs the runtime's native arm64 Steam client on the container's own display (no gamescope). The first run
     * downloads the client (bannerlator-steam-install); its output goes to Winlator/logs/linux-steam.log on shared
     * storage. Files a killed session left behind are cleared first, as the runtime's own session does.
     */
    private static String[] steamCommand() {
        return new String[]{"/bin/bash", "-c",
                "export XDG_DATA_HOME=\"${XDG_DATA_HOME:-$HOME/.local/share}\"; steam_root=\"$XDG_DATA_HOME/Steam\"; "
                        + "mkdir -p /storage/emulated/0/Winlator/logs; exec >/storage/emulated/0/Winlator/logs/linux-steam.log 2>&1; "
                        + "echo \"== steam start $(date) uid=$(id -u) home=$HOME\"; "
                        // The runtime ships gawk (what awk links to) without the execute bit, and the installer needs awk.
                        + "chmod 755 /usr/bin/gawk; "
                        + "bannerlator-steam-install || exit 1; "
                        + "rm -f \"$steam_root/.crash\" \"$steam_root/steam.pid\" \"$steam_root/config/htmlcache/\"Singleton*; "
                        + "export STEAM_RUNTIME=1 SDL_VIDEO_X11_DGAMOUSE=0 "
                        + "LD_LIBRARY_PATH=\"$steam_root/steamrtarm64:$steam_root/steamrtarm64/panorama\"; "
                        // The client exits with 42 after updating itself and wants a fresh start; an update that cannot
                        // install asks again every time, so the restarts are capped.
                        + "for try in 1 2 3 4; do \"$steam_root/steamrtarm64/steam\" -clientbeta publicbeta -no-cef-sandbox "
                        + "-cef-disable-gpu -cef-disable-gpu-compositing -cef-ozone-platform=x11; code=$?; "
                        + "[ \"$code\" = 42 ] || break; done; exit $code"};
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
        // The X server listens on X0 in this directory; the guest reaches it as X<displayNumber>.
        File xLink = new File(imageFsRoot, "usr/tmp/.X11-unix/X" + displayNumber());
        xLink.delete();
        try {
            android.system.Os.symlink("X0", xLink.getPath());
        }
        catch (android.system.ErrnoException e) {
            Log.w(TAG, "Cannot link the X display", e);
        }
        writeAccounts(rt, uid);
        writePreload(context, rt);

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
        // Android's own /dev/input is unreadable by apps, and SDL finds a pad by listing that directory: the app's
        // gamepad files stand in for it. libskyshim turns them from plain files into evdev devices (see guestEnv).
        File inputDir = inputDir(context);
        inputDir.mkdirs();
        try {
            // SDL lists the pads once, as it starts: a node that appears later is only found if the program
            // watches for one, so the first slot is always there, for the on-screen pad if for nothing else.
            new File(inputDir, "event0").createNewFile();
        }
        catch (java.io.IOException e) {
            Log.w(TAG, "Cannot create the gamepad file", e);
        }
        bind(argv, inputDir.getPath(), "/dev/input");
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
        env.put("DISPLAY", ":" + displayNumber());
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

        // Gamepad: libskyshim answers the evdev ioctls on the files in this folder (see inputDir), which build()
        // binds over /dev/input. SDL lists a pad by reading that directory, so the bind is what makes it visible;
        // on its own the shim only answers a path a program already knows.
        env.put("SKY_INPUT_DIR", inputDir(context).getPath());
        // No udev runs in the session, so SDL must scan /dev/input itself instead of waiting on a monitor.
        env.put("SDL_JOYSTICK_DISABLE_UDEV", "1");
        env.put("SDL_HIDAPI_JOYSTICK_DISABLE_UDEV", "1");
        // SDL drops joystick events while its window lacks keyboard focus, and without a window manager the
        // core FocusIn never reaches SDL (see docs/linux-input-findings.md). Let pad events through regardless.
        env.put("SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS", "1");

        // The X server cannot take dma-bufs yet: with this on, Vulkan copies finished frames on the CPU.
        if (!"native".equals(container.getExtra(EXTRA_VULKAN_PRESENT))) env.put("MESA_VK_WSI_DEBUG", "sw");

        // Frame generation (Lossless Scaling through lsfg-vk): a Vulkan layer the guest's loader picks up.
        com.winlator.cmod.lsfg.LsfgManager.prepareLinuxLaunch(context, container, env);

        LinuxEmulator.Installed emulator = LinuxEmulator.resolve(context, container);
        if (emulator != null && emulator.emulator.equals(LinuxEmulator.BOX64)) {
            // The libraries Box64 does not wrap come from the package; the program's own folder is searched too.
            env.put("BOX64_LD_LIBRARY_PATH", new File(emulator.dir, "lib/box64-x86_64-linux-gnu").getPath());
            env.put("BOX64_NOBANNER", "1");
            // An emulated program is an x86-64 process, so the session's aarch64 libraries are never loaded into it:
            // the shims it needs are preloaded again, built for that architecture.
            StringBuilder preload = new StringBuilder();
            for (String name : new String[]{
                    "libskyshim_x64.so",   // the gamepad's evdev ioctls and the uevent socket
                    "libcefnosb_x64.so"}) { // an embedded Chromium (CEF) aborts on the setuid sandbox check
                File shim = stageAsset(context, name);
                if (shim == null) continue;
                if (preload.length() > 0) preload.append(':');
                preload.append(shim.getPath());
            }
            if (preload.length() > 0) env.put("BOX64_LD_PRELOAD", preload.toString());
            env.put("BOX64_DYNAREC", "1");
            // The Box64 log switch in Settings (on by default in a debug build) reaches Linux sessions too.
            if (androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    .getBoolean("enable_box64_logs", com.winlator.cmod.core.AppUtils.isDebugBuild(context))) {
                env.put("BOX64_LOG", "1");
            }
            String preset = container.getExtra(LinuxEmulator.EXTRA_PRESET);
            if (preset.isEmpty()) preset = com.winlator.cmod.box64.Box64Preset.COMPATIBILITY;
            for (String pair : com.winlator.cmod.box64.Box64PresetManager.getEnvVars("box64", context, preset).toStringArray()) {
                int index = pair.indexOf('=');
                if (index > 0) env.put(pair.substring(0, index), pair.substring(index + 1));
            }
        }

        else if (emulator != null && emulator.emulator.equals(LinuxEmulator.FEX)) {
            // FEX runs the program in an x86-64 root filesystem of its own, so nothing of Box64's applies. A missing
            // root filesystem is reported when the program is started (programCommand).
            String problem = LinuxFex.prepare(context, container, emulator, env);
            if (problem != null) Log.w(TAG, problem);
        }

        // Native libraries the emulated program's wrapped libraries need that the runtime does not ship: the
        // emulator package's lib/host and the container's own hostlibs folder.
        java.util.List<String> extraLibs = new ArrayList<>();
        File containerLibs = new File(container.getLinuxHomeDir(), "hostlibs");
        if (containerLibs.isDirectory()) extraLibs.add("/root/hostlibs");
        // libXss (X screensaver): Box64 wraps it, the runtime does not ship it, and a game with an embedded
        // Chromium (CEF) needs it to start. Arch Linux ARM's libxss 1.2.5, taken from the package unchanged.
        // (Box64 only: FEX's guest uses the x86-64 libXss of its own root filesystem.)
        File xss = emulator != null && emulator.emulator.equals(LinuxEmulator.FEX) ? null : stageAsset(context, "libXss.so.1");
        if (xss != null) extraLibs.add(xss.getParent());
        if (emulator != null && new File(emulator.dir, "lib/host").isDirectory()) {
            extraLibs.add(new File(emulator.dir, "lib/host").getPath());
        }
        if (!extraLibs.isEmpty()) env.put("LD_LIBRARY_PATH", String.join(":", extraLibs));

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
    private static void writePreload(Context context, File rt) {
        File preload = new File(rt, "etc/ld.so.preload");
        StringBuilder libraries = new StringBuilder();
        if (new File(rt, PRELOAD.substring(1)).exists()) libraries.append(PRELOAD).append('\n');
        File shim = stageShim(context);
        if (shim != null) libraries.append(shim.getPath()).append('\n');
        if (libraries.length() > 0) FileUtils.writeString(preload, libraries.toString());
        else preload.delete();
    }

    /**
     * Copies our own preload library (tools/linux-shim: answers the uevent socket that Android refuses, which SDL needs
     * to start) out of the APK. files/ is bound into the session at its own path, so the guest can load it from there.
     */
    private static File stageShim(Context context) {
        return stageAsset(context, "libskyshim.so");
    }

    private static File stageAsset(Context context, String name) {
        return stageAsset(context, "linux-shim", name);
    }

    /** Copies one of the app's own Linux binaries out of the APK into files/{@code dirName}. */
    static File stageAsset(Context context, String dirName, String name) {
        File dir = new File(context.getFilesDir(), dirName);
        File shim = new File(dir, name);
        try (java.io.InputStream in = context.getAssets().open("linux/" + name)) {
            byte[] data = in.readAllBytes();
            if (!shim.isFile() || shim.length() != data.length) {
                dir.mkdirs();
                File temp = new File(dir, name + ".tmp");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                    out.write(data);
                }
                FileUtils.chmod(temp, 0755);
                if (!temp.renameTo(shim)) return null;
            }
            return shim;
        }
        catch (java.io.IOException e) {
            Log.w(TAG, "Cannot stage " + name, e);
            return null;
        }
    }

    /** Where the app writes the controllers' evdev events; bound over /dev/input in the session. */
    public static File inputDir(Context context) {
        return new File(context.getFilesDir(), "imagefs/dev/input");
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
