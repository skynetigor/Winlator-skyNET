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
 * x86-64 programs (aarch64 build only): there is no binfmt_misc here, so a native program that starts an x86-64 one (the
 * Steam client starting a game) would get "Exec format error". When $SKY_X86_EMULATOR names an emulator (Box64), the exec
 * and spawn functions put it in front of any program whose ELF header says x86-64.
 *
 * Interposed: socket, bind, getsockname, setsockopt, getsockopt, close, open, open64, stat, stat64, lstat, lstat64,
 * ioctl, read, and (aarch64) execve, execv, execvp, execvpe, execl, execlp, execle, execveat, fexecve, syscall (only for
 * execveat), posix_spawn, posix_spawnp.
 * Everything else goes to libc unchanged.
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
#include <sys/syscall.h>
#include <spawn.h>

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

/*
 * A game with an embedded Chromium (CEF) reads icudtl.dat and its .pak files from the game's folder, one level above the
 * program's; BeamNG's depot has them only next to the program. When SKY_RESOURCE_DIR names the program's folder, an open
 * of one of these files that fails is tried there.
 */
static int resource_fallback(const char *path, int flags, mode_t mode, int (*real)(const char *, int, ...)) {
    static const char *const names[] = {"icudtl.dat", "resources.pak", "chrome_100_percent.pak", "chrome_200_percent.pak",
                                        "snapshot_blob.bin", "v8_context_snapshot.bin", NULL};
    const char *dir = getenv("SKY_RESOURCE_DIR");
    if (!dir || !*dir || !path) return -1;
    const char *base = strrchr(path, '/');
    base = base ? base + 1 : path;
    for (int i = 0; names[i]; i++) {
        if (strcmp(base, names[i]) != 0) continue;
        char alt[512];
        snprintf(alt, sizeof alt, "%s/%s", dir, base);
        return real(alt, flags, mode);
    }
    return -1;
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
    int fd = real(path, flags, mode);
    if (fd < 0 && errno == ENOENT) {
        int saved = errno;
        int alt = resource_fallback(path, flags, mode, real);
        if (alt >= 0) return alt;
        errno = saved;
    }
    return fd;
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

/* (An SDL_PollEvent trace used to be here. A game that loads SDL after this library, as Counter-Strike: Source does, resolved
 * its SDL_PollEvent to the trace's, which found no SDL behind it and called a null pointer.) */

/* ---- x86-64 programs through an emulator ------------------------------------------------------------------------- */
#if defined(__aarch64__)
extern char **environ;

static const char *x86_emulator(void) {
    static const char *emulator;
    static int looked;
    if (!looked) {
        emulator = getenv("SKY_X86_EMULATOR");
        if (emulator && !*emulator) emulator = NULL;
        looked = 1;
    }
    return emulator;
}

/* Whether the file at path is a 64-bit x86 ELF (e_machine 62). */
static int is_x86_64(const char *path) {
    unsigned char header[20];
    int fd = syscall(SYS_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = syscall(SYS_read, fd, header, sizeof header);
    syscall(SYS_close, fd);
    return n == (ssize_t)sizeof header && memcmp(header, "\177ELF", 4) == 0 && header[4] == 2 && header[18] == 62 && header[19] == 0;
}

/* argv with the emulator and the program in front; NULL if it cannot be built. */
static char **with_emulator(const char *emulator, const char *path, char *const argv[]) {
    size_t argc = 0;
    while (argv && argv[argc]) argc++;
    char **result = malloc((argc + 3) * sizeof(char *));
    if (!result) return NULL;
    result[0] = (char *)emulator;
    result[1] = (char *)path;
    for (size_t i = 1; i < argc; i++) result[i + 1] = argv[i];
    result[argc > 0 ? argc + 1 : 2] = NULL;
    return result;
}

/* The file execvp would run: file itself if it has a slash, else the first match along PATH. */
static const char *search_path(const char *file, char *buffer, size_t size) {
    if (strchr(file, '/')) return file;
    const char *path = getenv("PATH");
    if (!path) path = "/usr/local/bin:/usr/bin:/bin";
    size_t length = strlen(file);
    while (*path) {
        const char *end = strchr(path, ':');
        size_t part = end ? (size_t)(end - path) : strlen(path);
        if (part + 1 + length + 1 <= size) {
            memcpy(buffer, part ? path : ".", part ? part : 1);
            size_t at = part ? part : 1;
            buffer[at++] = '/';
            memcpy(buffer + at, file, length + 1);
            if (access(buffer, X_OK) == 0) return buffer;
        }
        if (!end) break;
        path = end + 1;
    }
    return NULL;
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    static int (*real)(const char *, char *const[], char *const[]);
    if (!real) real = dlsym(RTLD_NEXT, "execve");
    const char *emulator = x86_emulator();
    if (emulator && path && is_x86_64(path)) {
        char **wrapped = with_emulator(emulator, path, argv);
        if (wrapped) {
            int result = real(emulator, wrapped, envp);
            int saved = errno;
            free(wrapped);
            errno = saved;
            return result;
        }
    }
    return real(path, argv, envp);
}

int execv(const char *path, char *const argv[]) {
    return execve(path, argv, environ);
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    char buffer[4096];
    const char *found = file ? search_path(file, buffer, sizeof buffer) : NULL;
    if (found) {
        const char *emulator = x86_emulator();
        if (emulator && is_x86_64(found)) return execve(found, argv, envp);
    }
    static int (*real)(const char *, char *const[], char *const[]);
    if (!real) real = dlsym(RTLD_NEXT, "execvpe");
    return real(file, argv, envp);
}

int execvp(const char *file, char *const argv[]) {
    return execvpe(file, argv, environ);
}

/* The execl family: gather the arguments into an array and call the functions above. */
#define GATHER(first, list) \
    size_t count = 1; \
    va_list probe; va_start(probe, first); \
    while (va_arg(probe, char *)) count++; \
    va_end(probe); \
    char **list = malloc((count + 1) * sizeof(char *)); \
    if (!list) { errno = ENOMEM; return -1; } \
    va_list args; va_start(args, first); \
    list[0] = (char *)first; \
    for (size_t i = 1; i <= count; i++) list[i] = va_arg(args, char *); \
    va_end(args)

int execl(const char *path, const char *arg, ...) {
    GATHER(arg, list);
    int result = execve(path, list, environ);
    int saved = errno; free(list); errno = saved;
    return result;
}

int execlp(const char *file, const char *arg, ...) {
    GATHER(arg, list);
    int result = execvpe(file, list, environ);
    int saved = errno; free(list); errno = saved;
    return result;
}

int execle(const char *path, const char *arg, ...) {
    size_t count = 1;
    va_list probe;
    va_start(probe, arg);
    while (va_arg(probe, char *)) count++;
    va_end(probe);
    char **list = malloc((count + 1) * sizeof(char *));
    if (!list) { errno = ENOMEM; return -1; }
    va_list args;
    va_start(args, arg);
    list[0] = (char *)arg;
    for (size_t i = 1; i <= count; i++) list[i] = va_arg(args, char *);
    char *const *envp = va_arg(args, char *const *);
    va_end(args);
    int result = execve(path, list, envp);
    int saved = errno; free(list); errno = saved;
    return result;
}

/* ---- execveat with a descriptor ------------------------------------------------------------------------------------------
 * proot (the Termux build in the runtime) refuses execveat unless its directory descriptor is AT_FDCWD: "execveat() with
 * non-AT_FDCWD fd is not currently supported". FEX runs a program by re-executing itself through execveat, and a guest's own
 * fexecve (Chromium's helper processes) reaches it the same way. Here the call is turned into an execve of the path the
 * descriptor names (/proc/self/fd/N), which proot handles. A descriptor without a path (a memfd) cannot be run this way. */
static int path_of_fd(int fd, char *out, size_t size) {
    char link[64];
    snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
    ssize_t n = readlink(link, out, size - 1);
    if (n <= 0) return -1;
    out[n] = 0;
    return 0;
}

static long execveat_by_path(int dirfd, const char *path, char *const argv[], char *const envp[], int flags) {
    static long (*real)(long, ...);
    if (!real) real = (long (*)(long, ...))dlsym(RTLD_NEXT, "syscall");
    if (dirfd == AT_FDCWD && !(flags & AT_EMPTY_PATH)) return real(SYS_execveat, dirfd, path, argv, envp, flags);

    char target[4096];
    if (flags & AT_EMPTY_PATH && (!path || !*path)) {
        if (path_of_fd(dirfd, target, sizeof target) < 0) { errno = ENOENT; return -1; }
    } else if (path && path[0] == '/') {
        snprintf(target, sizeof target, "%s", path);
    } else if (path) {
        char base[4096];
        if (dirfd == AT_FDCWD) snprintf(target, sizeof target, "%s", path);
        else if (path_of_fd(dirfd, base, sizeof base) < 0) { errno = ENOENT; return -1; }
        else snprintf(target, sizeof target, "%s/%s", base, path);
    } else {
        errno = EFAULT;
        return -1;
    }
    return execve(target, argv, envp);
}

int execveat(int dirfd, const char *path, char *const argv[], char *const envp[], int flags) {
    return (int)execveat_by_path(dirfd, path, argv, envp, flags);
}

int fexecve(int fd, char *const argv[], char *const envp[]) {
    return (int)execveat_by_path(fd, "", argv, envp, AT_EMPTY_PATH);
}

/* FEX calls the system call through libc's syscall(): intercept that one number. */
long syscall(long number, ...) {
    static long (*real)(long, ...);
    if (!real) real = (long (*)(long, ...))dlsym(RTLD_NEXT, "syscall");
    va_list ap;
    va_start(ap, number);
    long a1 = va_arg(ap, long), a2 = va_arg(ap, long), a3 = va_arg(ap, long), a4 = va_arg(ap, long), a5 = va_arg(ap, long),
         a6 = va_arg(ap, long);
    va_end(ap);
    if (number == SYS_execveat)
        return execveat_by_path((int)a1, (const char *)a2, (char *const *)a3, (char *const *)a4, (int)a5);
    return real(number, a1, a2, a3, a4, a5, a6);
}

typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *, const posix_spawnattr_t *,
                        char *const[], char *const[]);

static int spawn_wrapped(spawn_fn real, int search, pid_t *pid, const char *file, const posix_spawn_file_actions_t *actions,
                         const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    const char *emulator = x86_emulator();
    if (emulator && file) {
        char buffer[4096];
        const char *found = search ? search_path(file, buffer, sizeof buffer) : file;
        if (found && is_x86_64(found)) {
            char **wrapped = with_emulator(emulator, found, argv);
            if (wrapped) {
                int result = real(pid, emulator, actions, attr, wrapped, envp);
                free(wrapped);
                return result;
            }
        }
    }
    return real(pid, file, actions, attr, argv, envp);
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions, const posix_spawnattr_t *attr,
                char *const argv[], char *const envp[]) {
    static spawn_fn real;
    if (!real) real = (spawn_fn)dlsym(RTLD_NEXT, "posix_spawn");
    return spawn_wrapped(real, 0, pid, path, actions, attr, argv, envp);
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *actions, const posix_spawnattr_t *attr,
                 char *const argv[], char *const envp[]) {
    static spawn_fn real;
    if (!real) real = (spawn_fn)dlsym(RTLD_NEXT, "posix_spawnp");
    return spawn_wrapped(real, 1, pid, file, actions, attr, argv, envp);
}
#endif /* __aarch64__ */
