#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <sys/vfs.h>
#include <sys/wait.h>
#include <unistd.h>

static int fails;
#define CHECK(c, msg) do { if (!(c)) { printf("FAIL: %s\n", msg); fails++; } else printf("ok:   %s\n", msg); } while (0)

static int exists(const char *p) { struct stat st; return stat(p, &st) == 0; }
static int listed(const char *dir, const char *name) {
    DIR *d = opendir(dir);
    if (!d) return -1;
    int found = 0;
    struct dirent *e;
    while ((e = readdir(d))) if (!strcmp(e->d_name, name)) found = 1;
    closedir(d);
    return found;
}
static int count(const char *dir) {
    DIR *d = opendir(dir);
    int n = 0;
    while (readdir(d)) n++;
    closedir(d);
    return n;
}
static void touch(const char *p) { int fd = open(p, O_CREAT | O_WRONLY, 0644); if (fd >= 0) close(fd); }

int main(void) {
    const char *R = "/data/local/tmp/fst";
    char a[128], b[128], c[128], sub[128];
    snprintf(a, sizeof a, "%s/a.txt", R);
    snprintf(b, sizeof b, "%s/b.txt", R);
    snprintf(c, sizeof c, "%s/c.txt", R);
    snprintf(sub, sizeof sub, "%s/sub", R);

    // 1. a cached negative answer must not hide a file created by this process
    CHECK(!exists(a), "a.txt absent (answer now cached)");
    CHECK(!exists(a), "a.txt absent again (from cache)");
    touch(a);
    CHECK(exists(a), "a.txt visible right after creation");

    // 2. listings
    CHECK(listed(R, "a.txt") == 1, "listing has a.txt");
    CHECK(listed(R, "b.txt") == 0, "listing lacks b.txt (cached)");
    touch(b);
    CHECK(listed(R, "b.txt") == 1, "listing has b.txt after creation");
    unlink(a);
    CHECK(listed(R, "a.txt") == 0, "listing lacks a.txt after unlink");
    CHECK(!exists(a), "a.txt gone after unlink");

    // 3. rename and mkdir
    CHECK(rename(b, c) == 0, "rename b -> c");
    CHECK(!exists(b) && exists(c), "b gone, c present after rename");
    CHECK(listed(R, "c.txt") == 1 && listed(R, "b.txt") == 0, "listing follows the rename");
    mkdir(sub, 0755);
    CHECK(exists(sub) && listed(R, "sub") == 1, "mkdir visible in stat and listing");
    rmdir(sub);
    CHECK(!exists(sub) && listed(R, "sub") == 0, "rmdir invisible in stat and listing");

    // 4. a file that has been written is never served stale
    struct stat st;
    int fd = open(c, O_WRONLY | O_TRUNC);
    CHECK(fd >= 0, "open c for writing");
    stat(c, &st);
    CHECK(st.st_size == 0, "size 0 before the write");
    write(fd, "hello world", 11);
    stat(c, &st);
    CHECK(st.st_size == 11, "size 11 after the write (file is dirty, not cached)");
    close(fd);

    // 5. another process changes the directory while this one holds cached answers
    char d[128];
    snprintf(d, sizeof d, "%s/from_child.txt", R);
    CHECK(!exists(d), "from_child.txt absent (cached)");
    int n0 = count(R);
    pid_t pid = fork();
    if (pid == 0) {
        touch(d);
        _exit(0);
    }
    waitpid(pid, NULL, 0);
    CHECK(exists(d), "file made by another process is seen (stat)");
    CHECK(listed(R, "from_child.txt") == 1 && count(R) == n0 + 1, "file made by another process is seen (listing)");

    // 6. paths outside the roots are untouched
    CHECK(exists("/system/bin/sh"), "stat outside the roots works");
    CHECK(listed("/system/bin", "sh") == 1, "listing outside the roots works");

    // 7. relative paths and "/./" forms
    if (chdir(R) == 0) {
        CHECK(exists("c.txt") && !exists("zzz.txt"), "relative paths");
        CHECK(listed(".", "c.txt") == 1, "opendir(\".\")");
    }
    // 8. copies of small read-only files (only with SKY_FSCACHE_COPY=1)
    if (getenv("SKY_FSCACHE_COPY")) {
        char f1[128], buf[64];
        snprintf(f1, sizeof f1, "%s/copyme.txt", R);
        fd = open(f1, O_CREAT | O_WRONLY | O_TRUNC, 0644);
        write(fd, "hello", 5);
        close(fd);
        struct stat s1, s2;
        stat(f1, &s1);
        memset(buf, 0, sizeof buf);
        fd = open(f1, O_RDONLY);
        CHECK(fd >= 0 && read(fd, buf, sizeof buf) == 5 && !strcmp(buf, "hello"), "read-only open returns the content");
        fstat(fd, &s2);
        CHECK(s2.st_size == s1.st_size && s2.st_mtim.tv_sec == s1.st_mtim.tv_sec && s2.st_mtim.tv_nsec == s1.st_mtim.tv_nsec,
              "size and mtime seen through the descriptor are the file's");
        close(fd);
        // a second open gives the same content
        memset(buf, 0, sizeof buf);
        fd = open(f1, O_RDONLY);
        CHECK(fd >= 0 && read(fd, buf, sizeof buf) == 5 && !strcmp(buf, "hello"), "second open returns the content");
        close(fd);
        // change the file: a later read-only open sees the change
        fd = open(f1, O_WRONLY | O_APPEND);
        write(fd, "!!", 2);
        close(fd);
        memset(buf, 0, sizeof buf);
        fd = open(f1, O_RDONLY);
        CHECK(fd >= 0 && read(fd, buf, sizeof buf) == 7 && !strcmp(buf, "hello!!"), "content after an append is current");
        close(fd);
        // replace it by a new file of another size through rename
        char f2[128];
        snprintf(f2, sizeof f2, "%s/new.txt", R);
        fd = open(f2, O_CREAT | O_WRONLY | O_TRUNC, 0644);
        write(fd, "brand new content", 17);
        close(fd);
        rename(f2, f1);
        memset(buf, 0, sizeof buf);
        fd = open(f1, O_RDONLY);
        CHECK(fd >= 0 && read(fd, buf, sizeof buf) == 17 && !strcmp(buf, "brand new content"), "content after a rename-over is current");
        close(fd);
        // too big: opened as it is
        char f3[128];
        snprintf(f3, sizeof f3, "%s/big.bin", R);
        fd = open(f3, O_CREAT | O_WRONLY | O_TRUNC, 0644);
        char chunk[65536];
        memset(chunk, 7, sizeof chunk);
        for (int i = 0; i < 80; i++) write(fd, chunk, sizeof chunk);   // 5 MB
        close(fd);
        fd = open(f3, O_RDONLY);
        CHECK(fd >= 0 && lseek(fd, 0, SEEK_END) == 80 * 65536, "a file above the limit is still readable");
        close(fd);
        // a directory and a missing file are not touched
        fd = open(R, O_RDONLY | O_DIRECTORY);
        CHECK(fd >= 0, "directory opens");
        close(fd);
        CHECK(open("/data/local/tmp/fst/nope", O_RDONLY) < 0 && errno == ENOENT, "missing file fails with ENOENT");
    }
    // 9. the case-sensitivity probe of Wine (only with SKY_FSCACHE_PROBE=1): open the directory, FS_IOC_GETFLAGS, fstatfs,
    //    ".ciopfs", close; the answers must be the same every time and the descriptor must be a working directory descriptor
    if (getenv("SKY_FSCACHE_PROBE")) {
        char pd[128];
        snprintf(pd, sizeof pd, "%s/probedir", R);
        mkdir(pd, 0755);
        int first_ioctl = 0, first_errno = 0;
        long first_type = 0;
        for (int round = 0; round < 4; round++) {
            int pfd = open(pd, O_RDONLY | O_NONBLOCK);
            struct stat ps;
            int flags = 0;
            struct statfs sfs;
            CHECK(pfd >= 0 && fstat(pfd, &ps) == 0 && S_ISDIR(ps.st_mode), "probe descriptor is a directory");
            int ir = ioctl(pfd, 0x80086601, &flags);
            int ie = errno;
            int fr = fstatfs(pfd, &sfs);
            struct stat cs;
            int cr = fstatat(pfd, ".ciopfs", &cs, 0);
            int ce = errno;
            CHECK(fr == 0 && cr == -1 && ce == ENOENT, "fstatfs works, .ciopfs absent");
            if (round == 0) { first_ioctl = ir; first_errno = ie; first_type = (long)sfs.f_type; }
            else CHECK(ir == first_ioctl && (ir == 0 || ie == first_errno) && (long)sfs.f_type == first_type, "same answers on a repeat");
            close(pfd);
        }
        // the directory is replaced: the probe must see the new one
        rmdir(pd);
        CHECK(!exists(pd), "probe directory removed");
        mkdir(pd, 0755);
        int pfd = open(pd, O_RDONLY | O_NONBLOCK);
        struct stat ps1, ps2;
        CHECK(pfd >= 0 && fstat(pfd, &ps1) == 0 && stat(pd, &ps2) == 0 && ps1.st_ino == ps2.st_ino,
              "after rmdir + mkdir the probe descriptor is the new directory");
        close(pfd);
        // opening a directory with other flags is untouched, and a file is not a directory
        pfd = open(pd, O_RDONLY | O_DIRECTORY);
        CHECK(pfd >= 0, "open with O_DIRECTORY works");
        close(pfd);
        pfd = open(c, O_RDONLY | O_NONBLOCK);
        CHECK(pfd >= 0, "open of a file with O_NONBLOCK works");
        close(pfd);
        // two descriptors of one directory give two independent read positions
        char dd[128], nm[160];
        snprintf(dd, sizeof dd, "%s/twodirs", R);
        mkdir(dd, 0755);
        for (int i = 0; i < 6; i++) { snprintf(nm, sizeof nm, "%s/f%d", dd, i); touch(nm); }
        int fa = open(dd, O_RDONLY), fb = open(dd, O_RDONLY | O_DIRECTORY);
        DIR *da = fdopendir(fa), *db = fdopendir(fb);
        int na = 0, nb = 0;
        for (int i = 0; i < 3; i++) if (readdir(da)) na++;
        while (readdir(db)) nb++;
        while (readdir(da)) na++;
        CHECK(da && db && na == 8 && nb == 8, "two streams over one directory both read all 8 entries");
        closedir(da);
        closedir(db);
        int fc = open(dd, O_RDONLY);
        CHECK(fc >= 0 && fchdir(fc) == 0, "fchdir on a directory descriptor works");
        close(fc);
        chdir(R);
    }
    printf("%s (%d failures)\n", fails ? "FAILED" : "ALL PASSED", fails);
    return fails != 0;
}
