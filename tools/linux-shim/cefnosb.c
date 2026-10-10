/*
 * cefnosb: an x86_64 LD_PRELOAD library for programs run through Box64 that embed Chromium (CEF), such as BeamNG.drive.
 *
 * CEF's browser process aborts at start when the sandbox helper next to the program (chrome-sandbox) is not
 * root-owned and setuid, which cannot be arranged inside an Android app. Games do not take a switch for this, so this
 * library sets no_sandbox in the settings passed to cef_initialize. It does nothing for programs without CEF.
 *
 * MIT licence, written for this project.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stddef.h>

/* cef_settings_t starts with { size_t size; int no_sandbox; ... } */
struct cef_settings_head { size_t size; int no_sandbox; };

int cef_initialize(const void *args, const void *settings, void *app, void *sandbox_info) {
    static int (*real)(const void *, const void *, void *, void *);
    if (!real) real = dlsym(RTLD_NEXT, "cef_initialize");
    ((struct cef_settings_head *)settings)->no_sandbox = 1;
    return real(args, settings, app, sandbox_info);
}
