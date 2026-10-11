package com.winlator.cmod.linux;

import android.content.Context;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.FileUtils;

import java.io.File;

/**
 * The input testers that ship with the app. They draw what the guest's SDL reports, so the controller and the
 * pointer the app believes it is sending can be checked against what a program actually receives, with nothing
 * to install.
 *
 * Each comes in two builds: the native one tests the container, and the x86-64 one tests the same input as seen
 * by a program under Box64, which is where a game like BeamNG reads it.
 *
 * They are for development: the binaries live in src/debug/assets, so a release build does not carry them, and
 * nothing here runs unless the app is debuggable.
 */
public final class LinuxTestPrograms {
    private static final String TOOLS_DIR = "linux-tools";
    private static final String GAMEPAD_TEST = "gamepadtest";
    private static final String GAMEPAD_TEST_X64 = "gamepadtest_x64";
    private static final String MOUSE_TEST = "mousetest";
    private static final String MOUSE_TEST_X64 = "mousetest_x64";

    private LinuxTestPrograms() {}

    /** True in a debug build only; a release build has neither the testers nor the demo shortcuts. */
    public static boolean enabled(Context context) {
        return (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /** Puts the testers where the session can reach them and gives the container a shortcut for each. */
    public static void ensure(Context context, Container container) {
        if (container == null || !container.isLinux() || !enabled(context)) return;
        addShortcut(context, container, "Gamepad test", GAMEPAD_TEST);
        addShortcut(context, container, "Gamepad test (x86-64)", GAMEPAD_TEST_X64);
        addShortcut(context, container, "Mouse test", MOUSE_TEST);
        addShortcut(context, container, "Mouse test (x86-64)", MOUSE_TEST_X64);
    }

    private static void addShortcut(Context context, Container container, String name, String asset) {
        // files/ is bound into the session at its own path, so what the app writes is what the guest runs.
        File program = LinuxSession.stageAsset(context, TOOLS_DIR, asset);
        if (program == null) return;
        File shortcut = new File(container.getDesktopDir(), name + ".desktop");
        if (shortcut.isFile()) return;
        FileUtils.chmod(program, 0755);
        ContainerManager.addLinuxShortcut(container, name, program.getPath());
    }
}
