/*
 * A mouse tester for a Linux container, the companion to gamepadtest.c.
 *
 * It draws a mouse: a button lights up while it is held, and under each one are two counters, how many times
 * it went down and how many times it came up. A press that arrives without its release is the shape of a bug
 * that leaves a program hovering happily and acting on nothing, so the two numbers are worth comparing.
 *
 * The pointer itself is drawn as a crosshair wherever the program thinks it is, with its coordinates, so a
 * cursor that lags or lands somewhere else is visible too. The bar across the top says whether the window
 * believes it has the pointer and the keyboard.
 *
 * SDL is opened with dlopen rather than linked, so this cross-compiles with no SDL headers and runs against
 * whatever libSDL2 the runtime ships.
 *
 * MIT licence, written for this project.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

typedef struct { int x, y, w, h; } Rect;

static void *sdl;
#define SYM(var, name) do { *(void **)(&var) = dlsym(sdl, name); } while (0)

static int (*SDL_Init)(uint32_t);
static void (*SDL_Quit)(void);
static const char *(*SDL_GetError)(void);
static void *(*SDL_CreateWindow)(const char *, int, int, int, int, uint32_t);
static void *(*SDL_CreateRenderer)(void *, int, uint32_t);
static int (*SDL_SetRenderDrawColor)(void *, uint8_t, uint8_t, uint8_t, uint8_t);
static int (*SDL_RenderClear)(void *);
static int (*SDL_RenderFillRect)(void *, const Rect *);
static int (*SDL_RenderDrawRect)(void *, const Rect *);
static void (*SDL_RenderPresent)(void *);
static int (*SDL_PollEvent)(void *);
static void (*SDL_Delay)(uint32_t);
static void *(*SDL_GetKeyboardFocus)(void);
static void *(*SDL_GetMouseFocus)(void);
static uint32_t (*SDL_GetMouseState)(int *, int *);
static uint32_t (*SDL_GetWindowFlags)(void *);
static uint32_t (*SDL_GetTicks)(void);
static int (*SDL_GetWindowWMInfo)(void *, void *);
static uint32_t (*SDL_GetWindowID)(void *);
static uint8_t (*SDL_EventState)(uint32_t, int);

#define INIT_VIDEO 0x20u
#define WINDOW_SHOWN 0x4u

#define WIDTH 1280
#define HEIGHT 720

static void *renderer;

static void colour(int r, int g, int b) { SDL_SetRenderDrawColor(renderer, r, g, b, 255); }

static void fill(int x, int y, int w, int h) {
    Rect r = { x, y, w, h };
    SDL_RenderFillRect(renderer, &r);
}

static void outline(int x, int y, int w, int h) {
    Rect r = { x, y, w, h };
    SDL_RenderDrawRect(renderer, &r);
}

/* ---- numbers ------------------------------------------------------------------------------------------
 * Seven segments, so a count can be read without shipping a font. */
static const unsigned char SEGMENTS[10] = { 0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f };

static void digit(int x, int y, int w, int h, int value) {
    int t = w / 5;              /* stroke */
    if (t < 2) t = 2;
    int half = h / 2;
    unsigned char on = value >= 0 && value <= 9 ? SEGMENTS[value] : 0x40 /* a bare dash */;
    if (on & 0x01) fill(x + t, y, w - 2 * t, t);                       /* a: top */
    if (on & 0x02) fill(x + w - t, y + t, t, half - t);                /* b: upper right */
    if (on & 0x04) fill(x + w - t, y + half, t, half - t);             /* c: lower right */
    if (on & 0x08) fill(x + t, y + h - t, w - 2 * t, t);               /* d: bottom */
    if (on & 0x10) fill(x, y + half, t, half - t);                     /* e: lower left */
    if (on & 0x20) fill(x, y + t, t, half - t);                        /* f: upper left */
    if (on & 0x40) fill(x + t, y + half - t / 2, w - 2 * t, t);        /* g: middle */
}

/* Right-aligned, so the digits do not jump about as a count grows. */
static void number(int right, int y, int w, int h, int gap, int digits, int value) {
    int negative = value < 0;
    if (negative) value = -value;
    for (int i = 0; i < digits; i++) {
        int place = 1;
        for (int j = 0; j < i; j++) place *= 10;
        int shown = value / place % 10;
        int leading = value < place && i > 0;
        int x = right - (i + 1) * (w + gap) + gap;
        if (leading) {
            if (negative && value / (place / 10) < 10) { digit(x, y, w, h, -1); negative = 0; }
            continue;
        }
        digit(x, y, w, h, shown);
    }
}

