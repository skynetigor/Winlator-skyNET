/*
 * A gamepad tester for a Linux container: it draws what the guest's SDL reports, so a pad that the app
 * believes it is sending can be checked against what a program actually receives.
 *
 * SDL is opened with dlopen rather than linked, so this cross-compiles with no SDL headers or import
 * library, and runs against whatever libSDL2 the runtime happens to ship.
 *
 * Nothing is drawn with text: the layout is a gamepad, and a control lights up while it is held.
 * Every event is also written to stdout, which is where the app's session log picks it up.
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
static int (*SDL_NumJoysticks)(void);
static int (*SDL_IsGameController)(int);
static void *(*SDL_GameControllerOpen)(int);
static const char *(*SDL_GameControllerName)(void *);
static const char *(*SDL_GameControllerMapping)(void *);
static uint8_t (*SDL_GameControllerGetButton)(void *, int);
static int16_t (*SDL_GameControllerGetAxis)(void *, int);
static void *(*SDL_JoystickOpen)(int);
static const char *(*SDL_JoystickNameForIndex)(int);
static int (*SDL_JoystickNumButtons)(void *);
static int (*SDL_JoystickNumAxes)(void *);
static uint8_t (*SDL_JoystickGetButton)(void *, int);
static int16_t (*SDL_JoystickGetAxis)(void *, int);

#define INIT_VIDEO 0x20u
#define INIT_JOYSTICK 0x200u
#define INIT_GAMECONTROLLER 0x2000u
#define WINDOW_SHOWN 0x4u

/* SDL_GameControllerButton */
enum { BTN_A, BTN_B, BTN_X, BTN_Y, BTN_BACK, BTN_GUIDE, BTN_START,
       BTN_LSTICK, BTN_RSTICK, BTN_LSHOULDER, BTN_RSHOULDER,
       BTN_UP, BTN_DOWN, BTN_LEFT, BTN_RIGHT, BTN_COUNT };
/* SDL_GameControllerAxis */
enum { AX_LEFTX, AX_LEFTY, AX_RIGHTX, AX_RIGHTY, AX_TRIGGERLEFT, AX_TRIGGERRIGHT, AX_COUNT };

static void *renderer;

static void colour(int r, int g, int b) { SDL_SetRenderDrawColor(renderer, r, g, b, 255); }

static void box(int x, int y, int w, int h, int on) {
    Rect r = { x, y, w, h };
    if (on) {
        colour(0x3d, 0xd5, 0x98);
        SDL_RenderFillRect(renderer, &r);
    }
    else {
        colour(0x3a, 0x42, 0x50);
        SDL_RenderFillRect(renderer, &r);
        colour(0x6a, 0x76, 0x88);
        SDL_RenderDrawRect(renderer, &r);
    }
}

/* A filled disc, drawn as rows: the renderer has no circle of its own. */
static void disc(int cx, int cy, int radius, int r, int g, int b) {
    colour(r, g, b);
    for (int dy = -radius; dy <= radius; dy++) {
        int half = 0;
        while ((half + 1) * (half + 1) + dy * dy <= radius * radius) half++;
        Rect row = { cx - half, cy + dy, half * 2 + 1, 1 };
        SDL_RenderFillRect(renderer, &row);
    }
}

/* A stick: the ring is where it can go, the dot is where it is. */
static void stick(int cx, int cy, int radius, int x, int y, int pressed) {
    disc(cx, cy, radius, 0x2a, 0x31, 0x3d);
    disc(cx, cy, radius - 4, 0x1c, 0x21, 0x2a);
    int dx = (int)((long)x * (radius - 22) / 32767);
    int dy = (int)((long)y * (radius - 22) / 32767);
    if (pressed) disc(cx + dx, cy + dy, 20, 0x3d, 0xd5, 0x98);
    else disc(cx + dx, cy + dy, 20, 0x8a, 0x98, 0xad);
}

/* A trigger, filling upwards with how far it is held. */
static void trigger(int x, int y, int w, int h, int value) {
    box(x, y, w, h, 0);
    int filled = (int)((long)value * h / 32767);
    if (filled < 0) filled = 0;
    if (filled > h) filled = h;
    if (filled > 0) {
        Rect r = { x, y + h - filled, w, filled };
        colour(0x3d, 0xd5, 0x98);
        SDL_RenderFillRect(renderer, &r);
    }
}

