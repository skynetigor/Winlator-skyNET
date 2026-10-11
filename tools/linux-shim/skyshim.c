/*
 * skyshim: a small LD_PRELOAD library for the Winlator skyNET Linux runtime (glibc aarch64, run under proot on Android).
 *
 * Android's app sandbox refuses a netlink "kobject uevent" socket, which libudev opens to watch for devices. SDL's
 * joystick code treats that failure as fatal ("Could not initialize UDEV") and SDL_Init fails, so games that use SDL
 * cannot start. The shim answers that one socket with a harmless local socket: there are never any device events here.
 *
 * Gamepads: Android has no /dev/input for apps. The app writes the controller's state as evdev events into
 * $SKY_INPUT_DIR/event<N> (see FakeInputWriter). Programs that open /dev/input/event<N> (SDL does, when told with
 * SDL_JOYSTICK_DEVICE) are given that file, and its ioctls are answered as an Xbox 360 pad.
 *
 * Interposed: socket, bind, getsockname, setsockopt, getsockopt, close, open, open64, stat, stat64, lstat, lstat64,
 * ioctl, read. Everything else goes to libc unchanged.
 *
 * MIT licence, written for this project.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <time.h>
#include <linux/input.h>
#include <linux/netlink.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define MAX_FAKE 32
static int fake_fds[MAX_FAKE];
static int fake_count;

static int is_fake(int fd) {
    for (int i = 0; i < fake_count; i++) if (fake_fds[i] == fd) return 1;
    return 0;
}

static void remember(int fd) {
    if (fake_count < MAX_FAKE) fake_fds[fake_count++] = fd;
}

static void forget(int fd) {
    for (int i = 0; i < fake_count; i++) {
        if (fake_fds[i] == fd) {
            fake_fds[i] = fake_fds[--fake_count];
            return;
        }
    }
}


/* ---- gamepads ---------------------------------------------------------------------------------------------- */

#define MAX_PADS 16
static int pad_fds[MAX_PADS];
static int pad_clocks[MAX_PADS];
static int pad_count;

static int pad_is(int fd) {
    for (int i = 0; i < pad_count; i++) if (pad_fds[i] == fd) return 1;
    return 0;
}

static void pad_remember(int fd) {
    if (pad_count < MAX_PADS) {
        pad_clocks[pad_count] = CLOCK_REALTIME; // what evdev uses until a program asks for another
        pad_fds[pad_count++] = fd;
    }
}

static void pad_set_clock(int fd, int clock) {
    for (int i = 0; i < pad_count; i++) if (pad_fds[i] == fd) pad_clocks[i] = clock;
}

static int pad_clock(int fd) {
    for (int i = 0; i < pad_count; i++) if (pad_fds[i] == fd) return pad_clocks[i];
    return CLOCK_REALTIME;
}

static void pad_forget(int fd) {
    for (int i = 0; i < pad_count; i++) {
        if (pad_fds[i] == fd) {
            pad_fds[i] = pad_fds[--pad_count];
            pad_clocks[i] = pad_clocks[pad_count];
            return;
        }
    }
}

/* "/dev/input/event3" -> the app's file for slot 3 in {out}; returns the slot or -1. */
static int pad_path(const char *path, char *out, size_t size) {
    if (!path || strncmp(path, "/dev/input/event", 16) != 0) return -1;
    const char *digits = path + 16;
    if (!*digits) return -1;
    for (const char *c = digits; *c; c++) if (*c < '0' || *c > '9') return -1;
    const char *dir = getenv("SKY_INPUT_DIR");
    if (!dir || !*dir) return -1;
    snprintf(out, size, "%s/event%s", dir, digits);
    return atoi(digits);
}

int open(const char *path, int flags, ...) {
    static int (*real)(const char *, int, ...);
    if (!real) real = dlsym(RTLD_NEXT, "open");
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, mode_t);
        va_end(ap);
    }
    char backing[512];
    if (pad_path(path, backing, sizeof backing) >= 0) {
        int fd = real(backing, (flags & ~(O_WRONLY | O_RDWR | O_CREAT | O_TRUNC | O_APPEND)) | O_RDONLY, 0);
        if (fd >= 0) {
            lseek(fd, 0, SEEK_END); // only events from now on
            pad_remember(fd);
        }
        return fd;
    }
    return real(path, flags, mode);
}

int open64(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, mode_t);
        va_end(ap);
    }
    return open(path, flags, mode);
}

static void pad_fake_stat(const char *path, struct stat *st, int slot) {
    (void)path;
    st->st_mode = S_IFCHR | 0660;
    st->st_rdev = makedev(13, 64 + slot);
}

