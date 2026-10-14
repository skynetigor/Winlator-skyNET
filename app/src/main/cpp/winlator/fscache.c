// Preloaded libc shim for Wine processes (ARM64, loaded through LD_PRELOAD like libfakeinput).
//
// Wine asks the file system the same questions again and again (is there a file called X in this directory, list this
// directory). On shared storage (Android's FUSE layer) every one of them is a round trip to a user-space daemon, which is
// what makes games that read a lot of small files load slowly. This library answers repeated questions from memory for the
// directories of the game it is told about (the "roots") and passes everything else through.
//
// What is cached, for paths under a root only:
//   * stat / lstat / fstatat results, including "no such file" answers
//   * directory listings: opendir / fdopendir + readdir (a cached directory is handed out as a private DIR object)
// How stale answers are avoided:
//   * every process that has this library loaded (Wine's client processes and wineserver) reports its own changes to the
//     file system (create, write-open, unlink, rename, mkdir, rmdir, ...) in a small memory-mapped file shared by all of them;
//     an entry is dropped when its directory's counter has moved, and a file that has been opened for writing is never cached
//   * entries also expire after a few seconds, which bounds the damage of a change made behind the library's back
//
// Environment (set by the app, see GuestProgramLauncherComponent):
//   SKY_FSCACHE=1             turn the cache on
//   SKY_FSCACHE_STATS=1       count the metadata calls and their time, write a summary to the log directory
//   SKY_FSCACHE_ROOTS=a:b     absolute directories to cache (the game folder)
//   SKY_FSCACHE_SHM=file      shared change counters (created by the app; without it only this process's changes are seen)
//   SKY_FSCACHE_LOG_DIR=dir   where fscache_<pid>.txt goes when SKY_FSCACHE_STATS=1
//   WINEPREFIX                used to map the Wine drive letters (dosdevices/d: ...) back to real directories

#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/xattr.h>
#include <time.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default")))

// ---------------------------------------------------------------------------------------------------------------------
// Counters (SKY_FSCACHE_STATS)
// ---------------------------------------------------------------------------------------------------------------------

enum { FN_STAT, FN_LSTAT, FN_FSTATAT, FN_STATX, FN_GETXATTR, FN_LGETXATTR, FN_OPENDIR, FN_FDOPENDIR, FN_READDIR, FN_CLOSEDIR,
       FN_ACCESS, FN_FACCESSAT, FN_OPEN, FN_READ, FN_COUNT };
static const char *const FN_NAMES[FN_COUNT] = {
    "stat", "lstat", "fstatat", "statx", "getxattr", "lgetxattr", "opendir", "fdopendir", "readdir", "closedir", "access",
    "faccessat", "open", "read"
};
enum { CLS_ROOT, CLS_OTHER, CLS_UNKNOWN, CLS_COUNT };
static const char *const CLS_NAMES[CLS_COUNT] = { "root", "other", "unknown" };

typedef struct {
    _Atomic uint64_t calls;
    _Atomic uint64_t ns;
    _Atomic uint64_t failed;
    _Atomic uint64_t hits;     // answered from the cache (no time counted)
    _Atomic uint64_t bytes;    // read: bytes returned
} counter_t;

static counter_t g_counters[FN_COUNT][CLS_COUNT];
static int g_stats;     // SKY_FSCACHE_STATS
static int g_cache;     // SKY_FSCACHE
static int g_enabled;   // either
static char g_log_dir[512];
static uint64_t g_start_ns;
static _Atomic uint64_t g_last_dump_ns;
static __thread int g_depth;   // >0 while inside a real libc call made by a wrapper: nested interposed calls pass through

static char g_roots[16][512];
static size_t g_root_len[16];
static int g_root_count;

static char g_dosdev[512];          // $WINEPREFIX/dosdevices
static size_t g_dosdev_len;
static char g_dd_target[26][512];   // real directory behind each drive letter, "" when there is none

static uint64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static uint64_t hash_str(const char *p) {
    uint64_t h = 1469598103934665603ull;
    for (; *p; p++) { h ^= (unsigned char)*p; h *= 1099511628211ull; }
    return h | 1;
}

// ---------------------------------------------------------------------------------------------------------------------
// Shared change counters
// ---------------------------------------------------------------------------------------------------------------------

#define GEN_SLOTS 1024
#define DIRTY_SLOTS 8192
typedef struct {
    _Atomic uint32_t gen[GEN_SLOTS];       // bumped when something in (or of) a directory changes; indexed by hash of the path
    _Atomic uint64_t dirty[DIRTY_SLOTS];   // hashes of files that have been opened for writing (never cached again)
} shm_t;
#define SHM_BYTES (sizeof(shm_t))
static shm_t *g_shm;
static shm_t g_local_shm;

static shm_t *shm(void) { return g_shm ? g_shm : &g_local_shm; }
static uint32_t gen_get(uint64_t h) { return atomic_load_explicit(&shm()->gen[h % GEN_SLOTS], memory_order_acquire); }
static void gen_bump(uint64_t h) { atomic_fetch_add_explicit(&shm()->gen[h % GEN_SLOTS], 1, memory_order_acq_rel); }

static int dirty_has(uint64_t h) {
    shm_t *s = shm();
    for (uint32_t i = (uint32_t)(h >> 7) % DIRTY_SLOTS, n = 0; n < 64; i = (i + 1) % DIRTY_SLOTS, n++) {
        uint64_t cur = atomic_load_explicit(&s->dirty[i], memory_order_acquire);
        if (cur == h) return 1;
        if (cur == 0) return 0;
    }
    return 1;   // a full neighbourhood: be careful and treat the file as dirty
}

