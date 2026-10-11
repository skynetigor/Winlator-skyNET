package com.winlator.cmod.xserver;

/** The shared word of a libxshmfence fence a Linux client created (see cpp/winlator/shm_fence.c). */
public abstract class ShmFence {
    /** Maps the fence's file descriptor; 0 when it cannot be mapped. */
    public static native long map(int fd);

    /** Marks the fence triggered and wakes whoever sleeps on it. */
    public static native void trigger(long address);

    public static native void unmap(long address);
}
