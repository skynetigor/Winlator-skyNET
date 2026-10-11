/* Stand-in for Android's <android/log.h> on a glibc Linux build of lsfg-vk: log lines go to stderr. */
#pragma once
#include <stdarg.h>
#include <stdio.h>

enum {
    ANDROID_LOG_UNKNOWN = 0, ANDROID_LOG_DEFAULT, ANDROID_LOG_VERBOSE, ANDROID_LOG_DEBUG, ANDROID_LOG_INFO,
    ANDROID_LOG_WARN, ANDROID_LOG_ERROR, ANDROID_LOG_FATAL, ANDROID_LOG_SILENT
};

static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    (void)prio;
    va_list args;
    va_start(args, fmt);
    fprintf(stderr, "%s: ", tag);
    vfprintf(stderr, fmt, args);
    fputc('\n', stderr);
    va_end(args);
    return 1;
}