#define STAT_HOOK(name, type) \
int name(const char *path, type *st) { \
    static int (*real)(const char *, type *); \
    if (!real) real = dlsym(RTLD_NEXT, #name); \
    char backing[512]; \
    int slot = pad_path(path, backing, sizeof backing); \
    if (slot >= 0) { \
        int r = real(backing, st); \
        if (r == 0) pad_fake_stat(path, (struct stat *)st, slot); \
        return r; \
    } \
    return real(path, st); \
}
STAT_HOOK(stat, struct stat)
STAT_HOOK(lstat, struct stat)
STAT_HOOK(stat64, struct stat64)
STAT_HOOK(lstat64, struct stat64)

static void set_bit(unsigned char *bits, size_t size, int n) {
    if ((size_t)(n >> 3) < size) bits[n >> 3] |= 1 << (n & 7);
}

/* Answers the evdev queries SDL makes about an Xbox 360 pad. */
static int pad_ioctl(unsigned long req, void *arg) {
    unsigned nr = req & 0xff;
    size_t size = (req >> 16) & 0x3fff;
    if (((req >> 8) & 0xff) != 'E') {
        errno = ENOTTY;
        return -1;
    }
    if (nr == 0x01) { *(int *)arg = 0x010001; return 0; }
    if (nr == 0x02) {
        struct input_id id = { 3 /* USB */, 0x045e, 0x028e, 0x0110 };
        memcpy(arg, &id, sizeof id);
        return 0;
    }
    if (nr == 0x06) {
        const char *name = "Microsoft X-Box 360 pad";
        size_t n = strlen(name) + 1;
        if (n > size) n = size;
        memcpy(arg, name, n);
        return (int)n;
    }
    if (nr == 0x07 || nr == 0x08) { errno = ENOENT; return -1; }
    if (nr == 0x09 || nr == 0x18 || nr == 0x19 || nr == 0x1b) { memset(arg, 0, size); return 0; }
    if (nr >= 0x20 && nr < 0x40) {
        int type = nr - 0x20;
        unsigned char *bits = arg;
        memset(bits, 0, size);
        if (type == 0) {
            set_bit(bits, size, EV_SYN);
            set_bit(bits, size, EV_KEY);
            set_bit(bits, size, EV_ABS);
        }
        else if (type == EV_KEY) {
            // BTN_MODE is listed though never sent, so SDL numbers the stick buttons as it does for a real pad.
            static const int keys[] = { 0x130, 0x131, 0x133, 0x134, 0x136, 0x137, 0x13a, 0x13b, 0x13c, 0x13d, 0x13e };
            for (unsigned i = 0; i < sizeof keys / sizeof keys[0]; i++) set_bit(bits, size, keys[i]);
        }
        else if (type == EV_ABS) {
            static const int axes[] = { ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ, ABS_HAT0X, ABS_HAT0Y };
            for (unsigned i = 0; i < sizeof axes / sizeof axes[0]; i++) set_bit(bits, size, axes[i]);
        }
        return 0;
    }
    if (nr >= 0x40 && nr < 0x80) {
        int axis = nr - 0x40;
        struct input_absinfo info;
        memset(&info, 0, sizeof info);
        if (axis == ABS_Z || axis == ABS_RZ) { info.minimum = 0; info.maximum = 255; }
        else if (axis == ABS_HAT0X || axis == ABS_HAT0Y) { info.minimum = -1; info.maximum = 1; }
        else { info.minimum = -32768; info.maximum = 32767; info.fuzz = 16; info.flat = 128; }
        memcpy(arg, &info, size < sizeof info ? size : sizeof info);
        return 0;
    }
    if (nr == 0x90) return 0; // grab
    errno = EINVAL;
    return -1;
}

int ioctl(int fd, unsigned long req, ...) {
    static int (*real)(int, unsigned long, ...);
    if (!real) real = dlsym(RTLD_NEXT, "ioctl");
    va_list ap;
    va_start(ap, req);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    if (pad_is(fd)) {
        // EVIOCSCLOCKID: a program that asks for one clock and is answered on another reads every event as
        // decades old or ahead of now, which is how long ago it looks like the button was pressed.
        if ((req & 0xff) == 0xa0 && ((req >> 8) & 0xff) == 'E') {
            pad_set_clock(fd, arg ? *(const int *)arg : CLOCK_REALTIME);
            return 0;
        }
        return pad_ioctl(req, arg);
    }
    return real(fd, req, arg);
}

ssize_t read(int fd, void *buf, size_t count) {
    static ssize_t (*real)(int, void *, size_t);
    if (!real) real = dlsym(RTLD_NEXT, "read");
    ssize_t n = real(fd, buf, count);
    if (n >= 0 && pad_is(fd)) {
        if (n == 0) { errno = EAGAIN; return -1; } // a device that has nothing yet, not end of file
        struct timespec now;
        if (clock_gettime(pad_clock(fd), &now) != 0) now.tv_sec = now.tv_nsec = 0;
        // The app names the triggers ABS_BRAKE / ABS_GAS; an Xbox 360 pad reports them as ABS_Z / ABS_RZ.
        struct input_event *events = buf;
        for (size_t i = 0; i < (size_t)n / sizeof *events; i++) {
            if (events[i].type == EV_ABS && events[i].code == ABS_BRAKE) events[i].code = ABS_Z;
            else if (events[i].type == EV_ABS && events[i].code == ABS_GAS) events[i].code = ABS_RZ;
            // The app stamps events with its own wall clock; a reader wants them on the clock it chose,
            // and these have only just been read, so "now" is the honest answer.
            events[i].time.tv_sec = now.tv_sec;
            events[i].time.tv_usec = now.tv_nsec / 1000;
        }
    }
    return n;
}

int socket(int domain, int type, int protocol) {
    static int (*real)(int, int, int);
    if (!real) real = dlsym(RTLD_NEXT, "socket");
    if (domain == AF_NETLINK && protocol == NETLINK_KOBJECT_UEVENT) {
        int pair[2];
        int flags = type & (SOCK_NONBLOCK | SOCK_CLOEXEC);
        if (socketpair(AF_UNIX, SOCK_DGRAM | flags, 0, pair) == 0) {
            // The other end stays open (and unused) so reads see "no data yet" and not end-of-file.
            remember(pair[0]);
            return pair[0];
        }
    }
    return real(domain, type, protocol);
}

int bind(int fd, const struct sockaddr *addr, socklen_t len) {
    static int (*real)(int, const struct sockaddr *, socklen_t);
    if (!real) real = dlsym(RTLD_NEXT, "bind");
    if (is_fake(fd)) return 0;
    return real(fd, addr, len);
}

int getsockname(int fd, struct sockaddr *addr, socklen_t *len) {
    static int (*real)(int, struct sockaddr *, socklen_t *);
    if (!real) real = dlsym(RTLD_NEXT, "getsockname");
    if (is_fake(fd)) {
        struct sockaddr_nl nl;
        memset(&nl, 0, sizeof nl);
        nl.nl_family = AF_NETLINK;
        nl.nl_pid = (unsigned)getpid();
        socklen_t n = *len < sizeof nl ? *len : sizeof nl;
        memcpy(addr, &nl, n);
        *len = sizeof nl;
        return 0;
    }
    return real(fd, addr, len);
}

int setsockopt(int fd, int level, int name, const void *value, socklen_t len) {
    static int (*real)(int, int, int, const void *, socklen_t);
    if (!real) real = dlsym(RTLD_NEXT, "setsockopt");
    if (is_fake(fd)) return 0;
    return real(fd, level, name, value, len);
}

int getsockopt(int fd, int level, int name, void *value, socklen_t *len) {
    static int (*real)(int, int, int, void *, socklen_t *);
    if (!real) real = dlsym(RTLD_NEXT, "getsockopt");
    if (is_fake(fd)) {
        // A receive-buffer size is the only thing libudev reads back; any plausible number will do.
        if (level == SOL_SOCKET && (name == SO_RCVBUF || name == SO_SNDBUF) && value && *len >= sizeof(int)) {
            *(int *)value = 1 << 20;
            *len = sizeof(int);
            return 0;
        }
    }
    return real(fd, level, name, value, len);
}

int close(int fd) {
    static int (*real)(int);
    if (!real) real = dlsym(RTLD_NEXT, "close");
    if (is_fake(fd)) forget(fd);
    pad_forget(fd);
    return real(fd);
}

/* ---- SDL event trace (debugging) -----------------------------------------------------------------------------
 * With the file /root/.sdltrace present, SDL_PollEvent / SDL_PeepEvents results are logged to /root/sdltrace.log
 * (event type and, for mouse buttons and gamepad events, the first words). Off by default. */
static int sdl_trace_on(void) {
    static int state = -1;
    if (state < 0) state = access("/root/.sdltrace", F_OK) == 0;
    return state;
}

static void sdl_log(const char *func, const unsigned int *e) {
    unsigned int t = e[0];
    if (t == 0x400 || t == 0x1000 || t == 0x1001 || t == 0x7f00 || (t >= 0x7000 && t < 0x8000)) return; // motion / frame noise
    static int fd = -1;
    if (fd < 0) fd = open("/root/sdltrace.log", O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) return;
    char b[200];
    int n = snprintf(b, sizeof b, "%s type=0x%x w=%08x %08x %08x %08x %08x\n", func, t, e[1], e[2], e[3], e[4], e[5]);
    write(fd, b, n);
}

int SDL_PollEvent(void *event) {
    static int (*real)(void *);
    if (!real) real = dlsym(RTLD_NEXT, "SDL_PollEvent");
    int r = real(event);
    if (r && event && sdl_trace_on()) sdl_log("SDL_PollEvent", event);
    return r;
}
