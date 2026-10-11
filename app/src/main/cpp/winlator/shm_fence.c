/*
 * The shared-memory half of an X sync fence (libxshmfence, the Linux futex variant).
 *
 * A Mesa client creates the fence in memory it shares with the X server, passes the file descriptor to DRI3
 * FenceFromFD, and waits on the shared word until the server says the buffer is idle again. The word is 0 while the
 * fence is untriggered, 1 once triggered and -1 while someone sleeps on it. Triggering is what the server owes it.
 */
#include <jni.h>
#include <limits.h>
#include <linux/futex.h>
#include <stdint.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_xserver_ShmFence_map(JNIEnv *env, jclass obj, jint fd) {
    void *address = mmap(NULL, sizeof(int32_t), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    return address == MAP_FAILED ? 0 : (jlong)(intptr_t)address;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_ShmFence_trigger(JNIEnv *env, jclass obj, jlong address) {
    volatile int32_t *word = (volatile int32_t *)(intptr_t)address;
    if (!word) return;
    if (__sync_val_compare_and_swap(word, 0, 1) == -1) {
        __atomic_store_n(word, 1, __ATOMIC_SEQ_CST);
        syscall(SYS_futex, word, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_xserver_ShmFence_unmap(JNIEnv *env, jclass obj, jlong address) {
    if (address) munmap((void *)(intptr_t)address, sizeof(int32_t));
}
