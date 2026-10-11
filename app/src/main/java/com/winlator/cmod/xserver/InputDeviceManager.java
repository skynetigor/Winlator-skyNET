package com.winlator.cmod.xserver;

import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xserver.events.ButtonPress;
import com.winlator.cmod.xserver.events.ButtonRelease;
import com.winlator.cmod.xserver.events.EnterNotify;
import com.winlator.cmod.xserver.events.Event;
import com.winlator.cmod.xserver.events.FocusEvent;
import com.winlator.cmod.xserver.events.KeyPress;
import com.winlator.cmod.xserver.events.KeyRelease;
import com.winlator.cmod.xserver.events.LeaveNotify;
import com.winlator.cmod.xserver.events.MappingNotify;
import com.winlator.cmod.xserver.events.MotionNotify;
import com.winlator.cmod.xserver.events.PointerWindowEvent;
import com.winlator.cmod.xserver.extensions.XInput2Extension;
import java.util.ArrayList;

public class InputDeviceManager implements Pointer.OnPointerMotionListener, Keyboard.OnKeyboardListener, WindowManager.OnWindowModificationListener, XResourceManager.OnResourceLifecycleListener {
    private static final byte MOUSE_WHEEL_DELTA = 120;
    private Window pointWindow;
    private final XServer xServer;

    public InputDeviceManager(XServer xServer) {
        this.xServer = xServer;
        pointWindow = xServer.windowManager.rootWindow;
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.windowManager.addOnResourceLifecycleListener(this);
        xServer.pointer.addOnPointerMotionListener(this);
        xServer.keyboard.addOnKeyboardListener(this);
        xServer.windowManager.setOnFocusChangeListener(this::onFocusChanged);
    }

    /** Toolkits such as SDL wait for FocusIn / EnterNotify before they take input; Wine does not need them. */
    private boolean sendsFocusAndCrossing() {
        return xi2() != null;
    }

    private void onFocusChanged(Window oldFocus, Window newFocus) {
        if (!sendsFocusAndCrossing()) return;
        if (com.winlator.cmod.xserver.XClientRequestHandler.trace) {
            android.util.Log.d("XTrace", "focus " + (oldFocus != null ? oldFocus.id : 0) + " -> " + (newFocus != null ? newFocus.id : 0)
                    + " pointWindow=" + (pointWindow != null ? pointWindow.id : 0));
        }
        XInput2Extension xi2 = xi2();
        if (oldFocus == xServer.windowManager.rootWindow && pointWindow != null && pointWindow != xServer.windowManager.rootWindow
                && pointWindow.attributes.isEnabled()) {
            // Moving the focus from PointerRoot: the window under the pointer is told it loses it (detail Pointer), as a real server does.
            pointWindow.sendEvent(Event.FOCUS_CHANGE, new FocusEvent(false, (byte)5, pointWindow, FocusEvent.MODE_NORMAL));
        }
        if (oldFocus != null && oldFocus != xServer.windowManager.rootWindow && oldFocus.attributes.isEnabled()) {
            oldFocus.sendEvent(Event.FOCUS_CHANGE, new FocusEvent(false, FocusEvent.DETAIL_NONLINEAR, oldFocus, FocusEvent.MODE_NORMAL));
            xi2.onFocus(false, oldFocus);
        }
        if (newFocus != null && newFocus != xServer.windowManager.rootWindow && newFocus.attributes.isEnabled()) {
            newFocus.sendEvent(Event.FOCUS_CHANGE, new FocusEvent(true, FocusEvent.DETAIL_NONLINEAR, newFocus, FocusEvent.MODE_NORMAL));
            newFocus.sendEvent(Event.KEYMAP_STATE, new com.winlator.cmod.xserver.events.KeymapNotify());
            xi2.onFocus(true, newFocus);
        }
    }

    private java.util.concurrent.ScheduledExecutorService replayExecutor;

