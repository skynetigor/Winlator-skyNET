package com.winlator.cmod.linux;

import android.content.Context;

import com.winlator.cmod.container.Container;

/** How a Linux container is named in the lists: the system, its x86 emulator and the way frames reach the screen. */
public final class LinuxContainerInfo {
    private LinuxContainerInfo() {}

    /** "ARM64" when the container has no x86 emulator, else the emulator's build ("Box64 0.4.4"). */
    public static String emulatorLabel(Context context, Container container) {
        String choice = LinuxEmulator.choice(container);
        if (choice.equals(LinuxEmulator.NONE)) return "ARM64";
        LinuxEmulator.Installed installed = LinuxEmulator.resolve(context, container);
        if (installed != null) return installed.name;
        return choice.equals(LinuxEmulator.FEX) ? "FEX" : "Box64";
    }

    /** "Vulkan" for the dma-buf (native) output, which the Vulkan renderer shows; "EGL" for the CPU-copy one. */
    public static String outputLabel(Container container) {
        return "native".equals(container.getExtra(LinuxSession.EXTRA_VULKAN_PRESENT)) ? "Vulkan" : "EGL";
    }

    public static String summary(Context context, Container container, String separator) {
        return "Linux" + separator + emulatorLabel(context, container) + separator + outputLabel(container);
    }
}