static void dirty_add(uint64_t h) {
    shm_t *s = shm();
    for (uint32_t i = (uint32_t)(h >> 7) % DIRTY_SLOTS, n = 0; n < 64; i = (i + 1) % DIRTY_SLOTS, n++) {
        uint64_t expected = 0;
        if (atomic_compare_exchange_strong(&s->dirty[i], &expected, h)) return;
        if (expected == h) return;
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Paths
// ---------------------------------------------------------------------------------------------------------------------

// Absolute path for (dirfd, path): relative paths are joined to the directory the descriptor (or the working directory)
// points at. NULL when it cannot be worked out.
static const char *resolve(int dirfd, const char *path, char *out, size_t n) {
    if (!path) return NULL;
    if (path[0] == '/') return path;
    char link[48];
    if (dirfd == AT_FDCWD) snprintf(link, sizeof link, "/proc/self/cwd");
    else snprintf(link, sizeof link, "/proc/self/fd/%d", dirfd);
    char base[1024];
    ssize_t r = readlink(link, base, sizeof base - 1);
    if (r <= 0) return NULL;
    base[r] = 0;
    if (path[0] == 0 || (path[0] == '.' && path[1] == 0)) snprintf(out, n, "%s", base);
    else snprintf(out, n, "%s/%s", base, path);
    return out;
}

// Canonical form used as the cache key: "//", "/./" and "/../" removed, and the Wine drive-letter links replaced by the
// directories they point at, so that dosdevices/d:/Games/X and /storage/emulated/0/Download/Games/X are the same key.
static int canonicalize(const char *full, char *out, size_t n) {
    if (!full || full[0] != '/') return -1;
    char tmp[1100];
    size_t len = 0;
    const char *p = full;
    while (*p) {
        while (*p == '/') p++;
        if (!*p) break;
        const char *e = p;
        while (*e && *e != '/') e++;
        size_t cl = (size_t)(e - p);
        if (cl == 1 && p[0] == '.') {
            // skip
        } else if (cl == 2 && p[0] == '.' && p[1] == '.') {
            while (len > 0 && tmp[len - 1] != '/') len--;
            if (len > 0) len--;
        } else {
            if (len + 1 + cl >= sizeof tmp) return -1;
            tmp[len++] = '/';
            memcpy(tmp + len, p, cl);
            len += cl;
        }
        p = e;
    }
    if (len == 0) tmp[len++] = '/';
    tmp[len] = 0;

    if (g_dosdev_len && len > g_dosdev_len + 2 && strncmp(tmp, g_dosdev, g_dosdev_len) == 0 && tmp[g_dosdev_len] == '/') {
        char letter = tmp[g_dosdev_len + 1];
        if (letter >= 'a' && letter <= 'z' && tmp[g_dosdev_len + 2] == ':' &&
            (tmp[g_dosdev_len + 3] == '/' || tmp[g_dosdev_len + 3] == 0) && g_dd_target[letter - 'a'][0]) {
            const char *rest = tmp + g_dosdev_len + 3;
            int w = snprintf(out, n, "%s%s", g_dd_target[letter - 'a'], rest);
            return (w > 0 && (size_t)w < n) ? 0 : -1;
        }
    }
    if (len + 1 > n) return -1;
    memcpy(out, tmp, len + 1);
    return 0;
}

static int in_root(const char *canon) {
    for (int i = 0; i < g_root_count; i++) {
        if (strncmp(canon, g_roots[i], g_root_len[i]) == 0 && (canon[g_root_len[i]] == '/' || canon[g_root_len[i]] == 0))
            return 1;
    }
    return 0;
}

static uint64_t parent_hash(const char *canon) {
    char tmp[1100];
    size_t len = strlen(canon);
    if (len >= sizeof tmp) return 1;
    memcpy(tmp, canon, len + 1);
    char *slash = strrchr(tmp, '/');
    if (!slash) return 1;
    if (slash == tmp) tmp[1] = 0;
    else *slash = 0;
    return hash_str(tmp);
}

// ---------------------------------------------------------------------------------------------------------------------
// The cache
// ---------------------------------------------------------------------------------------------------------------------

#define TTL_POS_NS (30ull * 1000000000ull)
#define TTL_NEG_NS (5ull * 1000000000ull)
#define TTL_DIR_NS (10ull * 1000000000ull)
#define MEM_CAP (256ull << 20)

typedef struct { uint64_t ino; uint32_t off; uint16_t nlen; uint8_t type; } dl_ent;
typedef struct {
    _Atomic int ref;
    uint32_t count, cap;
    dl_ent *ents;
    char *blob;
    size_t blob_len, blob_cap;
} dlist;

typedef struct centry {
    struct centry *next;
    uint64_t h;
    char *path;
    uint8_t kind;          // 0 stat, 1 lstat, 2 directory listing
    int err;               // stat kinds: 0 or the errno of a failure that is worth remembering
    uint32_t gen;
    uint64_t t_ns;
    struct stat st;
    dlist *dl;
} centry;

#define NBUCKET (1u << 16)
#define NSTRIPE 64
static centry *g_buckets[NBUCKET];
static pthread_mutex_t g_stripes[NSTRIPE] = { PTHREAD_MUTEX_INITIALIZER };
static _Atomic uint64_t g_mem;
static _Atomic uint64_t g_cache_hits, g_cache_misses, g_cache_stores;

static pthread_mutex_t *stripe_for(uint64_t h) { return &g_stripes[(h >> 40) % NSTRIPE]; }

static void dlist_unref(dlist *d) {
    if (!d) return;
    if (atomic_fetch_sub(&d->ref, 1) == 1) {
        atomic_fetch_sub(&g_mem, d->cap * sizeof(dl_ent) + d->blob_cap + sizeof(dlist));
        free(d->ents);
        free(d->blob);
        free(d);
    }
}

static dlist *dlist_new(void) {
    dlist *d = (dlist *)calloc(1, sizeof(dlist));
    if (d) {
        atomic_init(&d->ref, 1);
        atomic_fetch_add(&g_mem, sizeof(dlist));
    }
    return d;
}

static int dlist_add(dlist *d, const char *name, size_t nlen, uint64_t ino, uint8_t type) {
    if (d->count == d->cap) {
        uint32_t ncap = d->cap ? d->cap * 2 : 64;
        dl_ent *ne = (dl_ent *)realloc(d->ents, ncap * sizeof(dl_ent));
        if (!ne) return -1;
        atomic_fetch_add(&g_mem, (uint64_t)(ncap - d->cap) * sizeof(dl_ent));
        d->ents = ne;
        d->cap = ncap;
    }
    if (d->blob_len + nlen + 1 > d->blob_cap) {
        size_t nb = d->blob_cap ? d->blob_cap * 2 : 2048;
        while (nb < d->blob_len + nlen + 1) nb *= 2;
        char *nbuf = (char *)realloc(d->blob, nb);
        if (!nbuf) return -1;
        atomic_fetch_add(&g_mem, nb - d->blob_cap);
        d->blob = nbuf;
        d->blob_cap = nb;
    }
    dl_ent *e = &d->ents[d->count++];
    e->ino = ino;
    e->type = type;
    e->nlen = (uint16_t)nlen;
    e->off = (uint32_t)d->blob_len;
    memcpy(d->blob + d->blob_len, name, nlen);
    d->blob[d->blob_len + nlen] = 0;
    d->blob_len += nlen + 1;
    return 0;
}

static centry *find_locked(uint64_t h, uint8_t kind, const char *path) {
    for (centry *e = g_buckets[h % NBUCKET]; e; e = e->next)
        if (e->h == h && e->kind == kind && strcmp(e->path, path) == 0) return e;
    return NULL;
}

static int entry_valid(const centry *e, uint64_t now) {
    uint64_t ttl = e->kind == 2 ? TTL_DIR_NS : (e->err ? TTL_NEG_NS : TTL_POS_NS);
    if (now - e->t_ns > ttl) return 0;
    return e->gen == gen_get(e->kind == 2 ? e->h : parent_hash(e->path));
}

// Cached stat answer. 1 = hit (st filled when the answer is a success), 0 = miss.
static int cache_get_stat(uint8_t kind, const char *canon, uint64_t h, struct stat *st, int *err) {
    uint64_t now = now_ns();
    int hit = 0;
    pthread_mutex_t *m = stripe_for(h);
    pthread_mutex_lock(m);
    centry *e = find_locked(h, kind, canon);
    if (e && entry_valid(e, now)) {
        *err = e->err;
        if (!e->err) *st = e->st;
        hit = 1;
    }
    pthread_mutex_unlock(m);
    return hit;
}

// `gen` is the directory counter read before the real call: a change that happens during the call makes the entry stale at once.
static void cache_put_stat(uint8_t kind, const char *canon, uint64_t h, int r, int err, const struct stat *st, uint32_t gen) {
    if (r != 0 && err != ENOENT && err != ENOTDIR) return;   // other failures (permissions, I/O) are not remembered
    if (atomic_load(&g_mem) > MEM_CAP) return;
    pthread_mutex_t *m = stripe_for(h);
    pthread_mutex_lock(m);
    centry *e = find_locked(h, kind, canon);
    if (!e) {
        e = (centry *)calloc(1, sizeof(centry));
        char *p = e ? strdup(canon) : NULL;
        if (!e || !p) { free(e); free(p); pthread_mutex_unlock(m); return; }
        e->h = h;
        e->kind = kind;
        e->path = p;
        e->next = g_buckets[h % NBUCKET];
        g_buckets[h % NBUCKET] = e;
        atomic_fetch_add(&g_mem, sizeof(centry) + strlen(canon) + 1);
    }
    e->err = r == 0 ? 0 : err;
    if (r == 0) e->st = *st;
    e->gen = gen;
    e->t_ns = now_ns();
    pthread_mutex_unlock(m);
    atomic_fetch_add(&g_cache_stores, 1);
}

static dlist *cache_get_dir(const char *canon, uint64_t h) {
    uint64_t now = now_ns();
    dlist *d = NULL;
    pthread_mutex_t *m = stripe_for(h);
    pthread_mutex_lock(m);
    centry *e = find_locked(h, 2, canon);
    if (e && e->dl && entry_valid(e, now)) {
        d = e->dl;
        atomic_fetch_add(&d->ref, 1);
    }
    pthread_mutex_unlock(m);
    return d;
}

// Takes over the builder's reference.
static void cache_put_dir(const char *canon, uint64_t h, dlist *d, uint32_t gen_at_start) {
    if (atomic_load(&g_mem) > MEM_CAP) { dlist_unref(d); return; }
    pthread_mutex_t *m = stripe_for(h);
    pthread_mutex_lock(m);
    centry *e = find_locked(h, 2, canon);
    if (!e) {
        e = (centry *)calloc(1, sizeof(centry));
        char *p = e ? strdup(canon) : NULL;
        if (!e || !p) { free(e); free(p); pthread_mutex_unlock(m); dlist_unref(d); return; }
        e->h = h;
        e->kind = 2;
        e->path = p;
        e->next = g_buckets[h % NBUCKET];
        g_buckets[h % NBUCKET] = e;
        atomic_fetch_add(&g_mem, sizeof(centry) + strlen(canon) + 1);
    }
    dlist *old = e->dl;
    e->dl = d;
    e->err = 0;
    e->gen = gen_at_start;   // a change that happened while the listing was being read makes it stale at once
    e->t_ns = now_ns();
    pthread_mutex_unlock(m);
    dlist_unref(old);
    atomic_fetch_add(&g_cache_stores, 1);
}

// ---------------------------------------------------------------------------------------------------------------------
// Per-path tables for the statistics (busiest paths and directories)
// ---------------------------------------------------------------------------------------------------------------------

typedef struct {
    _Atomic uint64_t h;
    char *_Atomic path;
    _Atomic uint64_t calls;
    _Atomic uint64_t ns;
    _Atomic uint64_t opens;
    _Atomic int cls;
} pent_t;
#define PT_SIZE (1u << 16)
static pent_t *g_stat_tab;
static pent_t *g_dir_tab;

static pent_t *pt_get(pent_t *tab, const char *path, int cls) {
    if (!tab || !path) return NULL;
    uint64_t h = hash_str(path);
    uint32_t i = (uint32_t)(h >> 16) & (PT_SIZE - 1);
    for (int probe = 0; probe < 64; probe++, i = (i + 1) & (PT_SIZE - 1)) {
        uint64_t cur = atomic_load_explicit(&tab[i].h, memory_order_acquire);
        if (cur == h) return &tab[i];
        if (cur == 0) {
            uint64_t expected = 0;
            if (atomic_compare_exchange_strong(&tab[i].h, &expected, h)) {
                atomic_store(&tab[i].cls, cls);
                atomic_store(&tab[i].path, strdup(path));
                return &tab[i];
            }
            if (expected == h) return &tab[i];
        }
    }
    return NULL;
}

// Open DIR*: what it is, so that readdir / closedir know what to do.
enum { DK_REAL, DK_RECORDING, DK_FAKE };
typedef struct {
    int kind;
    int cls;
    pent_t *ent;           // statistics row
    // DK_RECORDING: the listing being collected from the real directory
    char *canon;
    uint64_t h;
    uint32_t gen_at_start;
    dlist *builder;
    // DK_FAKE: a cached listing handed out as a DIR
    dlist *dl;
    uint32_t idx;
    int fd;                // descriptor given to fdopendir (closed with the directory), or -1
    struct dirent de;
} dstate;

#define DIRTAB_SIZE 16384
static struct { _Atomic uintptr_t key; _Atomic(dstate *) st; } g_dirtab[DIRTAB_SIZE];

static int dirtab_put(void *d, dstate *s) {
    uintptr_t key = (uintptr_t)d;
    for (uint32_t i = (uint32_t)(key >> 4) & (DIRTAB_SIZE - 1), n = 0; n < 32; i = (i + 1) & (DIRTAB_SIZE - 1), n++) {
        uintptr_t expected = 0;
        if (atomic_compare_exchange_strong(&g_dirtab[i].key, &expected, key)) {
            atomic_store(&g_dirtab[i].st, s);
            return 0;
        }
    }
    return -1;
}

static dstate *dirtab_get(void *d, int remove) {
    uintptr_t key = (uintptr_t)d;
    for (uint32_t i = (uint32_t)(key >> 4) & (DIRTAB_SIZE - 1), n = 0; n < 32; i = (i + 1) & (DIRTAB_SIZE - 1), n++) {
        if (atomic_load(&g_dirtab[i].key) == key) {
            dstate *s = atomic_load(&g_dirtab[i].st);
            if (remove) atomic_store(&g_dirtab[i].key, 0);
            return s;
        }
    }
    return NULL;
}

// ---------------------------------------------------------------------------------------------------------------------
// Statistics output
// ---------------------------------------------------------------------------------------------------------------------

static void dump(const char *why) {
    if (!g_stats || g_log_dir[0] == 0) return;
    uint64_t total = 0;
    for (int f = 0; f < FN_COUNT; f++)
        for (int c = 0; c < CLS_COUNT; c++)
            total += atomic_load(&g_counters[f][c].calls) + atomic_load(&g_counters[f][c].hits);
    if (total < 500) return;   // short-lived helper processes: no file

    static char buf[40000];
    int n = 0;
    char comm[64] = "?";
    FILE *cf = fopen("/proc/self/comm", "r");
    if (cf) { if (fgets(comm, sizeof comm, cf)) comm[strcspn(comm, "\n")] = 0; fclose(cf); }

    n += snprintf(buf + n, sizeof buf - n, "pid=%d comm=%s uptime=%.1fs reason=%s cache=%d roots=", (int)getpid(), comm,
                  (double)(now_ns() - g_start_ns) / 1e9, why, g_cache);
    for (int i = 0; i < g_root_count; i++) n += snprintf(buf + n, sizeof buf - n, "%s%s", i ? ":" : "", g_roots[i]);
    n += snprintf(buf + n, sizeof buf - n, "\ncache: hits=%llu misses=%llu stores=%llu mem=%.1fMB shm=%s\n",
                  (unsigned long long)atomic_load(&g_cache_hits), (unsigned long long)atomic_load(&g_cache_misses),
                  (unsigned long long)atomic_load(&g_cache_stores), (double)atomic_load(&g_mem) / 1048576.0,
                  g_shm ? "yes" : "no");
    n += snprintf(buf + n, sizeof buf - n, "%-10s %-8s %10s %10s %9s %9s %10s %9s\n", "function", "class", "calls", "total_ms",
                  "avg_us", "failed", "cache_hits", "MB");
    for (int f = 0; f < FN_COUNT; f++) {
        for (int c = 0; c < CLS_COUNT; c++) {
            counter_t *k = &g_counters[f][c];
            uint64_t calls = atomic_load(&k->calls);
            uint64_t hits = atomic_load(&k->hits);
            if (!calls && !hits) continue;
            uint64_t ns = atomic_load(&k->ns);
            n += snprintf(buf + n, sizeof buf - n, "%-10s %-8s %10llu %10.1f %9.1f %9llu %10llu %9.1f\n", FN_NAMES[f], CLS_NAMES[c],
                          (unsigned long long)calls, (double)ns / 1e6, calls ? (double)ns / 1e3 / (double)calls : 0.0,
                          (unsigned long long)atomic_load(&k->failed), (unsigned long long)hits,
                          (double)atomic_load(&k->bytes) / 1048576.0);
        }
    }

    for (int pass = 0; pass < 2; pass++) {
        pent_t *tab = pass ? g_dir_tab : g_stat_tab;
        if (!tab) continue;
        n += snprintf(buf + n, sizeof buf - n, pass ? "\ntop directories by readdir time (real calls only):\n  opens  entries     ms  class path\n"
                                                    : "\ntop stat-like paths by real calls:\n    calls     ms  class path\n");
        uint64_t last_key = UINT64_MAX;
        uint32_t last_idx = 0;
        for (int rank = 0; rank < 20; rank++) {
            uint64_t best = 0;
            int best_i = -1;
            for (uint32_t i = 0; i < PT_SIZE; i++) {
                if (!atomic_load(&tab[i].h) || !atomic_load(&tab[i].path)) continue;
                uint64_t key = pass ? atomic_load(&tab[i].ns) : atomic_load(&tab[i].calls);
                if (key > last_key || (key == last_key && i <= last_idx)) continue;
                if (best_i < 0 || key > best) { best = key; best_i = (int)i; }
            }
            if (best_i < 0) break;
            pent_t *e = &tab[best_i];
            const char *p = atomic_load(&e->path);
            if (pass)
                n += snprintf(buf + n, sizeof buf - n, "%7llu %8llu %7.1f  %-5s %.200s\n", (unsigned long long)atomic_load(&e->opens),
                              (unsigned long long)atomic_load(&e->calls), (double)atomic_load(&e->ns) / 1e6,
                              CLS_NAMES[atomic_load(&e->cls)], p);
            else
                n += snprintf(buf + n, sizeof buf - n, "%9llu %6.1f  %-5s %.200s\n", (unsigned long long)atomic_load(&e->calls),
                              (double)atomic_load(&e->ns) / 1e6, CLS_NAMES[atomic_load(&e->cls)], p);
            last_key = best;
            last_idx = (uint32_t)best_i;
            if (n > (int)sizeof buf - 400) break;
        }
    }

    char path[640];
    snprintf(path, sizeof path, "%s/fscache_%d.txt", g_log_dir, (int)getpid());
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        ssize_t w = write(fd, buf, (size_t)n);
        (void)w;
        close(fd);
    }
}

static void maybe_dump(uint64_t now) {
    if (!g_stats) return;
    uint64_t last = atomic_load_explicit(&g_last_dump_ns, memory_order_relaxed);
    if (now - last < 5000000000ull) return;
    if (atomic_compare_exchange_strong(&g_last_dump_ns, &last, now)) {
        g_depth++;
        dump("periodic");
        g_depth--;
    }
}

static int class_of(const char *canon) { return !canon ? CLS_UNKNOWN : in_root(canon) ? CLS_ROOT : CLS_OTHER; }

// Counts one real call (and, for stat-like calls, the busiest-path table).
static void record(int fn, int cls, uint64_t t0, uint64_t t1, int failed, const char *path_for_table) {
    if (!g_stats) return;
    counter_t *k = &g_counters[fn][cls];
    atomic_fetch_add_explicit(&k->calls, 1, memory_order_relaxed);
    atomic_fetch_add_explicit(&k->ns, t1 - t0, memory_order_relaxed);
    if (failed) atomic_fetch_add_explicit(&k->failed, 1, memory_order_relaxed);
    if (path_for_table) {
        pent_t *e = pt_get(g_stat_tab, path_for_table, cls);
        if (e) {
            atomic_fetch_add_explicit(&e->calls, 1, memory_order_relaxed);
            atomic_fetch_add_explicit(&e->ns, t1 - t0, memory_order_relaxed);
        }
    }
    maybe_dump(t1);
}

// Which open descriptors belong to a root (statistics only): read time is attributed through this.
#define FDTAB_SIZE 8192
static _Atomic uint8_t g_fdcls[FDTAB_SIZE];   // 0 unknown, 1 root, 2 other

static void count_hit(int fn, int cls) {
    atomic_fetch_add_explicit(&g_cache_hits, 1, memory_order_relaxed);
    if (g_stats) atomic_fetch_add_explicit(&g_counters[fn][cls].hits, 1, memory_order_relaxed);
}

// ---------------------------------------------------------------------------------------------------------------------
// Real functions
// ---------------------------------------------------------------------------------------------------------------------

#define LOAD(var, name) do { if (!(var)) *(void **)&(var) = dlsym(RTLD_NEXT, name); } while (0)

static int (*real_stat)(const char *, struct stat *);
static int (*real_lstat)(const char *, struct stat *);
static int (*real_fstatat)(int, const char *, struct stat *, int);
static int (*real_statx)(int, const char *, int, unsigned int, void *);
static ssize_t (*real_getxattr)(const char *, const char *, void *, size_t);
static ssize_t (*real_lgetxattr)(const char *, const char *, void *, size_t);
static DIR *(*real_opendir)(const char *);
static DIR *(*real_fdopendir)(int);
static struct dirent *(*real_readdir)(DIR *);
static struct dirent64 *(*real_readdir64)(DIR *);
static int (*real_closedir)(DIR *);
static void (*real_rewinddir)(DIR *);
static long (*real_telldir)(DIR *);
static void (*real_seekdir)(DIR *, long);
static int (*real_dirfd)(DIR *);
static int (*real_access)(const char *, int);
static int (*real_faccessat)(int, const char *, int, int);
static ssize_t (*real_read)(int, void *, size_t);
static ssize_t (*real_pread)(int, void *, size_t, off_t);
static int (*real_close)(int);
static int (*real_open)(const char *, int, ...);
static int (*real_openat)(int, const char *, int, ...);
static int (*real_unlink)(const char *);
static int (*real_unlinkat)(int, const char *, int);
static int (*real_rmdir)(const char *);
static int (*real_mkdir)(const char *, mode_t);
static int (*real_mkdirat)(int, const char *, mode_t);
static int (*real_rename)(const char *, const char *);
static int (*real_renameat)(int, const char *, int, const char *);
static int (*real_symlink)(const char *, const char *);
static int (*real_link)(const char *, const char *);
static int (*real_truncate)(const char *, off_t);

static void read_dosdevices(void) {
    const char *prefix = getenv("WINEPREFIX");
    if (!prefix || strlen(prefix) + 12 >= sizeof g_dosdev) return;
    snprintf(g_dosdev, sizeof g_dosdev, "%s/dosdevices", prefix);
    g_dosdev_len = strlen(g_dosdev);
    for (int i = 0; i < 26; i++) {
        char link[560], real[PATH_MAX];
        snprintf(link, sizeof link, "%s/%c:", g_dosdev, 'a' + i);
        if (realpath(link, real) && strlen(real) < sizeof g_dd_target[0]) strcpy(g_dd_target[i], real);
    }
}

static void open_shm(const char *path) {
    int fd = open(path, O_RDWR | O_CLOEXEC);
    if (fd < 0) return;
    struct stat st;
    if (fstat(fd, &st) == 0 && (size_t)st.st_size >= SHM_BYTES) {
        void *m = mmap(NULL, SHM_BYTES, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (m != MAP_FAILED) g_shm = (shm_t *)m;
    }
    close(fd);
}

__attribute__((constructor)) static void fscache_init(void) {
    const char *s = getenv("SKY_FSCACHE_STATS");
    const char *c = getenv("SKY_FSCACHE");
    g_stats = s && s[0] == '1';
    g_cache = c && c[0] == '1';
    g_enabled = g_stats || g_cache;
    if (!g_enabled) return;
    g_start_ns = now_ns();
    atomic_store(&g_last_dump_ns, g_start_ns);
    const char *roots = getenv("SKY_FSCACHE_ROOTS");
    if (roots) {
        char tmp[8192];
        strncpy(tmp, roots, sizeof tmp - 1);
        tmp[sizeof tmp - 1] = 0;
        for (char *tok = strtok(tmp, ":"); tok && g_root_count < 16; tok = strtok(NULL, ":")) {
            size_t len = strlen(tok);
            while (len > 1 && tok[len - 1] == '/') tok[--len] = 0;
            if (len == 0 || len >= sizeof g_roots[0]) continue;
            memcpy(g_roots[g_root_count], tok, len + 1);
            g_root_len[g_root_count++] = len;
        }
    }
    const char *dir = getenv("SKY_FSCACHE_LOG_DIR");
    if (dir) { strncpy(g_log_dir, dir, sizeof g_log_dir - 1); g_log_dir[sizeof g_log_dir - 1] = 0; }
    read_dosdevices();
    const char *shm_path = getenv("SKY_FSCACHE_SHM");
    if (g_cache && shm_path) {
        g_depth++;
        open_shm(shm_path);
        g_depth--;
    }
    if (g_stats) {
        g_stat_tab = (pent_t *)calloc(PT_SIZE, sizeof(pent_t));
        g_dir_tab = (pent_t *)calloc(PT_SIZE, sizeof(pent_t));
    }
}

__attribute__((destructor)) static void fscache_fini(void) {
    if (g_stats) {
        g_depth++;
        dump("exit");
        g_depth--;
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// stat family
// ---------------------------------------------------------------------------------------------------------------------

// stat and lstat must go to the *next* stat / lstat in the preload chain, not to fstatat: libfakeinput.so (after us) rewrites
// /dev/input paths in its stat to fake a gamepad, and skipping it would hide the pad from the game.
static int call_real(int fn, int dirfd, const char *path, struct stat *st, int flags) {
    if (fn == FN_STAT) return real_stat(path, st);
    if (fn == FN_LSTAT) return real_lstat(path, st);
    return real_fstatat(dirfd, path, st, flags);
}

static int do_stat(int fn, int dirfd, const char *path, struct stat *st, int flags) {
    LOAD(real_stat, "stat");
    LOAD(real_lstat, "lstat");
    LOAD(real_fstatat, "fstatat");
    if (!g_enabled || g_depth || !path || ((flags & AT_EMPTY_PATH) && path[0] == 0)) return call_real(fn, dirfd, path, st, flags);

    char rbuf[1100], cbuf[1100];
    const char *full = resolve(dirfd, path, rbuf, sizeof rbuf);
    const char *canon = (full && canonicalize(full, cbuf, sizeof cbuf) == 0) ? cbuf : NULL;
    int cls = class_of(canon);
    uint8_t kind = (flags & AT_SYMLINK_NOFOLLOW) ? 1 : 0;
    uint64_t h = 0;
    uint32_t gen0 = 0;
    int cacheable = g_cache && cls == CLS_ROOT;
    if (cacheable) {
        h = hash_str(canon) ^ kind;
        if (dirty_has(hash_str(canon))) cacheable = 0;
        else gen0 = gen_get(parent_hash(canon));
    }
    if (cacheable) {
        int err = 0;
        if (cache_get_stat(kind, canon, h, st, &err)) {
            count_hit(fn, cls);
            if (err) { errno = err; return -1; }
            return 0;
        }
        atomic_fetch_add(&g_cache_misses, 1);
    }

    g_depth++;
    uint64_t t0 = now_ns();
    int r = call_real(fn, dirfd, path, st, flags);
    int saved = errno;
    uint64_t t1 = now_ns();
    g_depth--;
    if (cacheable) cache_put_stat(kind, canon, h, r, saved, st, gen0);
    record(fn, cls, t0, t1, r == -1, g_stats ? canon : NULL);
    errno = saved;
    return r;
}

EXPORT int stat(const char *path, struct stat *st) { return do_stat(FN_STAT, AT_FDCWD, path, st, 0); }
EXPORT int lstat(const char *path, struct stat *st) { return do_stat(FN_LSTAT, AT_FDCWD, path, st, AT_SYMLINK_NOFOLLOW); }
EXPORT int fstatat(int dirfd, const char *path, struct stat *st, int flags) { return do_stat(FN_FSTATAT, dirfd, path, st, flags); }

// Measured only.
#define MEASURED(FN, CALL, DIRFD, PATH)                                                                              \
    if (!g_enabled || !g_stats || g_depth) return CALL;                                                              \
    char rbuf[1100], cbuf[1100];                                                                                     \
    const char *full = resolve(DIRFD, PATH, rbuf, sizeof rbuf);                                                      \
    const char *canon = (full && canonicalize(full, cbuf, sizeof cbuf) == 0) ? cbuf : NULL;                          \
    int cls = class_of(canon);                                                                                       \
    g_depth++;                                                                                                       \
    uint64_t t0 = now_ns();                                                                                          \
    __typeof__(CALL) r = CALL;                                                                                       \
    uint64_t t1 = now_ns();                                                                                          \
    g_depth--;                                                                                                       \
    record(FN, cls, t0, t1, r == -1, NULL);                                                                          \
    return r;

EXPORT int statx(int dirfd, const char *path, int flags, unsigned int mask, void *buf) {
    LOAD(real_statx, "statx");
    if (!real_statx) { errno = ENOSYS; return -1; }
    MEASURED(FN_STATX, real_statx(dirfd, path, flags, mask, buf), dirfd, path)
}

EXPORT ssize_t getxattr(const char *path, const char *name, void *value, size_t size) {
    LOAD(real_getxattr, "getxattr");
    MEASURED(FN_GETXATTR, real_getxattr(path, name, value, size), AT_FDCWD, path)
}

EXPORT ssize_t lgetxattr(const char *path, const char *name, void *value, size_t size) {
    LOAD(real_lgetxattr, "lgetxattr");
    MEASURED(FN_LGETXATTR, real_lgetxattr(path, name, value, size), AT_FDCWD, path)
}

EXPORT int access(const char *path, int mode) {
    LOAD(real_access, "access");
    MEASURED(FN_ACCESS, real_access(path, mode), AT_FDCWD, path)
}

EXPORT int faccessat(int dirfd, const char *path, int mode, int flags) {
    LOAD(real_faccessat, "faccessat");
    MEASURED(FN_FACCESSAT, real_faccessat(dirfd, path, mode, flags), dirfd, path)
}

// ---------------------------------------------------------------------------------------------------------------------
// Directories
// ---------------------------------------------------------------------------------------------------------------------

static DIR *fake_dir_open(dlist *dl, int fd, int cls, pent_t *ent) {
    dstate *s = (dstate *)calloc(1, sizeof(dstate));
    if (!s) { dlist_unref(dl); return NULL; }
    s->kind = DK_FAKE;
    s->cls = cls;
    s->ent = ent;
    s->dl = dl;
    s->fd = fd;
    // the DIR* handed out is the dstate itself; it is only ever given back to our readdir / closedir / ...
    if (dirtab_put(s, s) != 0) { dlist_unref(dl); free(s); return NULL; }
    return (DIR *)s;
}

static DIR *dir_opened(int fn, DIR *real, const char *canon, int cls, uint64_t t0, uint64_t t1, int fd_given) {
    pent_t *ent = (g_stats && real && canon) ? pt_get(g_dir_tab, canon, cls) : NULL;
    if (ent) atomic_fetch_add_explicit(&ent->opens, 1, memory_order_relaxed);
    if (real && (g_stats || (g_cache && cls == CLS_ROOT))) {
        dstate *s = (dstate *)calloc(1, sizeof(dstate));
        if (s) {
            s->kind = DK_REAL;
            s->cls = cls;
            s->ent = ent;
            s->fd = -1;
            if (g_cache && cls == CLS_ROOT && canon && atomic_load(&g_mem) <= MEM_CAP) {
                dlist *b = dlist_new();
                char *cp = strdup(canon);
                if (b && cp) {
                    s->kind = DK_RECORDING;
                    s->canon = cp;
                    s->h = hash_str(canon);
                    s->gen_at_start = gen_get(s->h);
                    s->builder = b;
                } else { dlist_unref(b); free(cp); }
            }
            if (dirtab_put(real, s) != 0) { dlist_unref(s->builder); free(s->canon); free(s); }
        }
    }
    (void)fd_given;
    record(fn, cls, t0, t1, real == NULL, NULL);
    return real;
}

static DIR *open_dir_common(int fn, int fd, const char *path) {
    // path != NULL: opendir(path); otherwise fdopendir(fd)
    char rbuf[1100], cbuf[1100];
    const char *full = path ? resolve(AT_FDCWD, path, rbuf, sizeof rbuf) : resolve(fd, ".", rbuf, sizeof rbuf);
    const char *canon = (full && canonicalize(full, cbuf, sizeof cbuf) == 0) ? cbuf : NULL;
    int cls = class_of(canon);
    if (g_cache && cls == CLS_ROOT) {
        dlist *dl = cache_get_dir(canon, hash_str(canon));
        if (dl) {
            pent_t *ent = (g_stats && g_dir_tab) ? pt_get(g_dir_tab, canon, cls) : NULL;
            DIR *f = fake_dir_open(dl, path ? -1 : fd, cls, ent);
            if (f) {
                if (ent) atomic_fetch_add_explicit(&ent->opens, 1, memory_order_relaxed);
                count_hit(fn, cls);
                return f;
            }
        }
        atomic_fetch_add(&g_cache_misses, 1);
    }
    g_depth++;
    uint64_t t0 = now_ns();
    DIR *d = path ? real_opendir(path) : real_fdopendir(fd);
    int saved = errno;
    uint64_t t1 = now_ns();
    g_depth--;
    d = dir_opened(fn, d, canon, cls, t0, t1, path == NULL);
    errno = saved;
    return d;
}

EXPORT DIR *opendir(const char *path) {
    LOAD(real_opendir, "opendir");
    if (!g_enabled || g_depth || !path) return real_opendir(path);
    return open_dir_common(FN_OPENDIR, -1, path);
}

EXPORT DIR *fdopendir(int fd) {
    LOAD(real_fdopendir, "fdopendir");
    if (!g_enabled || g_depth) return real_fdopendir(fd);
    return open_dir_common(FN_FDOPENDIR, fd, NULL);
}

static struct dirent *fake_readdir(dstate *s) {
    if (s->idx >= s->dl->count) return NULL;
    dl_ent *e = &s->dl->ents[s->idx++];
    s->de.d_ino = e->ino;
    s->de.d_off = s->idx;
    s->de.d_reclen = sizeof(struct dirent);
    s->de.d_type = e->type;
    memcpy(s->de.d_name, s->dl->blob + e->off, e->nlen + 1);
    return &s->de;
}

static void recording_add(dstate *s, const struct dirent *e) {
    if (!s->builder) return;
    if (dlist_add(s->builder, e->d_name, strlen(e->d_name), e->d_ino, e->d_type) != 0) {
        dlist_unref(s->builder);
        s->builder = NULL;
    }
}

static void recording_end(dstate *s) {
    if (!s->builder) return;
    cache_put_dir(s->canon, s->h, s->builder, s->gen_at_start);
    s->builder = NULL;
}

static void recording_drop(dstate *s) {
    if (s->kind != DK_RECORDING) return;
    dlist_unref(s->builder);
    s->builder = NULL;
}

EXPORT struct dirent *readdir(DIR *d) {
    LOAD(real_readdir, "readdir");
    if (!g_enabled || g_depth) return real_readdir(d);
    dstate *s = dirtab_get(d, 0);
    if (s && s->kind == DK_FAKE) return fake_readdir(s);
    g_depth++;
    uint64_t t0 = now_ns();
    errno = 0;
    struct dirent *r = real_readdir(d);
    int saved = errno;
    uint64_t t1 = now_ns();
    g_depth--;
    if (s && s->kind == DK_RECORDING) {
        if (r) recording_add(s, r);
        else if (saved == 0) recording_end(s);
        else recording_drop(s);
    }
    if (s && s->ent) {
        atomic_fetch_add_explicit(&s->ent->calls, 1, memory_order_relaxed);
        atomic_fetch_add_explicit(&s->ent->ns, t1 - t0, memory_order_relaxed);
    }
    record(FN_READDIR, s ? s->cls : CLS_UNKNOWN, t0, t1, 0, NULL);
    errno = saved;
    return r;
}

EXPORT struct dirent64 *readdir64(DIR *d) {
    LOAD(real_readdir64, "readdir64");
    if (!real_readdir64) { errno = ENOSYS; return NULL; }
    if (!g_enabled || g_depth) return real_readdir64(d);
    dstate *s = dirtab_get(d, 0);
    if (s && s->kind == DK_FAKE) return (struct dirent64 *)fake_readdir(s);   // same layout on LP64 bionic
    g_depth++;
    uint64_t t0 = now_ns();
    errno = 0;
    struct dirent64 *r = real_readdir64(d);
    int saved = errno;
    uint64_t t1 = now_ns();
    g_depth--;
    if (s && s->kind == DK_RECORDING) {
        if (r) recording_add(s, (const struct dirent *)r);
        else if (saved == 0) recording_end(s);
        else recording_drop(s);
    }
    if (s && s->ent) {
        atomic_fetch_add_explicit(&s->ent->calls, 1, memory_order_relaxed);
        atomic_fetch_add_explicit(&s->ent->ns, t1 - t0, memory_order_relaxed);
    }
    record(FN_READDIR, s ? s->cls : CLS_UNKNOWN, t0, t1, 0, NULL);
    errno = saved;
    return r;
}

EXPORT void rewinddir(DIR *d) {
    LOAD(real_rewinddir, "rewinddir");
    if (!g_enabled || g_depth) { real_rewinddir(d); return; }
    dstate *s = dirtab_get(d, 0);
    if (s && s->kind == DK_FAKE) { s->idx = 0; return; }
    if (s) recording_drop(s);
    real_rewinddir(d);
}

EXPORT long telldir(DIR *d) {
    LOAD(real_telldir, "telldir");
    if (!g_enabled || g_depth) return real_telldir(d);
    dstate *s = dirtab_get(d, 0);
    if (s && s->kind == DK_FAKE) return (long)s->idx;
    if (s) recording_drop(s);
    return real_telldir(d);
}

EXPORT void seekdir(DIR *d, long loc) {
    LOAD(real_seekdir, "seekdir");
    if (!g_enabled || g_depth) { real_seekdir(d, loc); return; }
    dstate *s = dirtab_get(d, 0);
    if (s && s->kind == DK_FAKE) { s->idx = (uint32_t)loc > s->dl->count ? s->dl->count : (uint32_t)loc; return; }
    if (s) recording_drop(s);
    real_seekdir(d, loc);
}

EXPORT int dirfd(DIR *d) {
    LOAD(real_dirfd, "dirfd");
    if (!g_enabled || g_depth) return real_dirfd(d);
    dstate *s = dirtab_get(d, 0);
    if (s && s->kind == DK_FAKE) {
        if (s->fd < 0) { errno = ENOTSUP; return -1; }   // opened by path and answered from memory: there is no descriptor
        return s->fd;
    }
    return real_dirfd(d);
}

EXPORT int closedir(DIR *d) {
    LOAD(real_closedir, "closedir");
    if (!g_enabled || g_depth) return real_closedir(d);
    dstate *s = dirtab_get(d, 1);
    if (s && s->kind == DK_FAKE) {
        int r = 0;
        if (s->fd >= 0) r = close(s->fd);
        dlist_unref(s->dl);
        free(s);
        return r;
    }
    if (s) {
        recording_drop(s);
        free(s->canon);
    }
    g_depth++;
    uint64_t t0 = now_ns();
    int r = real_closedir(d);
    int saved = errno;
    uint64_t t1 = now_ns();
    g_depth--;
    record(FN_CLOSEDIR, s ? s->cls : CLS_UNKNOWN, t0, t1, r == -1, NULL);
    free(s);
    errno = saved;
    return r;
}

// ---------------------------------------------------------------------------------------------------------------------
// Changes: tell the other processes (and drop what this one knows)
// ---------------------------------------------------------------------------------------------------------------------

static void note_change(int dirfd, const char *path, int write_open) {
    if (!g_cache || !path) return;
    char rbuf[1100], cbuf[1100];
    const char *full = resolve(dirfd, path, rbuf, sizeof rbuf);
    if (!full || canonicalize(full, cbuf, sizeof cbuf) != 0 || !in_root(cbuf)) return;
    uint64_t h = hash_str(cbuf);
    if (write_open) dirty_add(h);
    gen_bump(parent_hash(cbuf));
    gen_bump(h);
}

#define WRITE_INTENT (O_WRONLY | O_RDWR | O_CREAT | O_TRUNC | O_APPEND)

static int open_common(int dirfd, const char *path, int flags, mode_t mode, int is_at) {
    int track = g_cache && !g_depth && (flags & WRITE_INTENT);
    if (track) note_change(dirfd, path, 1);
    int cls = CLS_UNKNOWN;
    if (g_stats && !g_depth && path) {
        char rbuf[1100], cbuf[1100];
        const char *full = resolve(is_at ? dirfd : AT_FDCWD, path, rbuf, sizeof rbuf);
        const char *canon = (full && canonicalize(full, cbuf, sizeof cbuf) == 0) ? cbuf : NULL;
        cls = class_of(canon);
    }
    uint64_t t0 = g_stats ? now_ns() : 0;
    int r = is_at ? real_openat(dirfd, path, flags, mode) : real_open(path, flags, mode);
    int e = errno;
    if (g_stats && !g_depth) {
        record(FN_OPEN, cls, t0, now_ns(), r < 0, NULL);
        if (r >= 0 && r < FDTAB_SIZE) atomic_store(&g_fdcls[r], cls == CLS_ROOT ? 1 : 2);
    }
    if (track) note_change(dirfd, path, 1);
    errno = e;
    return r;
}

EXPORT int open(const char *path, int flags, ...) {
    LOAD(real_open, "open");
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    return open_common(AT_FDCWD, path, flags, mode, 0);
}

EXPORT int openat(int dirfd, const char *path, int flags, ...) {
    LOAD(real_openat, "openat");
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    return open_common(dirfd, path, flags, mode, 1);
}

EXPORT int close(int fd) {
    LOAD(real_close, "close");
    if (g_stats && fd >= 0 && fd < FDTAB_SIZE) atomic_store(&g_fdcls[fd], 0);
    return real_close(fd);
}

// Class of a descriptor; one we never saw being opened (the server hands files over as descriptors) is looked up once.
static int fd_class(int fd) {
    if (fd < 0 || fd >= FDTAB_SIZE) return CLS_UNKNOWN;
    uint8_t c = atomic_load(&g_fdcls[fd]);
    if (c == 0) {
        char link[48], target[1100], cbuf[1100];
        snprintf(link, sizeof link, "/proc/self/fd/%d", fd);
        ssize_t n = readlink(link, target, sizeof target - 1);
        if (n > 0 && target[0] == '/') {
            target[n] = 0;
            c = (canonicalize(target, cbuf, sizeof cbuf) == 0 && in_root(cbuf)) ? 1 : 2;
        } else {
            c = 2;   // sockets, pipes, anonymous files
        }
        atomic_store(&g_fdcls[fd], c);
    }
    return c == 1 ? CLS_ROOT : CLS_OTHER;
}

static void note_read(int fd, uint64_t t0, uint64_t t1, ssize_t r) {
    int cls = fd_class(fd);
    record(FN_READ, cls, t0, t1, r < 0, NULL);
    if (r > 0) atomic_fetch_add_explicit(&g_counters[FN_READ][cls].bytes, (uint64_t)r, memory_order_relaxed);
}

EXPORT ssize_t read(int fd, void *buf, size_t n) {
    LOAD(real_read, "read");
    if (!g_stats || g_depth) return real_read(fd, buf, n);
    uint64_t t0 = now_ns();
    ssize_t r = real_read(fd, buf, n);
    int e = errno;
    note_read(fd, t0, now_ns(), r);
    errno = e;
    return r;
}

EXPORT ssize_t pread(int fd, void *buf, size_t n, off_t off) {
    LOAD(real_pread, "pread");
    if (!g_stats || g_depth) return real_pread(fd, buf, n, off);
    uint64_t t0 = now_ns();
    ssize_t r = real_pread(fd, buf, n, off);
    int e = errno;
    note_read(fd, t0, now_ns(), r);
    errno = e;
    return r;
}

EXPORT ssize_t pread64(int fd, void *buf, size_t n, off64_t off) {
    LOAD(real_pread, "pread64");
    if (!g_stats || g_depth) return real_pread(fd, buf, n, (off_t)off);
    uint64_t t0 = now_ns();
    ssize_t r = real_pread(fd, buf, n, (off_t)off);
    int e = errno;
    note_read(fd, t0, now_ns(), r);
    errno = e;
    return r;
}

#define CHANGE1(RET, NAME, PARAMS, ARGS, DIRFD, PATH)                                                                \
    EXPORT RET NAME PARAMS {                                                                                         \
        LOAD(real_##NAME, #NAME);                                                                                    \
        int track = g_cache && !g_depth;                                                                             \
        if (track) note_change(DIRFD, PATH, 0);                                                                      \
        RET r = real_##NAME ARGS;                                                                                    \
        if (track) { int e = errno; note_change(DIRFD, PATH, 0); errno = e; }                                        \
        return r;                                                                                                    \
    }

CHANGE1(int, unlink, (const char *p), (p), AT_FDCWD, p)
CHANGE1(int, unlinkat, (int fd, const char *p, int fl), (fd, p, fl), fd, p)
CHANGE1(int, rmdir, (const char *p), (p), AT_FDCWD, p)
CHANGE1(int, mkdir, (const char *p, mode_t m), (p, m), AT_FDCWD, p)
CHANGE1(int, mkdirat, (int fd, const char *p, mode_t m), (fd, p, m), fd, p)
CHANGE1(int, truncate, (const char *p, off_t l), (p, l), AT_FDCWD, p)

EXPORT int rename(const char *from, const char *to) {
    LOAD(real_rename, "rename");
    int track = g_cache && !g_depth;
    if (track) { note_change(AT_FDCWD, from, 0); note_change(AT_FDCWD, to, 0); }
    int r = real_rename(from, to);
    if (track) { int e = errno; note_change(AT_FDCWD, from, 0); note_change(AT_FDCWD, to, 0); errno = e; }
    return r;
}

EXPORT int renameat(int fd1, const char *from, int fd2, const char *to) {
    LOAD(real_renameat, "renameat");
    int track = g_cache && !g_depth;
    if (track) { note_change(fd1, from, 0); note_change(fd2, to, 0); }
    int r = real_renameat(fd1, from, fd2, to);
    if (track) { int e = errno; note_change(fd1, from, 0); note_change(fd2, to, 0); errno = e; }
    return r;
}

EXPORT int symlink(const char *target, const char *linkpath) {
    LOAD(real_symlink, "symlink");
    int track = g_cache && !g_depth;
    if (track) note_change(AT_FDCWD, linkpath, 0);
    int r = real_symlink(target, linkpath);
    if (track) { int e = errno; note_change(AT_FDCWD, linkpath, 0); errno = e; }
    return r;
}

EXPORT int link(const char *oldpath, const char *newpath) {
    LOAD(real_link, "link");
    int track = g_cache && !g_depth;
    if (track) note_change(AT_FDCWD, newpath, 0);
    int r = real_link(oldpath, newpath);
    if (track) { int e = errno; note_change(AT_FDCWD, newpath, 0); errno = e; }
    return r;
}
