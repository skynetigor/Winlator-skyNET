/*
 * A bare Xlib client for the Linux container's X server, to find out which events a client really gets.
 *
 * It opens a window, asks for core input events, optionally the XInput2 selections SDL makes, and prints every
 * event it is handed, with what XFilterEvent says about it. No SDL in the way, so what it prints is what Xlib
 * delivered.
 *
 *   xtest MODE SECONDS
 *   MODE letters:  i  select XI2 events the way SDL does (motion and touch on the window, raw on the root)
 *                  m  open an input method and an input context, as SDL does
 *                  b  also select XI2 button events on the window
 *                  c  read events with XCheckIfEvent, the way SDL does, instead of XPending / XNextEvent
 *
 * MIT licence, written for this project.
 */
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/extensions/XInput2.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <locale.h>
#include <time.h>
#include <unistd.h>

static Bool any_event(Display *d, XEvent *e, XPointer a) { (void)d; (void)e; (void)a; return True; }

static const char *name_of(int type) {
    switch (type) {
        case KeyPress: return "KeyPress"; case KeyRelease: return "KeyRelease";
        case ButtonPress: return "ButtonPress"; case ButtonRelease: return "ButtonRelease";
        case MotionNotify: return "MotionNotify"; case EnterNotify: return "EnterNotify";
        case LeaveNotify: return "LeaveNotify"; case FocusIn: return "FocusIn"; case FocusOut: return "FocusOut";
        case KeymapNotify: return "KeymapNotify"; case Expose: return "Expose"; case MapNotify: return "MapNotify";
        case ConfigureNotify: return "ConfigureNotify"; case PropertyNotify: return "PropertyNotify";
        case ReparentNotify: return "ReparentNotify"; case GenericEvent: return "GenericEvent";
        case ClientMessage: return "ClientMessage"; case UnmapNotify: return "UnmapNotify";
        default: return "other";
    }
}

int main(int argc, char **argv) {
    const char *mode = argc > 1 ? argv[1] : "";
    int seconds = argc > 2 ? atoi(argv[2]) : 25;
    setlocale(LC_ALL, "");
    Display *dpy = XOpenDisplay(NULL);
    if (!dpy) { printf("cannot open display\n"); return 1; }
    int xi_opcode = 0, ev, err;
    int have_xi = XQueryExtension(dpy, "XInputExtension", &xi_opcode, &ev, &err);
    printf("mode '%s', XInput2 extension: %d (opcode %d)\n", mode, have_xi, xi_opcode);

    Window root = DefaultRootWindow(dpy);
    XSetWindowAttributes attrs;
    attrs.event_mask = KeyPressMask | KeyReleaseMask | ButtonPressMask | ButtonReleaseMask | PointerMotionMask
                     | EnterWindowMask | LeaveWindowMask | FocusChangeMask | StructureNotifyMask | PropertyChangeMask
                     | ExposureMask | KeymapStateMask;
    Window win = XCreateWindow(dpy, root, 0, 0, 640, 480, 0, CopyFromParent, InputOutput, CopyFromParent, CWEventMask, &attrs);
    printf("window 0x%lx\n", win);

    if (strchr(mode, 'i') && have_xi) {
        int major = 2, minor = 2;
        XIQueryVersion(dpy, &major, &minor);
        printf("XI version %d.%d\n", major, minor);
        unsigned char root_mask[XIMaskLen(XI_LASTEVENT)] = {0};
        XIEventMask rm = { XIAllMasterDevices, sizeof root_mask, root_mask };
        XISetMask(root_mask, XI_RawMotion); XISetMask(root_mask, XI_RawButtonPress); XISetMask(root_mask, XI_RawButtonRelease);
        XISelectEvents(dpy, root, &rm, 1);
        unsigned char win_mask[XIMaskLen(XI_LASTEVENT)] = {0};
        XIEventMask wm = { XIAllMasterDevices, sizeof win_mask, win_mask };
        XISetMask(win_mask, XI_Motion);
        XISetMask(win_mask, XI_TouchBegin); XISetMask(win_mask, XI_TouchUpdate); XISetMask(win_mask, XI_TouchEnd);
        if (strchr(mode, 'b')) { XISetMask(win_mask, XI_ButtonPress); XISetMask(win_mask, XI_ButtonRelease); }
        XISelectEvents(dpy, win, &wm, 1);
    }
    XIM xim = NULL; XIC xic = NULL;
    if (strchr(mode, 'm')) {
        xim = XOpenIM(dpy, NULL, NULL, NULL);
        printf("XOpenIM: %p\n", (void *)xim);
        if (xim) {
            xic = XCreateIC(xim, XNClientWindow, win, XNInputStyle, XIMPreeditNothing | XIMStatusNothing, NULL);
            printf("XCreateIC: %p\n", (void *)xic);
        }
    }

    GC gc = XCreateGC(dpy, win, 0, NULL);
    XSetForeground(dpy, gc, 0x2040a0);
    XMapWindow(dpy, win);
    XSetInputFocus(dpy, win, RevertToParent, CurrentTime);
    XFlush(dpy);

    time_t end = time(NULL) + seconds;
    int counts[64] = {0};
    int filtered[64] = {0};
    while (time(NULL) < end) {
        for (;;) {
            XEvent e;
            if (strchr(mode, 'c')) {
                if (!XCheckIfEvent(dpy, &e, any_event, NULL)) break;
            } else {
                if (!XPending(dpy)) break;
                XNextEvent(dpy, &e);
            }
            int type = e.type;
            Bool was_filtered = XFilterEvent(&e, None);
            const char *label = name_of(type);
            if (type == GenericEvent && XGetEventData(dpy, &e.xcookie)) {
                printf("  GenericEvent ext %d evtype %d%s\n", e.xcookie.extension, e.xcookie.evtype, was_filtered ? " [FILTERED]" : "");
                XFreeEventData(dpy, &e.xcookie);
            } else if (type == ButtonPress || type == ButtonRelease) {
                printf("  %s button %u at %d,%d window 0x%lx%s\n", label, e.xbutton.button, e.xbutton.x, e.xbutton.y, e.xbutton.window, was_filtered ? " [FILTERED]" : "");
            } else if (type == KeyPress || type == KeyRelease) {
                printf("  %s keycode %u%s\n", label, e.xkey.keycode, was_filtered ? " [FILTERED]" : "");
            } else if (type == Expose) {
                /* paint something: the container's splash screen stays until a program draws */
                XFillRectangle(dpy, win, gc, 0, 0, 640, 480);
                XFlush(dpy);
                printf("  Expose (painted)\n");
            } else if (type != MotionNotify) {
                printf("  %s%s\n", label, was_filtered ? " [FILTERED]" : "");
            }
            if (type < 64) { counts[type]++; if (was_filtered) filtered[type]++; }
        }
        usleep(10000);
        fflush(stdout);
    }
    printf("totals:");
    for (int t = 0; t < 64; t++) if (counts[t]) printf(" %s=%d(filtered %d)", name_of(t), counts[t], filtered[t]);
    printf("\n");
    XCloseDisplay(dpy);
    return 0;
}