    /**
     * A window manager tells a program it has focus (FocusIn, Enter, Expose) once the window is up and the program is
     * reading events. Sent only at the instant of mapping, SDL drops them, and then ignores every click and key because it
     * thinks it has no focus. So the same events are repeated a few times over the next seconds.
     */
    private void replayFocusLater(final Window window) {
        if (replayExecutor == null) {
            replayExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "FocusReplay");
                thread.setDaemon(true);
                return thread;
            });
        }
        for (int delay : new int[]{300, 1000, 2500, 5000, 8000, 12000, 18000, 25000}) {
            replayExecutor.schedule(() -> {
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                    if (xServer.windowManager.getFocusedWindow() != window || !window.attributes.isMapped()) return;
                    window.sendEvent(Event.FOCUS_CHANGE, new FocusEvent(true, FocusEvent.DETAIL_NONLINEAR, window, FocusEvent.MODE_NORMAL));
                    short x = xServer.pointer.getClampedX();
                    short y = xServer.pointer.getClampedY();
                    if (window.containsPoint(x, y)) {
                        short[] local = window.rootPointToLocal(x, y);
                        window.sendEvent(Event.ENTER_WINDOW, new EnterNotify(PointerWindowEvent.Detail.ANCESTOR, xServer.windowManager.rootWindow, window, null,
                                x, y, local[0], local[1], getKeyButMask(), PointerWindowEvent.Mode.NORMAL, true));
                    }
                    window.sendEvent(Event.EXPOSURE, new com.winlator.cmod.xserver.events.Expose(window));
                }
            }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void onMapWindow(Window window) {
        updatePointWindow();
    }

    @Override
    public void onUnmapWindow(Window window) {
        updatePointWindow();
    }

    @Override
    public void onChangeWindowZOrder(Window.StackMode stackMode, Window window, Window sibling) {
        updatePointWindow();
    }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        updatePointWindow();
    }

    @Override
    public void onCreateResource(XResource resource) {
        updatePointWindow();
    }

    @Override
    public void onFreeResource(XResource resource) {
        updatePointWindow();
    }

    private void updatePointWindow() {
        Window pointWindow = xServer.windowManager.findPointWindow(xServer.pointer.getClampedX(), xServer.pointer.getClampedY());
        Window previous = this.pointWindow;
        this.pointWindow = pointWindow != null ? pointWindow : xServer.windowManager.rootWindow;
        if (previous != null && previous != this.pointWindow && sendsFocusAndCrossing()) {
            sendEnterLeaveNotify(previous, this.pointWindow, PointerWindowEvent.Mode.NORMAL);
        }
    }

    public Window getPointWindow() {
        return pointWindow;
    }

    private void sendEvent(Window window, int eventId, Event event) {
        Window grabWindow = xServer.grabManager.getWindow();
        if (grabWindow != null && grabWindow.attributes.isEnabled()) {
            EventListener eventListener = xServer.grabManager.getEventListener();
            if (xServer.grabManager.isOwnerEvents() && window != null) {
                window.sendEvent(eventId, event, xServer.grabManager.getClient());
            }
            else if (eventListener.isInterestedIn(eventId)) {
                eventListener.sendEvent(event);
            }
        }
        else if (window != null && window.attributes.isEnabled()) {
            window.sendEvent(eventId, event);
        }
    }

    private void sendEvent(Window window, Bitmask eventMask, Event event) {
        Window grabWindow = xServer.grabManager.getWindow();
        if (grabWindow != null && grabWindow.attributes.isEnabled()) {
            EventListener eventListener = xServer.grabManager.getEventListener();
            if (xServer.grabManager.isOwnerEvents() && window != null) {
                window.sendEvent(eventMask, event, eventListener.client);
            }
            else if (eventListener.isInterestedIn(eventMask)) {
                eventListener.sendEvent(event);
            }
        }
        else if (window != null && window.attributes.isEnabled()) {
            window.sendEvent(eventMask, event);
        }
    }

    public void sendEnterLeaveNotify(Window windowA, Window windowB, PointerWindowEvent.Mode mode) {
        if (windowA == windowB) return;
        short x = xServer.pointer.getX();
        short y = xServer.pointer.getY();

        short[] localPointA = windowA.rootPointToLocal(x, y);
        short[] localPointB = windowB.rootPointToLocal(x, y);

        boolean sameScreenAndFocus = windowB.isAncestorOf(xServer.windowManager.getFocusedWindow());
        PointerWindowEvent.Detail detailA = PointerWindowEvent.Detail.NONLINEAR;
        PointerWindowEvent.Detail detailB = PointerWindowEvent.Detail.NONLINEAR;

        // X11: leaving a window for its inferior sends Leave(detail Inferior) to the outer one and Enter(detail Ancestor)
        // to the inner one. The Wine-oriented code below has them the other way round; toolkits (SDL) ignore an Enter
        // with detail Inferior, so Linux sessions use the correct pairing.
        boolean correct = sendsFocusAndCrossing();
        if (windowA.isAncestorOf(windowB)) {
            detailA = correct ? PointerWindowEvent.Detail.INFERIOR : PointerWindowEvent.Detail.ANCESTOR;
            detailB = correct ? PointerWindowEvent.Detail.ANCESTOR : PointerWindowEvent.Detail.INFERIOR;
        }
        else if (windowB.isAncestorOf(windowA)) {
            detailB = correct ? PointerWindowEvent.Detail.INFERIOR : PointerWindowEvent.Detail.ANCESTOR;
            detailA = correct ? PointerWindowEvent.Detail.ANCESTOR : PointerWindowEvent.Detail.INFERIOR;
        }

        Bitmask keyButMask = getKeyButMask();
        sendEvent(windowA, Event.LEAVE_WINDOW, new LeaveNotify(detailA, xServer.windowManager.rootWindow, windowA, null, x, y, localPointA[0], localPointA[1], keyButMask, mode, sameScreenAndFocus));
        sendEvent(windowB, Event.ENTER_WINDOW, new EnterNotify(detailB, xServer.windowManager.rootWindow, windowB, null, x, y, localPointB[0], localPointB[1], keyButMask, mode, sameScreenAndFocus));
        if (sendsFocusAndCrossing()) windowB.sendEvent(Event.KEYMAP_STATE, new com.winlator.cmod.xserver.events.KeymapNotify());
        XInput2Extension xi2 = xi2();
        if (xi2 != null) {
            xi2.onCrossing(false, windowA, detailA.ordinal(), mode.ordinal());
            xi2.onCrossing(true, windowB, detailB.ordinal(), mode.ordinal());
        }
    }

    private XInput2Extension xi2() {
        return xServer.getExtension(XInput2Extension.MAJOR_OPCODE);
    }

    @Override
    public void onPointerButtonPress(Pointer.Button button) {
        XInput2Extension xi2 = xi2();
        if (xi2 != null) xi2.onButton(button, true);
        if (xServer.isRelativeMouseMovement()) {
            WinHandler winHandler = xServer.getWinHandler();
            int wheelDelta = button == Pointer.Button.BUTTON_SCROLL_UP ? MOUSE_WHEEL_DELTA : (button == Pointer.Button.BUTTON_SCROLL_DOWN ? -MOUSE_WHEEL_DELTA : 0);
            winHandler.mouseEvent(MouseEventFlags.getFlagFor(button, true), 0, 0, wheelDelta);
        }
        else {
            Window grabWindow = xServer.grabManager.getWindow();
            if (grabWindow == null) {
                grabWindow = pointWindow.getAncestorWithEventId(Event.BUTTON_PRESS);
                if (grabWindow != null) xServer.grabManager.activatePointerGrab(grabWindow);
            }

            int listenerCount = grabWindow != null ? grabWindow.eventListenerCount(Event.BUTTON_PRESS) : 0;
            if (com.winlator.cmod.xserver.XClientRequestHandler.trace) {
                android.util.Log.d("XTrace", "core ButtonPress: pointWindow=" + pointWindow.id + " grabWindow=" + (grabWindow != null ? grabWindow.id : 0)
                        + " enabled=" + (grabWindow != null && grabWindow.attributes.isEnabled())
                        + " listeners=" + listenerCount);
            }
            if (grabWindow != null && grabWindow.attributes.isEnabled()) {
                short x = xServer.pointer.getX();
                short y = xServer.pointer.getY();
                short[] localPoint = grabWindow.rootPointToLocal(x, y);

                Window child = grabWindow.isAncestorOf(pointWindow) ? pointWindow : null;
                // The state of a ButtonPress is the keys and buttons held before it (not the event-selection mask used above).
                Bitmask state = getKeyButMask();
                state.unset(button.flag());
                grabWindow.sendEvent(Event.BUTTON_PRESS, new ButtonPress(button.code(), xServer.windowManager.rootWindow, grabWindow, child, x, y, localPoint[0], localPoint[1], state));
            }
        }
    }

    @Override
    public void onPointerButtonRelease(Pointer.Button button) {
        XInput2Extension xi2 = xi2();
        if (xi2 != null) xi2.onButton(button, false);
        if (xServer.isRelativeMouseMovement()) {
            WinHandler winHandler = xServer.getWinHandler();
            winHandler.mouseEvent(MouseEventFlags.getFlagFor(button, false), 0, 0, 0);
        }
        else {
            Bitmask eventMask = createPointerEventMask();
            Window grabWindow = xServer.grabManager.getWindow();
            Window window = grabWindow == null || xServer.grabManager.isOwnerEvents() ? pointWindow.getAncestorWithEventMask(eventMask) : null;

            if (grabWindow != null || window != null) {
                Window eventWindow = window != null ? window : grabWindow;

                short x = xServer.pointer.getX();
                short y = xServer.pointer.getY();
                short[] localPoint = eventWindow.rootPointToLocal(x, y);

                Window child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
                // ... and for a ButtonRelease the buttons held before it, which still include this one.
                Bitmask state = getKeyButMask();
                state.set(button.flag());
                ButtonRelease buttonRelease = new ButtonRelease(button.code(), xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], state);
                sendEvent(window, eventMask, buttonRelease);
            }

            if (xServer.pointer.getButtonMask().isEmpty() && xServer.grabManager.isReleaseWithButtons()) {
                xServer.grabManager.deactivatePointerGrab();
            }
        }
    }

    @Override
    public void onPointerMove(short x, short y) {
        updatePointWindow();
        XInput2Extension xi2 = xi2();
        if (xi2 != null) xi2.onMotion(x, y);
        Bitmask eventMask = createPointerEventMask();
        Window grabWindow = xServer.grabManager.getWindow();
        Window window = grabWindow == null || xServer.grabManager.isOwnerEvents() ? pointWindow.getAncestorWithEventMask(eventMask) : null;

        if (grabWindow != null || window != null) {
            Window eventWindow = window != null ? window : grabWindow;
            short[] localPoint = eventWindow.rootPointToLocal(x, y);

            Window child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
            sendEvent(window, eventMask, new MotionNotify(false, xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], getKeyButMask()));
        }
    }

    @Override
    public void onKeyPress(byte keycode, int keysym) {
        XInput2Extension xi2 = xi2();
        if (xi2 != null) xi2.onKey(keycode, true);
        Window focusedWindow = xServer.windowManager.getFocusedWindow();
        if (focusedWindow == null) return;
        updatePointWindow();

        Window eventWindow = null;
        Window child = null;
        if (focusedWindow.isAncestorOf(pointWindow)) {
            eventWindow = pointWindow.getAncestorWithEventId(Event.KEY_PRESS, focusedWindow);
            child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
        }
        if (eventWindow == null) {
            if (!focusedWindow.hasEventListenerFor(Event.KEY_PRESS)) return;
            eventWindow = focusedWindow;
        }

        if (!eventWindow.attributes.isEnabled()) return;

        Bitmask keyButMask = getKeyButMask();
        short x = xServer.pointer.getX();
        short y = xServer.pointer.getY();
        short[] localPoint = eventWindow.rootPointToLocal(x, y);

        if (keysym != 0 && !xServer.keyboard.hasKeysym(keycode, keysym)) {
            xServer.keyboard.setKeysyms(keycode, keysym, keysym);
            eventWindow.sendEvent(new MappingNotify(MappingNotify.Request.KEYBOARD, keycode, 1));
        }

        eventWindow.sendEvent(Event.KEY_PRESS, new KeyPress(keycode, xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], keyButMask));
    }

    @Override
    public void onKeyRelease(byte keycode) {
        XInput2Extension xi2 = xi2();
        if (xi2 != null) xi2.onKey(keycode, false);
        Window focusedWindow = xServer.windowManager.getFocusedWindow();
        if (focusedWindow == null) return;
        updatePointWindow();

        Window eventWindow = null;
        Window child = null;
        if (focusedWindow.isAncestorOf(pointWindow)) {
            eventWindow = pointWindow.getAncestorWithEventId(Event.KEY_RELEASE, focusedWindow);
            child = eventWindow.isAncestorOf(pointWindow) ? pointWindow : null;
        }
        if (eventWindow == null) {
            if (!focusedWindow.hasEventListenerFor(Event.KEY_RELEASE)) return;
            eventWindow = focusedWindow;
        }

        if (!eventWindow.attributes.isEnabled()) return;

        Bitmask keyButMask = getKeyButMask();
        short x = xServer.pointer.getX();
        short y = xServer.pointer.getY();
        short[] localPoint = eventWindow.rootPointToLocal(x, y);

        eventWindow.sendEvent(Event.KEY_RELEASE, new KeyRelease(keycode, xServer.windowManager.rootWindow, eventWindow, child, x, y, localPoint[0], localPoint[1], keyButMask));
    }

    private Bitmask createPointerEventMask() {
        Bitmask eventMask = new Bitmask();
        eventMask.set(Event.POINTER_MOTION);

        Bitmask buttonMask = xServer.pointer.getButtonMask();
        if (!buttonMask.isEmpty()) {
            eventMask.set(Event.BUTTON_MOTION);

            if (buttonMask.isSet(Pointer.Button.BUTTON_LEFT.flag())) {
                eventMask.set(Event.BUTTON1_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_MIDDLE.flag())) {
                eventMask.set(Event.BUTTON2_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_RIGHT.flag())) {
                eventMask.set(Event.BUTTON3_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_SCROLL_UP.flag())) {
                eventMask.set(Event.BUTTON4_MOTION);
            }
            if (buttonMask.isSet(Pointer.Button.BUTTON_SCROLL_DOWN.flag())) {
                eventMask.set(Event.BUTTON5_MOTION);
            }
        }
        return eventMask;
    }

    public Bitmask getKeyButMask() {
        Bitmask keyButMask = new Bitmask();
        keyButMask.join(xServer.pointer.getButtonMask());
        keyButMask.join(xServer.keyboard.getModifiersMask());
        return keyButMask;
    }
}
