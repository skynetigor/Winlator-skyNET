/*
 * Stand-in for Android's <android/hardware_buffer.h> on a glibc Linux build of lsfg-vk. The hardware-buffer code
 * paths are compiled but never used there (the frames are shared as VkImages on one device), so the one function the
 * sources call does nothing.
 */
#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

struct AHardwareBuffer;
typedef struct AHardwareBuffer AHardwareBuffer;

static inline void AHardwareBuffer_acquire(AHardwareBuffer *buffer) { (void)buffer; }

#ifdef __cplusplus
}
#endif
