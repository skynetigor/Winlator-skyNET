/*
 * skyshim: a small LD_PRELOAD library for the Winlator skyNET Linux runtime (glibc aarch64, run under proot on Android).
 *
 * Android's app sandbox refuses a netlink "kobject uevent" socket, which libudev opens to watch for devices. SDL's
 * joystick code treats that failure as fatal ("Could not initialize UDEV") and SDL_Init fails, so games that use SDL
 * cannot start. The shim answers that one socket with a harmless local socket: there are never any device events here.
 *
 * Interposed: socket, bind, getsockname, setsockopt, getsockopt, close. Everything else goes to libc unchanged.
 *
 * MIT licence, written for this project.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
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
    return real(fd);
}