int main(void) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    sdl = dlopen("libSDL2-2.0.so.0", RTLD_NOW);
    if (!sdl) sdl = dlopen("libSDL2.so", RTLD_NOW);
    if (!sdl) {
        printf("gamepadtest: no libSDL2 in this runtime: %s\n", dlerror());
        return 1;
    }
    SYM(SDL_Init, "SDL_Init"); SYM(SDL_Quit, "SDL_Quit"); SYM(SDL_GetError, "SDL_GetError");
    SYM(SDL_CreateWindow, "SDL_CreateWindow"); SYM(SDL_CreateRenderer, "SDL_CreateRenderer");
    SYM(SDL_SetRenderDrawColor, "SDL_SetRenderDrawColor"); SYM(SDL_RenderClear, "SDL_RenderClear");
    SYM(SDL_RenderFillRect, "SDL_RenderFillRect"); SYM(SDL_RenderDrawRect, "SDL_RenderDrawRect");
    SYM(SDL_RenderPresent, "SDL_RenderPresent"); SYM(SDL_PollEvent, "SDL_PollEvent");
    SYM(SDL_Delay, "SDL_Delay"); SYM(SDL_NumJoysticks, "SDL_NumJoysticks");
    SYM(SDL_IsGameController, "SDL_IsGameController"); SYM(SDL_GameControllerOpen, "SDL_GameControllerOpen");
    SYM(SDL_GameControllerName, "SDL_GameControllerName");
    SYM(SDL_GameControllerMapping, "SDL_GameControllerMapping");
    SYM(SDL_GameControllerGetButton, "SDL_GameControllerGetButton");
    SYM(SDL_GameControllerGetAxis, "SDL_GameControllerGetAxis");
    SYM(SDL_JoystickOpen, "SDL_JoystickOpen");
    SYM(SDL_JoystickNameForIndex, "SDL_JoystickNameForIndex");
    SYM(SDL_JoystickNumButtons, "SDL_JoystickNumButtons");
    SYM(SDL_JoystickNumAxes, "SDL_JoystickNumAxes");
    SYM(SDL_JoystickGetButton, "SDL_JoystickGetButton");
    SYM(SDL_JoystickGetAxis, "SDL_JoystickGetAxis");
    if (!SDL_Init || !SDL_CreateRenderer || !SDL_RenderFillRect) {
        printf("gamepadtest: this libSDL2 is missing what the test needs\n");
        return 1;
    }

    if (SDL_Init(INIT_VIDEO | INIT_JOYSTICK | INIT_GAMECONTROLLER) != 0) {
        printf("gamepadtest: SDL_Init failed: %s\n", SDL_GetError());
        return 1;
    }
    void *window = SDL_CreateWindow("Gamepad test", 0, 0, 1280, 720, WINDOW_SHOWN);
    if (!window) {
        printf("gamepadtest: no window: %s\n", SDL_GetError());
        return 1;
    }
    renderer = SDL_CreateRenderer(window, -1, 0);
    if (!renderer) {
        printf("gamepadtest: no renderer: %s\n", SDL_GetError());
        return 1;
    }

    void *pad = NULL;      /* opened as a game controller, so the buttons have names */
    void *stickOnly = NULL; /* opened as a plain joystick when SDL has no mapping for it */
    int known = -1;

    char buffer[256];
    int running = 1;
    while (running) {
        while (SDL_PollEvent(buffer)) {
            uint32_t type;
            memcpy(&type, buffer, 4);
            if (type == 0x100) running = 0;                       /* SDL_QUIT */
            else if (type == 0x300) {                             /* SDL_KEYDOWN */
                int32_t scancode;
                memcpy(&scancode, buffer + 16, 4);                /* SDL_Keysym.scancode */
                if (scancode == 41) running = 0;                  /* Escape */
            }
            else if (type == 0x605 || type == 0x606 || type == 0x653 || type == 0x654) {
                printf("device list changed (event 0x%x)\n", type);
                pad = stickOnly = NULL;
                known = -1;
            }
            else if (type == 0x603 || type == 0x604) {
                printf("joystick button %u %s\n", (unsigned)(uint8_t)buffer[12],
                       type == 0x603 ? "down" : "up");
            }
            else if (type == 0x651 || type == 0x652) {
                printf("controller button %u %s\n", (unsigned)(uint8_t)buffer[12],
                       type == 0x651 ? "down" : "up");
            }
        }

        int count = SDL_NumJoysticks();
        if (count != known) {
            known = count;
            printf("joysticks: %d\n", count);
            for (int i = 0; i < count; i++) {
                printf("  [%d] %s%s\n", i, SDL_JoystickNameForIndex(i),
                       SDL_IsGameController(i) ? " (has a mapping)" : " (no mapping)");
            }
            if (count > 0) {
                if (SDL_IsGameController(0)) {
                    pad = SDL_GameControllerOpen(0);
                    stickOnly = NULL;
                    if (pad) printf("opened: %s\n  mapping: %s\n",
                                    SDL_GameControllerName(pad), SDL_GameControllerMapping(pad));
                }
                else {
                    stickOnly = SDL_JoystickOpen(0);
                    pad = NULL;
                    if (stickOnly) printf("opened without a mapping: %d buttons, %d axes\n",
                                          SDL_JoystickNumButtons(stickOnly), SDL_JoystickNumAxes(stickOnly));
                }
            }
        }

        colour(0x12, 0x16, 0x1c);
        SDL_RenderClear(renderer);

        /* The bar across the top is the answer to "is there a pad at all": green yes, red no. */
        if (pad || stickOnly) colour(0x3d, 0xd5, 0x98);
        else colour(0xd5, 0x4b, 0x4b);
        Rect banner = { 0, 0, 1280, 14 };
        SDL_RenderFillRect(renderer, &banner);

        if (pad) {
            int held[BTN_COUNT];
            for (int i = 0; i < BTN_COUNT; i++) held[i] = SDL_GameControllerGetButton(pad, i);
            int axis[AX_COUNT];
            for (int i = 0; i < AX_COUNT; i++) axis[i] = SDL_GameControllerGetAxis(pad, i);

            stick(260, 420, 110, axis[AX_LEFTX], axis[AX_LEFTY], held[BTN_LSTICK]);
            stick(1020, 420, 110, axis[AX_RIGHTX], axis[AX_RIGHTY], held[BTN_RSTICK]);

            /* D-pad */
            box(500, 350, 70, 70, held[BTN_UP]);
            box(500, 490, 70, 70, held[BTN_DOWN]);
            box(430, 420, 70, 70, held[BTN_LEFT]);
            box(570, 420, 70, 70, held[BTN_RIGHT]);

            /* Face buttons, laid out as they sit on a pad */
            box(780, 350, 70, 70, held[BTN_Y]);
            box(710, 420, 70, 70, held[BTN_X]);
            box(850, 420, 70, 70, held[BTN_B]);
            box(780, 490, 70, 70, held[BTN_A]);

            /* Shoulders and triggers */
            box(230, 190, 150, 50, held[BTN_LSHOULDER]);
            box(900, 190, 150, 50, held[BTN_RSHOULDER]);
            trigger(230, 60, 150, 110, axis[AX_TRIGGERLEFT]);
            trigger(900, 60, 150, 110, axis[AX_TRIGGERRIGHT]);

            /* Back, Guide, Start */
            box(540, 620, 60, 40, held[BTN_BACK]);
            box(610, 620, 60, 40, held[BTN_GUIDE]);
            box(680, 620, 60, 40, held[BTN_START]);
        }
        else if (stickOnly) {
            /* No mapping: show the raw buttons and axes instead, so at least the wiring is visible. */
            int buttons = SDL_JoystickNumButtons(stickOnly);
            for (int i = 0; i < buttons && i < 24; i++) {
                box(80 + (i % 12) * 90, 120 + (i / 12) * 90, 70, 70, SDL_JoystickGetButton(stickOnly, i));
            }
            int axes = SDL_JoystickNumAxes(stickOnly);
            for (int i = 0; i < axes && i < 8; i++) {
                int value = SDL_JoystickGetAxis(stickOnly, i);
                box(80 + i * 150, 380, 120, 240, 0);
                int mid = 380 + 120;
                int offset = (int)((long)value * 110 / 32767);
                Rect bar = { 80 + i * 150, offset < 0 ? mid + offset : mid, 120, offset < 0 ? -offset : offset };
                if (bar.h < 2) bar.h = 2;
                colour(0x3d, 0xd5, 0x98);
                SDL_RenderFillRect(renderer, &bar);
            }
        }

        SDL_RenderPresent(renderer);
        SDL_Delay(16);
    }

    SDL_Quit();
    return 0;
}
