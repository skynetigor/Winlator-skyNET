package com.winlator.cmod.linux;

import android.content.Context;

import com.winlator.cmod.core.FileUtils;

import java.io.File;

/** The shared Linux userland (a glibc aarch64 rootfs) that Linux containers run in. */
public final class LinuxRuntime {
    private LinuxRuntime() {}

    public static File rootDir(Context context) {
        return new File(context.getFilesDir(), "linuxfs");
    }

    public static boolean isInstalled(Context context) {
        LinuxRuntimeInstaller.recoverInterruptedSwap(context);
        File root = rootDir(context);
        return new File(root, "usr/bin/gamescope").exists()
                && new File(root, "usr/local/bin/bannerlator-session").exists()
                && new File(root, "opt/android-host/proot").exists();
    }

    /** The installed runtime release, or an empty string when none is installed. */
    public static String installedVersion(Context context) {
        File version = new File(rootDir(context), ".version");
        if (!version.isFile()) return "";
        String value = FileUtils.readString(version);
        return value != null ? value.trim() : "";
    }
}
