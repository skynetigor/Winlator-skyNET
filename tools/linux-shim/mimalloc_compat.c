/*
 * libmimalloc.so.3 for a game that links mimalloc, under Box64.
 *
 * BeamNG 0.39 ships mimalloc and calls its API (mi_new, mi_free, ...). On Linux mimalloc also takes over malloc and
 * free for the whole process, so every library, libdxcompiler included, frees with mimalloc's free. Box64 resolves
 * a library's free to its own wrapper of the glibc one, a block that mimalloc allocated reaches glibc's free, and the
 * game dies a second after start with "free(): invalid size" (BOX64_MALLOC_HACK changes where it dies, not whether).
 *
 * This library has the mi_* functions the game imports and none of the malloc family, so nothing is taken over and
 * every allocation is a plain malloc: one allocator for the whole process, as in 0.34. Put it in front of the game's
 * own copy (BOX64_LD_LIBRARY_PATH). A heap is a list of the blocks allocated from it, so mi_heap_destroy still
 * releases the ones that are left.
 *
 * MIT licence, written for this project.
 */
#define _GNU_SOURCE
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <malloc.h>

#define EXPORT __attribute__((visibility("default")))

/* ---- heaps -------------------------------------------------------------------------------------------------- */

typedef struct Heap {
    void **blocks;
    size_t count, capacity;
} Heap;

/* Blocks a heap still owns, so a block freed on its own is not freed again when the heap goes. */
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static void **owned;          /* open-addressing set of heap-owned block addresses */
static size_t ownedCapacity, ownedCount;

static size_t slot(void *p, size_t capacity) { return ((uintptr_t)p >> 4) * 0x9E3779B97F4A7C15ull % capacity; }

static void ownedRehash(size_t capacity) {
    void **old = owned;
    size_t oldCapacity = ownedCapacity;
    owned = calloc(capacity, sizeof(void *));
    ownedCapacity = capacity;
    ownedCount = 0;
    for (size_t i = 0; i < oldCapacity; i++) {
        void *p = old[i];
        if (p && p != (void *)1) {
            size_t s = slot(p, capacity);
            while (owned[s]) s = (s + 1) % capacity;
            owned[s] = p;
            ownedCount++;
        }
    }
    free(old);
}

static void ownedAdd(void *p) {
    if (!ownedCapacity || (ownedCount + 1) * 2 > ownedCapacity) ownedRehash(ownedCapacity ? ownedCapacity * 2 : 1024);
    size_t s = slot(p, ownedCapacity);
    while (owned[s] && owned[s] != (void *)1) s = (s + 1) % ownedCapacity;
    owned[s] = p;
    ownedCount++;
}

/* Removes [p] from the set; true when it was in it. A tombstone (1) keeps probe chains intact. */
static bool ownedRemove(void *p) {
    if (!ownedCapacity) return false;
    size_t s = slot(p, ownedCapacity);
    for (size_t n = 0; n < ownedCapacity && owned[s]; n++, s = (s + 1) % ownedCapacity) {
        if (owned[s] == p) {
            owned[s] = (void *)1;
            ownedCount--;
            return true;
        }
    }
    return false;
}

EXPORT Heap *mi_heap_new(void) { return calloc(1, sizeof(Heap)); }

EXPORT void *mi_heap_malloc(Heap *heap, size_t size) {
    void *p = malloc(size);
    if (!p || !heap) return p;
    pthread_mutex_lock(&lock);
    if (heap->count == heap->capacity) {
        heap->capacity = heap->capacity ? heap->capacity * 2 : 64;
        heap->blocks = realloc(heap->blocks, heap->capacity * sizeof(void *));
    }
    heap->blocks[heap->count++] = p;
    ownedAdd(p);
    pthread_mutex_unlock(&lock);
    return p;
}

EXPORT void mi_heap_destroy(Heap *heap) {
    if (!heap) return;
    pthread_mutex_lock(&lock);
    for (size_t i = 0; i < heap->count; i++) {
        if (ownedRemove(heap->blocks[i])) free(heap->blocks[i]);
    }
    pthread_mutex_unlock(&lock);
    free(heap->blocks);
    free(heap);
}

/* ---- the rest of the allocator API: plain libc --------------------------------------------------------------- */

static void forget(void *p) {
    if (!p || !ownedCount) return;
    pthread_mutex_lock(&lock);
    ownedRemove(p);
    pthread_mutex_unlock(&lock);
}

EXPORT void mi_free(void *p) { forget(p); free(p); }
EXPORT void mi_free_aligned(void *p, size_t alignment) { (void)alignment; mi_free(p); }
EXPORT void mi_free_size(void *p, size_t size) { (void)size; mi_free(p); }
EXPORT void mi_free_size_aligned(void *p, size_t size, size_t alignment) { (void)size; (void)alignment; mi_free(p); }

EXPORT void *mi_malloc(size_t size) { return malloc(size); }
EXPORT void *mi_zalloc(size_t size) { return calloc(1, size); }

EXPORT void *mi_malloc_aligned(size_t size, size_t alignment) {
    void *p = NULL;
    if (alignment < sizeof(void *)) alignment = sizeof(void *);
    return posix_memalign(&p, alignment, size) == 0 ? p : NULL;
}

/* operator new: it must not return null */
EXPORT void *mi_new(size_t size) {
    void *p = malloc(size ? size : 1);
    if (!p) abort();
    return p;
}
EXPORT void *mi_new_nothrow(size_t size) { return malloc(size ? size : 1); }
EXPORT void *mi_new_aligned(size_t size, size_t alignment) {
    void *p = mi_malloc_aligned(size ? size : 1, alignment);
    if (!p) abort();
    return p;
}
EXPORT void *mi_new_aligned_nothrow(size_t size, size_t alignment) { return mi_malloc_aligned(size ? size : 1, alignment); }

EXPORT void *mi_realloc(void *p, size_t size) {
    forget(p);
    return realloc(p, size);
}

EXPORT void *mi_realloc_aligned(void *p, size_t size, size_t alignment) {
    if ((uintptr_t)p % alignment == 0) return mi_realloc(p, size);
    void *q = mi_malloc_aligned(size, alignment);
    if (q && p) {
        size_t old = malloc_usable_size(p);
        memcpy(q, p, old < size ? old : size);
        mi_free(p);
    }
    return q;
}

/* ---- housekeeping the game calls and glibc does not need ------------------------------------------------------ */

EXPORT void mi_collect(bool force) { (void)force; }
EXPORT void mi_thread_init(void) {}
EXPORT void mi_process_init(void) {}
EXPORT bool mi_option_set(int option, long value) { (void)option; (void)value; return true; }
EXPORT void mi_register_output(void *out, void *arg) { (void)out; (void)arg; }
EXPORT void mi_register_error(void *fun, void *arg) { (void)fun; (void)arg; }
EXPORT void mi_stats_print_out(void *out, void *arg) { (void)out; (void)arg; }

EXPORT void mi_process_info(size_t *elapsed, size_t *user, size_t *system, size_t *current_rss, size_t *peak_rss,
                            size_t *current_commit, size_t *peak_commit, size_t *page_faults) {
    size_t *out[] = { elapsed, user, system, current_rss, peak_rss, current_commit, peak_commit, page_faults };
    for (int i = 0; i < 8; i++) if (out[i]) *out[i] = 0;
}