/* ---- the mouse ---------------------------------------------------------------------------------------- */

#define BTN_LEFT 1
#define BTN_MIDDLE 2
#define BTN_RIGHT 3
#define BTN_X1 4
#define BTN_X2 5
#define BUTTONS 6

static int held[BUTTONS];
static int downs[BUTTONS];
static int ups[BUTTONS];

static void button(int x, int y, int w, int h, int which) {
    if (held[which]) colour(0x3d, 0xd5, 0x98);
    else colour(0x33, 0x3b, 0x47);
    fill(x, y, w, h);
    colour(0x72, 0x7e, 0x90);
    outline(x, y, w, h);

    /* Down count in green over up count in blue: they should keep pace with each other. */
    colour(0x3d, 0xd5, 0x98);
    number(x + w - 6, y + h + 12, 14, 22, 4, 3, downs[which]);
    colour(0x6f, 0xa8, 0xf5);
    number(x + w - 6, y + h + 42, 14, 22, 4, 3, ups[which]);
}

int main(void) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    sdl = dlopen("libSDL2-2.0.so.0", RTLD_NOW);
    if (!sdl) sdl = dlopen("libSDL2.so", RTLD_NOW);
    if (!sdl) {
        printf("mousetest: no libSDL2 in this runtime: %s\n", dlerror());
        return 1;
    }
    SYM(SDL_Init, "SDL_Init"); SYM(SDL_Quit, "SDL_Quit"); SYM(SDL_GetError, "SDL_GetError");
    SYM(SDL_CreateWindow, "SDL_CreateWindow"); SYM(SDL_CreateRenderer, "SDL_CreateRenderer");
    SYM(SDL_SetRenderDrawColor, "SDL_SetRenderDrawColor"); SYM(SDL_RenderClear, "SDL_RenderClear");
    SYM(SDL_RenderFillRect, "SDL_RenderFillRect"); SYM(SDL_RenderDrawRect, "SDL_RenderDrawRect");
    SYM(SDL_RenderPresent, "SDL_RenderPresent"); SYM(SDL_PollEvent, "SDL_PollEvent");
    SYM(SDL_Delay, "SDL_Delay");
    SYM(SDL_GetKeyboardFocus, "SDL_GetKeyboardFocus"); SYM(SDL_GetMouseFocus, "SDL_GetMouseFocus");
    SYM(SDL_GetMouseState, "SDL_GetMouseState"); SYM(SDL_GetWindowFlags, "SDL_GetWindowFlags");
    SYM(SDL_GetTicks, "SDL_GetTicks");
    SYM(SDL_GetWindowWMInfo, "SDL_GetWindowWMInfo"); SYM(SDL_GetWindowID, "SDL_GetWindowID");
    SYM(SDL_EventState, "SDL_EventState");
    if (!SDL_Init || !SDL_CreateRenderer || !SDL_RenderFillRect) {
        printf("mousetest: this libSDL2 is missing what the test needs\n");
        return 1;
    }

    if (SDL_Init(INIT_VIDEO) != 0) {
        printf("mousetest: SDL_Init failed: %s\n", SDL_GetError());
        return 1;
    }
    void *window = SDL_CreateWindow("Mouse test", 0, 0, WIDTH, HEIGHT, WINDOW_SHOWN);
    if (!window) {
        printf("mousetest: no window: %s\n", SDL_GetError());
        return 1;
    }
    renderer = SDL_CreateRenderer(window, -1, 0);
    if (!renderer) {
        printf("mousetest: no renderer: %s\n", SDL_GetError());
        return 1;
    }

    /* Which X window SDL is actually listening on: an event sent to any other one is dropped in silence. */
    if (SDL_GetWindowWMInfo) {
        unsigned char info[128];
        memset(info, 0, sizeof info);
        info[0] = 2; info[1] = 0; info[2] = 0;   /* SDL_version: ask as 2.0.0, which any SDL2 accepts */
        if (SDL_GetWindowWMInfo(window, info)) {
            uint32_t subsystem;
            unsigned long xwindow;
            memcpy(&subsystem, info + 4, 4);
            memcpy(&xwindow, info + 16, 8);
            printf("SDL window: subsystem=%u xwindow=0x%lx (id %u)\n",
                   subsystem, xwindow, SDL_GetWindowID ? SDL_GetWindowID(window) : 0);
        }
        else printf("SDL_GetWindowWMInfo refused: %s\n", SDL_GetError());
    }

    // SDL raises one of these for every X event its own dispatcher handles, which says whether an event
    // that never became an SDL event was dropped before SDL saw it or after.
    if (SDL_EventState) SDL_EventState(0x201 /* SDL_SYSWMEVENT */, 1 /* SDL_ENABLE */);

    int mouseX = WIDTH / 2, mouseY = HEIGHT / 2;
    int lastRelX = 0, lastRelY = 0;
    int wheelX = 0, wheelY = 0;
    int motions = 0;
    int keys = 0, lastScancode = 0, lastMod = 0;
    uint32_t lastReport = 0;
    int xcount[64]; memset(xcount, 0, sizeof xcount);
    int hasPointer = 0, hasKeyboard = 0;

    char buffer[256];
    int running = 1;
    while (running) {
        while (SDL_PollEvent(buffer)) {
            uint32_t type;
            memcpy(&type, buffer, 4);
            if (type == 0x100) running = 0;                            /* SDL_QUIT */
            else if (type == 0x300 || type == 0x301) {                 /* SDL_KEY{DOWN,UP} */
                int32_t scancode, sym;
                uint16_t mod;
                memcpy(&scancode, buffer + 16, 4);                     /* SDL_Keysym.scancode */
                memcpy(&sym, buffer + 20, 4);
                memcpy(&mod, buffer + 24, 2);
                if (type == 0x300) { lastScancode = scancode; lastMod = mod; keys++; }
                printf("key %s scancode=%d sym=%d mod=0x%x\n",
                       type == 0x300 ? "down" : "up  ", scancode, sym, mod);
                if (type == 0x300 && scancode == 41 && mod == 0) running = 0;   /* Escape */
            }
            else if (type == 0x400) {                                  /* SDL_MOUSEMOTION */
                int32_t x, y, xrel, yrel;
                memcpy(&x, buffer + 20, 4); memcpy(&y, buffer + 24, 4);
                memcpy(&xrel, buffer + 28, 4); memcpy(&yrel, buffer + 32, 4);
                mouseX = x; mouseY = y; lastRelX = xrel; lastRelY = yrel;
                motions++;
            }
            else if (type == 0x401 || type == 0x402) {                 /* SDL_MOUSEBUTTON{DOWN,UP} */
                unsigned which = (unsigned char)buffer[16];
                int32_t x, y;
                memcpy(&x, buffer + 20, 4); memcpy(&y, buffer + 24, 4);
                mouseX = x; mouseY = y;
                if (which < BUTTONS) {
                    if (type == 0x401) { held[which] = 1; downs[which]++; }
                    else { held[which] = 0; ups[which]++; }
                }
                printf("button %u %s at %d,%d  (downs %d, ups %d)\n", which,
                       type == 0x401 ? "down" : "up", x, y,
                       which < BUTTONS ? downs[which] : -1, which < BUTTONS ? ups[which] : -1);
            }
            else if (type == 0x403) {                                  /* SDL_MOUSEWHEEL */
                int32_t x, y;
                memcpy(&x, buffer + 16, 4); memcpy(&y, buffer + 20, 4);
                wheelX += x; wheelY += y;
                printf("wheel %d,%d  (total %d,%d)\n", x, y, wheelX, wheelY);
            }
            else if (type == 0x200) {                                  /* SDL_WINDOWEVENT */
                unsigned what = (unsigned char)buffer[12];
                if (what == 10) { hasPointer = 1; printf("pointer entered\n"); }
                else if (what == 11) { hasPointer = 0; printf("pointer left\n"); }
                else if (what == 12) { hasKeyboard = 1; printf("focus gained\n"); }
                else if (what == 13) { hasKeyboard = 0; printf("focus lost\n"); }
            }
            else if (type == 0x201) {            /* SDL_SYSWMEVENT: the X event SDL is dispatching */
                void *msg;
                memcpy(&msg, buffer + 8, sizeof msg);
                if (msg) {
                    int32_t xtype;
                    memcpy(&xtype, (char *)msg + 8, 4);   /* SDL_SysWMmsg: version, subsystem, then the XEvent */
                    if (xtype != 6) { xcount[xtype & 63]++; printf("X event %d reached SDL\n", xtype); }
                }
            }
            else if (type != 0x400 && type != 0x200) {
                printf("other event 0x%x\n", type);   /* anything not handled above, motion aside */
            }
        }

        colour(0x12, 0x16, 0x1c);
        SDL_RenderClear(renderer);

        /* Top bar: the left half is the pointer, the right half the keyboard. */
        colour(hasPointer ? 0x3d : 0xd5, hasPointer ? 0xd5 : 0x4b, hasPointer ? 0x98 : 0x4b);
        fill(0, 0, WIDTH / 2, 14);
        colour(hasKeyboard ? 0x3d : 0xd5, hasKeyboard ? 0xd5 : 0x4b, hasKeyboard ? 0x98 : 0x4b);
        fill(WIDTH / 2, 0, WIDTH / 2, 14);

        /* The pointer, where the program believes it is. */
        colour(0x2d, 0x35, 0x42);
        fill(0, mouseY, WIDTH, 1);
        fill(mouseX, 0, 1, HEIGHT);
        colour(0xf2, 0xc1, 0x4e);
        fill(mouseX - 9, mouseY - 2, 18, 4);
        fill(mouseX - 2, mouseY - 9, 4, 18);

        /* The mouse itself: two buttons, a wheel between them, and the two side buttons. */
        int bx = 490, by = 170, bw = 300, bh = 330;
        colour(0x1b, 0x20, 0x29);
        fill(bx, by, bw, bh);
        colour(0x72, 0x7e, 0x90);
        outline(bx, by, bw, bh);

        button(bx + 14, by + 14, 118, 130, BTN_LEFT);
        button(bx + bw - 132, by + 14, 118, 130, BTN_RIGHT);
        button(bx + bw / 2 - 20, by + 30, 40, 98, BTN_MIDDLE);
        button(bx - 70, by + 60, 56, 60, BTN_X1);
        button(bx - 70, by + 190, 56, 60, BTN_X2);

        /* Pointer position, then the last relative step, then the wheel total. */
        colour(0xf2, 0xc1, 0x4e);
        number(250, 600, 18, 30, 5, 4, mouseX);
        number(250, 645, 18, 30, 5, 4, mouseY);
        colour(0x8a, 0x98, 0xad);
        number(470, 600, 18, 30, 5, 4, lastRelX);
        number(470, 645, 18, 30, 5, 4, lastRelY);
        colour(0x6f, 0xa8, 0xf5);
        number(1240, 600, 18, 30, 5, 4, wheelX);
        number(1240, 645, 18, 30, 5, 4, wheelY);
        colour(0x55, 0x60, 0x72);
        number(1000, 645, 14, 22, 4, 5, motions);
        /* The keyboard: how many keys have arrived, the last one's scancode, and the modifiers with it. */
        colour(0xc9, 0x8a, 0xf5);
        number(1000, 600, 14, 22, 4, 4, keys);
        number(820, 600, 14, 22, 4, 3, lastScancode);
        number(820, 645, 14, 22, 4, 4, lastMod);

        SDL_RenderPresent(renderer);
        SDL_Delay(16);

        /* Ask SDL what it believes, rather than inferring it from the events it chose to raise. */
        if (SDL_GetTicks && SDL_GetTicks() - lastReport > 2000) {
            lastReport = SDL_GetTicks();
            int px = 0, py = 0;
            uint32_t buttons = SDL_GetMouseState ? SDL_GetMouseState(&px, &py) : 0;
            printf("SDL thinks: keyboardFocus=%s mouseFocus=%s flags=0x%x polledButtons=0x%x at %d,%d\n",
                   SDL_GetKeyboardFocus && SDL_GetKeyboardFocus() == window ? "ours" : "none",
                   SDL_GetMouseFocus && SDL_GetMouseFocus() == window ? "ours" : "none",
                   SDL_GetWindowFlags ? SDL_GetWindowFlags(window) : 0, buttons, px, py);
            printf("  X events seen by SDL:");
            for (int t = 0; t < 64; t++) if (xcount[t]) printf(" %d=%d", t, xcount[t]);
            printf("\n");
        }
    }

    SDL_Quit();
    return 0;
}
