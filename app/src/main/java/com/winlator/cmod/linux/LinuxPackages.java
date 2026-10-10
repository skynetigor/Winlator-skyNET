package com.winlator.cmod.linux;

import android.content.Context;

import java.io.File;

/** Where each kind of Linux package (runtime, x86 emulator, x86 root filesystem) is installed. */
public final class LinuxPackages {
    public static final String KIND_RUNTIME = "runtime";
    public static final String KIND_EMULATOR = "emulator";
    public static final String KIND_ROOTFS = "rootfs";

    private LinuxPackages() {}

    public static File baseDir(Context context, String kind) {
        String name;
        switch (kind) {
            case KIND_EMULATOR:
                name = "linux-emulators";
                break;
            case KIND_ROOTFS:
                name = "linux-rootfs";
                break;
            default:
                name = "linux-runtimes";
        }
        return new File(context.getFilesDir(), name);
    }

    public static File rootDir(Context context, String kind, String id) {
        return new File(baseDir(context, kind), id);
    }
}
