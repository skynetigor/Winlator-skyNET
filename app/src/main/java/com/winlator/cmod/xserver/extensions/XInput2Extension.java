package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XResource;
import com.winlator.cmod.xserver.XResourceManager;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.events.Event;

import java.io.IOException;
import java.util.ArrayList;

/**
 * A small XInput 2.2 for glibc clients. Games that read the mouse through XI2 (BeamNG.drive, SDL with XI2) see no
 * clicks or motion without it, because they ignore the core events. There is one master pointer (id 2) and one master
 * keyboard (id 3), each with a slave (4, 5). Touch, scroll valuators, device properties and real grabs are not
 * implemented: the matching requests are answered with "nothing" or success.
 */
public class XInput2Extension implements Extension, XResourceManager.OnResourceLifecycleListener {
    public static final byte MAJOR_OPCODE = -111;
    private static final byte FIRST_EVENT_ID = 80;
    private static final int GENERIC_EVENT = 35;

    public static final int DEVICE_MASTER_POINTER = 2;
    public static final int DEVICE_MASTER_KEYBOARD = 3;
    private static final int DEVICE_SLAVE_POINTER = 4;
    private static final int DEVICE_SLAVE_KEYBOARD = 5;

    public static final int EVENT_KEY_PRESS = 2;
    public static final int EVENT_KEY_RELEASE = 3;
    public static final int EVENT_BUTTON_PRESS = 4;
    public static final int EVENT_BUTTON_RELEASE = 5;
    public static final int EVENT_MOTION = 6;
    public static final int EVENT_RAW_KEY_PRESS = 13;
    public static final int EVENT_RAW_KEY_RELEASE = 14;
    public static final int EVENT_RAW_BUTTON_PRESS = 15;
    public static final int EVENT_RAW_BUTTON_RELEASE = 16;
    public static final int EVENT_RAW_MOTION = 17;

    private static final int MAX_BUTTONS = 7;
    // XI 1.x requests with replies (the ones handled above aside); the reply type byte equals the request's minor.
    private static final String LEGACY_REPLY_MINORS = ",5,9,10,13,20,22,24,26,27,28,29,30,34,35,36,39,";

    private static abstract class Minor {
        private static final int QUERY_POINTER = 40;
        private static final int WARP_POINTER = 41;
        private static final int SELECT_EVENTS = 46;
        private static final int QUERY_VERSION = 47;
        private static final int QUERY_DEVICE = 48;
        private static final int GET_FOCUS = 50;
        private static final int GRAB_DEVICE = 51;
        private static final int PASSIVE_GRAB_DEVICE = 54;
        private static final int LIST_PROPERTIES = 56;
        private static final int GET_PROPERTY = 59;
        private static final int GET_SELECTED_EVENTS = 60;
        private static final int GET_CLIENT_POINTER = 45;
    }

    private static class Selection {
        private final XClient client;
        private final Window window;
        private final int deviceId;
        private final byte[] mask;

        private Selection(XClient client, Window window, int deviceId, byte[] mask) {
            this.client = client;
            this.window = window;
            this.deviceId = deviceId;
            this.mask = mask;
        }

        private boolean wants(int eventType, int masterId) {
            if (deviceId != 0 && deviceId != 1 && deviceId != masterId) return false;
            int index = eventType >> 3;
            return index < mask.length && (mask[index] & (1 << (eventType & 7))) != 0;
        }
    }

    private final XServer xServer;
    private final ArrayList<Selection> selections = new ArrayList<>();
    private int lastRootX;
    private int lastRootY;

    public XInput2Extension(XServer xServer) {
        this.xServer = xServer;
        xServer.windowManager.addOnResourceLifecycleListener(this);
    }

    @Override
    public String getName() {
        return "XInputExtension";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return 0;
    }

    @Override
    public byte getFirstEventId() {
        return FIRST_EVENT_ID;
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (!(resource instanceof Window)) return;
        synchronized (selections) {
            for (int i = selections.size() - 1; i >= 0; i--) {
                if (selections.get(i).window == resource) selections.remove(i);
            }
        }
    }

    public void removeClient(XClient client) {
        synchronized (selections) {
            for (int i = selections.size() - 1; i >= 0; i--) {
                if (selections.get(i).client == client) selections.remove(i);
            }
        }
    }

    // ---- requests -------------------------------------------------------------------------------------------

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int minor = client.getRequestData() & 0xff;
        switch (minor) {
            case 1: // XGetExtensionVersion (XI 1.x): libXi asks first
                client.skipRequest();
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                    outputStream.writeByte((byte)1); // reply type: GetExtensionVersion
                    outputStream.writeShort(client.getSequenceNumber());
                    outputStream.writeInt(0);
                    outputStream.writeShort((short)2);
                    outputStream.writeShort((short)2);
                    outputStream.writeByte((byte)1); // present
                    outputStream.writePad(19);
                }
                break;
            case 3: // XOpenDevice (XI 1.x): opens a device with no input classes
                client.skipRequest();
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                    outputStream.writeByte((byte)3);
                    outputStream.writeShort(client.getSequenceNumber());
                    outputStream.writeInt(0);
                    outputStream.writeByte((byte)0); // classes
                    outputStream.writePad(23);
                }
                break;
            case 7: // XGetSelectedExtensionEvents (XI 1.x): nothing selected
                client.skipRequest();
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                    outputStream.writeByte((byte)7);
                    outputStream.writeShort(client.getSequenceNumber());
                    outputStream.writeInt(0);
                    outputStream.writeInt(0);
                    outputStream.writePad(20);
                }
                break;
            case 2: // XListInputDevices (XI 1.x): the two master devices, no input classes
                client.skipRequest();
                listInputDevices(client, outputStream);
                break;
            case Minor.QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case Minor.QUERY_DEVICE:
                queryDevice(client, inputStream, outputStream);
                break;
            case Minor.SELECT_EVENTS:
                selectEvents(client, inputStream);
                break;
            case Minor.GET_SELECTED_EVENTS:
                getSelectedEvents(client, inputStream, outputStream);
                break;
            case Minor.QUERY_POINTER:
                queryPointer(client, inputStream, outputStream);
                break;
            case Minor.WARP_POINTER:
                warpPointer(client, inputStream);
                break;
            case Minor.GET_CLIENT_POINTER:
                client.skipRequest();
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                    outputStream.writeByte((byte)0);
                    outputStream.writeShort(client.getSequenceNumber());
                    outputStream.writeInt(0);
                    outputStream.writeByte((byte)1); // set
                    outputStream.writeByte((byte)0);
                    outputStream.writeShort((short)DEVICE_MASTER_POINTER);
                    outputStream.writePad(20);
                }
                break;
            case Minor.GET_FOCUS: {
                client.skipRequest();
                Window focus = xServer.windowManager.getFocusedWindow();
                simpleReply(client, outputStream, 0, focus != null ? focus.id : 1);
                break;
            }
            case Minor.GRAB_DEVICE:
                client.skipRequest();
                simpleReply(client, outputStream, 0, 0); // status: Success
                break;
            case Minor.PASSIVE_GRAB_DEVICE: {
                // XIPassiveGrabDevice (XIGrabButton): parse the grab window and event mask so SDL's
                // button grabs are honoured.  Without this, SDL registers buttons via this request
                // (not XISelectEvents) and we answer "success" but deliver nothing → clicks ignored.
                int gwId = xServer.windowManager.rootWindow.id;
                int pgDeviceId = 0;
                byte[] pgMask = new byte[0];
                int grabType = 0;
                try {
                    inputStream.skip(4); // time
                    gwId = inputStream.readInt(); // grab_window
                    inputStream.skip(4); // cursor
                    inputStream.skip(4); // detail (button number)
                    pgDeviceId = inputStream.readUnsignedShort();
                    inputStream.skip(2); // num_modifiers
                    int maskLen = inputStream.readUnsignedShort();
                    grabType = inputStream.readUnsignedByte();
                    inputStream.skip(3); // grab_mode, paired_device_mode, owner_events
                    inputStream.skip(2); // pad
                    pgMask = new byte[maskLen * 4];
                    inputStream.read(pgMask);
                } catch (Exception ignored) {}
                client.skipRequest();

                if (grabType == 0) { // GrabtypeButton
                    // Force XI_ButtonPress (bit 4) and XI_ButtonRelease (bit 5) into the mask.
                    if (pgMask.length == 0) pgMask = new byte[1];
                    pgMask[0] |= (byte)((1 << EVENT_BUTTON_PRESS) | (1 << EVENT_BUTTON_RELEASE));
                    Window gw = xServer.windowManager.getWindow(gwId);
                    if (gw == null) gw = xServer.windowManager.rootWindow;
                    final Window fgw = gw;
                    final int fDev = pgDeviceId;
                    final byte[] fMask = pgMask;
                    android.util.Log.d("XTrace", "XI PassiveGrab button gwId=" + gwId
                            + " deviceId=" + fDev + " mask[0]=" + String.format("%02x", fMask[0] & 0xff));
                    synchronized (selections) {
                        for (int j = selections.size() - 1; j >= 0; j--) {
                            Selection old = selections.get(j);
                            if (old.client == client && old.window == fgw && old.deviceId == fDev) selections.remove(j);
                        }
                        selections.add(new Selection(client, fgw, fDev, fMask));
                    }
                }
                simpleReply(client, outputStream, 0, 0);
                break;
            }
            case Minor.LIST_PROPERTIES:
                client.skipRequest();
                simpleReply(client, outputStream, 0, 0); // zero properties
                break;
            case Minor.GET_PROPERTY:
                client.skipRequest();
                simpleReply(client, outputStream, 0, 0);
                break;
            default:
                if (LEGACY_REPLY_MINORS.indexOf("," + minor + ",") >= 0) {
                    // An XI 1.x request that expects a reply: answer "nothing" so the client does not wait for ever.
                    client.skipRequest();
                    try (XStreamLock lock = outputStream.lock()) {
                        outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                        outputStream.writeByte((byte)minor);
                        outputStream.writeShort(client.getSequenceNumber());
                        outputStream.writeInt(0);
                        outputStream.writePad(24);
                    }
                    break;
                }
                // SetClientPointer, SetFocus, UngrabDevice, AllowEvents, ChangeCursor, ChangeHierarchy and the
                // property setters have no reply.
                client.skipRequest();
                break;
        }
    }

    /** A 32-byte reply whose first 4 bytes after the header are {@code first} (u32). */
    private void simpleReply(XClient client, XOutputStream outputStream, int extraWords, int first) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(extraWords);
            outputStream.writeInt(first);
            outputStream.writePad(20);
        }
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        client.skipRequest();
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short)2);
            outputStream.writeShort((short)2);
            outputStream.writePad(20);
        }
    }

    private static int padded(int length) {
        return (length + 3) & ~3;
    }

    private void writeDevice(XOutputStream out, int id, int type, int attachment, String name, boolean pointer) {
        out.writeShort((short)id);
        out.writeShort((short)type);
        out.writeShort((short)attachment);
        out.writeShort((short)(pointer ? 3 : 1));
        out.writeShort((short)name.length());
        out.writeByte((byte)1);
        out.writeByte((byte)0);
        out.writeString8(name); // pads to 4 bytes itself

        if (pointer) {
            // Buttons: header 2 words + state 1 word + one label atom per button.
            out.writeShort((short)1);
            out.writeShort((short)(3 + MAX_BUTTONS));
            out.writeShort((short)id);
            out.writeShort((short)MAX_BUTTONS);
            out.writeInt(0);
            out.writePad(MAX_BUTTONS * 4);
            for (int axis = 0; axis < 2; axis++) {
                int size = axis == 0 ? xServer.screenInfo.width : xServer.screenInfo.height;
                out.writeShort((short)2);
                out.writeShort((short)11);
                out.writeShort((short)id);
                out.writeShort((short)axis);
                out.writeInt(0); // label
                out.writeFP3232(0);
                out.writeFP3232(size);
                out.writeFP3232(axis == 0 ? xServer.pointer.getClampedX() : xServer.pointer.getClampedY());
                out.writeInt(1000); // resolution
                out.writeByte((byte)1); // absolute
                out.writePad(3);
            }
        }
        else {
            int keys = 248;
            out.writeShort((short)0);
            out.writeShort((short)(2 + keys));
            out.writeShort((short)id);
            out.writeShort((short)keys);
            for (int code = 8; code < 8 + keys; code++) out.writeInt(code);
        }
    }

    private static int deviceSize(String name, boolean pointer) {
        int size = 12 + padded(name.length());
        size += pointer ? (3 + MAX_BUTTONS) * 4 + 2 * 44 : (2 + 248) * 4;
        return size;
    }

    /**
     * XI 1.x ListInputDevices: devices (8 bytes each), their classes (none here) and the names (a length byte and the
     * characters, no padding between them). Two readers disagree about an empty list, so the list is never empty:
     * libXi (Steam's own windows, SDL) does not read the data of a reply that has no devices, and Chromium (the web
     * helper, every CEF) wants the reply's length to cover one byte past the names, rounded up to a word.
     */
    private void listInputDevices(XClient client, XOutputStream outputStream) throws IOException {
        final String[] names = {"Virtual core pointer", "Virtual core keyboard"};
        final int[] ids = {DEVICE_MASTER_POINTER, DEVICE_MASTER_KEYBOARD};
        final int[] use = {0, 1}; // IsXPointer, IsXKeyboard

        int data = names.length * 8;
        for (String name : names) data += 1 + name.length();
        int total = (32 + data + 1 + 3) & ~3;
        int extra = total - 32;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)2); // reply type: ListInputDevices
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(extra / 4);
            outputStream.writeByte((byte)names.length);
            outputStream.writePad(23);
            for (int i = 0; i < names.length; i++) {
                outputStream.writeInt(0); // device type atom: none
                outputStream.writeByte((byte)ids[i]);
                outputStream.writeByte((byte)0); // no classes
                outputStream.writeByte((byte)use[i]);
                outputStream.writeByte((byte)0);
            }
            int written = names.length * 8;
            for (String name : names) {
                outputStream.writeByte((byte)name.length());
                for (int k = 0; k < name.length(); k++) outputStream.writeByte((byte)name.charAt(k));
                written += 1 + name.length();
            }
            outputStream.writePad(extra - written);
        }
    }

    private void queryDevice(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int deviceId = inputStream.readUnsignedShort();
        client.skipRequest();

        final String[] names = {"Virtual core pointer", "Virtual core keyboard", "skyNET virtual pointer", "skyNET virtual keyboard"};
        final int[] ids = {DEVICE_MASTER_POINTER, DEVICE_MASTER_KEYBOARD, DEVICE_SLAVE_POINTER, DEVICE_SLAVE_KEYBOARD};
        final int[] types = {1, 2, 3, 4};
        final int[] attachments = {DEVICE_MASTER_KEYBOARD, DEVICE_MASTER_POINTER, DEVICE_MASTER_POINTER, DEVICE_MASTER_KEYBOARD};

        ArrayList<Integer> chosen = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            boolean master = i < 2;
            if (deviceId == 0 || (deviceId == 1 && master) || deviceId == ids[i]) chosen.add(i);
        }

        int bytes = 0;
        for (int i : chosen) bytes += deviceSize(names[i], i % 2 == 0);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(bytes / 4);
            outputStream.writeShort((short)chosen.size());
            outputStream.writePad(22);
            for (int i : chosen) writeDevice(outputStream, ids[i], types[i], attachments[i], names[i], i % 2 == 0);
        }
    }

    private void selectEvents(XClient client, XInputStream inputStream) {
        int windowId = inputStream.readInt();
        int count = inputStream.readUnsignedShort();
        inputStream.skip(2);

        Window window = xServer.windowManager.getWindow(windowId);
        for (int i = 0; i < count; i++) {
            int deviceId = inputStream.readUnsignedShort();
            int words = inputStream.readUnsignedShort();
            byte[] mask = new byte[words * 4];
            inputStream.read(mask);
            if (com.winlator.cmod.xserver.XClientRequestHandler.trace) {
                StringBuilder hex = new StringBuilder();
                for (byte b : mask) hex.append(String.format("%02x", b));
                android.util.Log.d("XTrace", "XISelectEvents client=" + client.resourceIDBase + " window=" + windowId + (window == null ? "(gone)" : "") + " device=" + deviceId + " mask=" + hex);
            }
            if (window == null) continue;

            synchronized (selections) {
                for (int j = selections.size() - 1; j >= 0; j--) {
                    Selection old = selections.get(j);
                    if (old.client == client && old.window == window && old.deviceId == deviceId) selections.remove(j);
                }
                boolean empty = true;
                for (byte b : mask) if (b != 0) empty = false;
                if (!empty) selections.add(new Selection(client, window, deviceId, mask));
            }
        }
        client.skipRequest();
    }

    private void getSelectedEvents(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        Window window = xServer.windowManager.getWindow(inputStream.readInt());
        client.skipRequest();

        ArrayList<Selection> mine = new ArrayList<>();
        synchronized (selections) {
            for (Selection s : selections) if (s.client == client && s.window == window) mine.add(s);
        }
        int bytes = 0;
        for (Selection s : mine) bytes += 4 + s.mask.length;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(bytes / 4);
            outputStream.writeShort((short)mine.size());
            outputStream.writePad(22);
            for (Selection s : mine) {
                outputStream.writeShort((short)s.deviceId);
                outputStream.writeShort((short)(s.mask.length / 4));
                outputStream.write(s.mask);
            }
        }
    }

    private void queryPointer(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        Window window = xServer.windowManager.getWindow(inputStream.readInt());
        client.skipRequest();

        int x = xServer.pointer.getClampedX();
        int y = xServer.pointer.getClampedY();
        short[] local = window != null ? window.rootPointToLocal((short)x, (short)y) : new short[]{(short)x, (short)y};
        Window point = xServer.inputDeviceManager.getPointWindow();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(7);
            outputStream.writeInt(xServer.windowManager.rootWindow.id);
            outputStream.writeInt(point != null && point != xServer.windowManager.rootWindow ? point.id : 0);
            outputStream.writeInt(x << 16);
            outputStream.writeInt(y << 16);
            outputStream.writeInt(local[0] << 16);
            outputStream.writeInt(local[1] << 16);
            outputStream.writeByte((byte)1);
            outputStream.writeByte((byte)0);
            outputStream.writeShort((short)1);
            int mods = modifiers();
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writeInt(mods);
            outputStream.writePad(4);
            outputStream.writeInt(buttonState(null));
        }
    }

    private void warpPointer(XClient client, XInputStream inputStream) {
        inputStream.skip(4); // source window
        Window destination = xServer.windowManager.getWindow(inputStream.readInt());
        inputStream.skip(8 + 4); // source x/y, source width/height
        int dx = inputStream.readInt() >> 16;
        int dy = inputStream.readInt() >> 16;
        client.skipRequest();

        if (destination != null) {
            short[] origin = destination.rootPointToLocal((short)0, (short)0);
            xServer.injectPointerMove(dx - origin[0], dy - origin[1]);
        }
        else xServer.injectPointerMoveDelta(dx, dy);
    }

    // ---- events ---------------------------------------------------------------------------------------------

    private int modifiers() {
        return xServer.keyboard.getModifiersMask().getBits() & 0xff;
    }

    /** One 32-bit button state word: bit n is set while button n is held, except {@code exclude}. */
    private int buttonState(Pointer.Button exclude) {
        int state = 0;
        for (Pointer.Button button : Pointer.Button.values()) {
            if (button != exclude && xServer.pointer.isButtonPressed(button)) state |= 1 << button.code();
        }
        return state;
    }

    private static class XI2Event extends Event {
        private final byte extension;
        private final int evType;
        private final int deviceId;
        private final int sourceId;
        private final int detail;
        private final int rootId;
        private final int eventId;
        private final int childId;
        private final int rootX;
        private final int rootY;
        private final int eventX;
        private final int eventY;
        private final int buttons;
        private final int mods;
        private final boolean valuators;
        private final boolean raw;
        private final double dx;
        private final double dy;
        private final int time = (int)System.currentTimeMillis();

        private XI2Event(byte extension, int evType, int deviceId, int sourceId, int detail, Window root, Window event, Window child,
                         short rootX, short rootY, short eventX, short eventY, int buttons, int mods, boolean valuators, boolean raw, double dx, double dy) {
            super(GENERIC_EVENT);
            this.extension = extension;
            this.evType = evType;
            this.deviceId = deviceId;
            this.sourceId = sourceId;
            this.detail = detail;
            this.rootId = root != null ? root.id : 0;
            this.eventId = event != null ? event.id : 0;
            this.childId = child != null ? child.id : 0;
            this.rootX = rootX;
            this.rootY = rootY;
            this.eventX = eventX;
            this.eventY = eventY;
            this.buttons = buttons;
            this.mods = mods;
            this.valuators = valuators;
            this.raw = raw;
            this.dx = dx;
            this.dy = dy;
        }

        @Override
        public void send(short sequenceNumber, XOutputStream out) throws IOException {
            try (XStreamLock lock = out.lock()) {
                if (raw) {
                    // xXIRawEvent: 36 bytes, then the valuator mask and, for each set bit, the value and the raw value.
                    int words = valuators ? 1 + 8 : 0;
                    out.writeByte((byte)GENERIC_EVENT);
                    out.writeByte(extension);
                    out.writeShort(sequenceNumber);
                    out.writeInt(1 + words);
                    out.writeShort((short)evType);
                    out.writeShort((short)deviceId);
                    out.writeInt(time);
                    out.writeInt(detail);
                    out.writeShort((short)sourceId);
                    out.writeShort((short)(valuators ? 1 : 0));
                    out.writeInt(0); // flags
                    out.writePad(8);
                    if (valuators) {
                        out.writeInt(3);
                        out.writeFP3232(dx);
                        out.writeFP3232(dy);
                        out.writeFP3232(dx);
                        out.writeFP3232(dy);
                    }
                    return;
                }

                int valuatorWords = valuators ? 1 : 0;
                // XIDeviceEvent variable part: 4 (coords) + 2 (lens/src/pad) + 1 (flags) +
                //   4 (mods/XIModifierState) + 1 (group/XIGroupState, four bytes) + 1 (buttons[]) = 13 words.
                int length = 13 + valuatorWords + (valuators ? 4 : 0);
                out.writeByte((byte)GENERIC_EVENT);
                out.writeByte(extension);
                out.writeShort(sequenceNumber);
                out.writeInt(length);
                out.writeShort((short)evType);
                out.writeShort((short)deviceId);
                out.writeInt(time);
                out.writeInt(detail);
                out.writeInt(rootId);
                out.writeInt(eventId);
                out.writeInt(childId);
                out.writeInt(rootX << 16);
                out.writeInt(rootY << 16);
                out.writeInt(eventX << 16);
                out.writeInt(eventY << 16);
                out.writeShort((short)1); // buttons_len
                out.writeShort((short)valuatorWords);
                out.writeShort((short)sourceId);
                out.writeShort((short)0);
                out.writeInt(0); // flags
                // XIModifierState mods (4 × INT32)
                out.writeInt(0); out.writeInt(0); out.writeInt(0); out.writeInt(mods);
                // XIGroupState group (4 × CARD8)
                out.writeInt(0);
                out.writeInt(buttons);
                if (valuators) {
                    out.writeInt(3);
                    out.writeFP3232(rootX);
                    out.writeFP3232(rootY);
                }
            }
        }
    }

    private void deliver(int evType, int masterId, boolean rawEvent, Window start, XI2Event event) {
        ArrayList<XClient> done = new ArrayList<>();
        Window root = xServer.windowManager.rootWindow;
        for (Window window = start; window != null; window = window.getParent()) {
            if (rawEvent && window != root) continue;
            ArrayList<XClient> targets = new ArrayList<>();
            synchronized (selections) {
                for (Selection s : selections) {
                    if (s.window == window && !done.contains(s.client) && s.wants(evType, masterId)) {
                        targets.add(s.client);
                        done.add(s.client);
                    }
                }
            }
            for (XClient target : targets) target.sendEvent(event);
        }
    }

    /** XI_Enter / XI_Leave: delivered to the event window only. */
    public void onCrossing(boolean enter, Window window, int detail, int mode) {
        sendCrossing(enter ? 7 : 8, window, detail, mode, 0);
    }

    /** XI_FocusIn / XI_FocusOut. */
    public void onFocus(boolean in, Window window) {
        sendCrossing(in ? 9 : 10, window, 3, 0, 1);
    }

    private void sendCrossing(int evType, Window window, int detail, int mode, int focus) {
        if (!anySelection() || window == null) return;
        short x = xServer.pointer.getClampedX();
        short y = xServer.pointer.getClampedY();
        short[] local = window.rootPointToLocal(x, y);
        int rootId = xServer.windowManager.rootWindow.id;
        int buttons = buttonState(null);
        int mods = modifiers();
        ArrayList<XClient> targets = new ArrayList<>();
        synchronized (selections) {
            for (Selection s : selections) {
                if (s.window == window && (s.wants(evType, DEVICE_MASTER_POINTER) || s.wants(evType, DEVICE_MASTER_KEYBOARD)) && !targets.contains(s.client)) targets.add(s.client);
            }
        }
        if (targets.isEmpty()) return;
        Event event = new Event(GENERIC_EVENT) {
            private final int time = (int)System.currentTimeMillis();

            @Override
            public void send(short sequenceNumber, XOutputStream out) throws IOException {
                try (XStreamLock lock = out.lock()) {
                    out.writeByte((byte)GENERIC_EVENT);
                    out.writeByte(MAJOR_OPCODE);
                    out.writeShort(sequenceNumber);
                    out.writeInt(10 + 1); // 72 bytes beyond the 32 + one word of buttons
                    out.writeShort((short)evType);
                    out.writeShort((short)DEVICE_MASTER_POINTER);
                    out.writeInt(time);
                    out.writeShort((short)DEVICE_SLAVE_POINTER);
                    out.writeByte((byte)mode);
                    out.writeByte((byte)detail);
                    out.writeInt(rootId);
                    out.writeInt(window.id);
                    out.writeInt(0);
                    out.writeInt(x << 16);
                    out.writeInt(y << 16);
                    out.writeInt(local[0] << 16);
                    out.writeInt(local[1] << 16);
                    out.writeByte((byte)1); // same screen
                    out.writeByte((byte)focus);
                    out.writeShort((short)1); // buttons_len
                    out.writeInt(0);
                    out.writeInt(0);
                    out.writeInt(0);
                    out.writeInt(mods);
                    out.writePad(4); // group
                    out.writeInt(buttons);
                }
            }
        };
        for (XClient target : targets) target.sendEvent(event);
    }

    private boolean anySelection() {
        synchronized (selections) {
            return !selections.isEmpty();
        }
    }

    private XI2Event deviceEvent(int evType, int deviceId, int sourceId, int detail, Window eventWindow, Window child, int buttons, boolean valuators) {
        short x = xServer.pointer.getClampedX();
        short y = xServer.pointer.getClampedY();
        short[] local = eventWindow.rootPointToLocal(x, y);
        return new XI2Event(MAJOR_OPCODE, evType, deviceId, sourceId, detail, xServer.windowManager.rootWindow, eventWindow, child,
                x, y, local[0], local[1], buttons, modifiers(), valuators, false, 0, 0);
    }

    private void deliverPointer(int evType, int rawType, int detail, Pointer.Button button, boolean valuators, double dx, double dy) {
        if (!anySelection()) return;
        Window point = xServer.inputDeviceManager.getPointWindow();
        if (point == null) point = xServer.windowManager.rootWindow;

        int buttons = button != null ? buttonState(evType == EVENT_BUTTON_PRESS ? button : null) : buttonState(null);
        for (Window window = point; window != null; window = window.getParent()) {
            Window child = window != point ? point : null;
            XI2Event event = deviceEvent(evType, DEVICE_MASTER_POINTER, DEVICE_SLAVE_POINTER, detail, window, child, buttons, valuators);
            // Selections are tested one window at a time so each client gets the event from its deepest window.
            ArrayList<XClient> targets = new ArrayList<>();
            synchronized (selections) {
                for (Selection s : selections) {
                    if (s.window == window && s.wants(evType, DEVICE_MASTER_POINTER)) targets.add(s.client);
                }
            }
            for (XClient target : targets) {
                if (alreadyServed(target, point, window, evType)) continue;
                if (com.winlator.cmod.xserver.XClientRequestHandler.trace && evType != EVENT_MOTION) {
                    android.util.Log.d("XTrace", "XI event " + evType + " detail=" + detail + " -> client=" + target.resourceIDBase + " window=" + window.id);
                }
                target.sendEvent(event);
            }
        }

        XI2Event raw = new XI2Event(MAJOR_OPCODE, rawType, DEVICE_MASTER_POINTER, DEVICE_SLAVE_POINTER, detail, null, null, null,
                (short)0, (short)0, (short)0, (short)0, 0, 0, valuators, true, dx, dy);
        deliver(rawType, DEVICE_MASTER_POINTER, true, xServer.windowManager.rootWindow, raw);
    }

    /** True when {@code client} also has a selection on a window deeper than {@code window} on the path from {@code point}. */
    private boolean alreadyServed(XClient client, Window point, Window window, int evType) {
        for (Window deeper = point; deeper != null && deeper != window; deeper = deeper.getParent()) {
            synchronized (selections) {
                for (Selection s : selections) {
                    if (s.window == deeper && s.client == client && s.wants(evType, DEVICE_MASTER_POINTER)) return true;
                }
            }
        }
        return false;
    }

    public void onButton(Pointer.Button button, boolean pressed) {
        int evType = pressed ? EVENT_BUTTON_PRESS : EVENT_BUTTON_RELEASE;
        // Always-on diagnostic: count how many clients will receive this XI button event.
        int count = 0;
        Window point = xServer.inputDeviceManager.getPointWindow();
        if (point == null) point = xServer.windowManager.rootWindow;
        synchronized (selections) {
            for (Selection s : selections) {
                for (Window w = point; w != null; w = w.getParent()) {
                    if (s.window == w && s.wants(evType, DEVICE_MASTER_POINTER)) { count++; break; }
                }
            }
        }
        StringBuilder selInfo = new StringBuilder();
        synchronized (selections) {
            for (Selection s : selections) {
                selInfo.append(s.window != null ? s.window.id : 0)
                        .append("/d").append(s.deviceId)
                        .append(s.wants(evType, DEVICE_MASTER_POINTER) ? "*" : "").append(",");
            }
        }
        android.util.Log.d("XTrace", "XI button " + (pressed?"press":"release") + " btn=" + button.code()
                + " pointWin=" + (point != null ? point.id : 0)
                + " sels=[" + selInfo + "] willDeliver=" + count);
        deliverPointer(evType, pressed ? EVENT_RAW_BUTTON_PRESS : EVENT_RAW_BUTTON_RELEASE,
                button.code(), button, false, 0, 0);
    }

    public void onMotion(short x, short y) {
        int dx = x - lastRootX;
        int dy = y - lastRootY;
        lastRootX = x;
        lastRootY = y;
        deliverPointer(EVENT_MOTION, EVENT_RAW_MOTION, 0, null, true, dx, dy);
    }

    public void onKey(byte keycode, boolean pressed) {
        if (!anySelection()) return;
        Window focused = xServer.windowManager.getFocusedWindow();
        Window point = xServer.inputDeviceManager.getPointWindow();
        Window start = focused != null && point != null && focused.isAncestorOf(point) ? point : focused;
        if (start == null) start = xServer.windowManager.rootWindow;

        int evType = pressed ? EVENT_KEY_PRESS : EVENT_KEY_RELEASE;
        for (Window window = start; window != null; window = window.getParent()) {
            XI2Event event = deviceEvent(evType, DEVICE_MASTER_KEYBOARD, DEVICE_SLAVE_KEYBOARD, keycode & 0xff, window, window != start ? start : null, buttonState(null), false);
            ArrayList<XClient> targets = new ArrayList<>();
            synchronized (selections) {
                for (Selection s : selections) {
                    if (s.window == window && s.wants(evType, DEVICE_MASTER_KEYBOARD)) targets.add(s.client);
                }
            }
            for (XClient target : targets) {
                boolean deeper = false;
                for (Window w = start; w != null && w != window; w = w.getParent()) {
                    synchronized (selections) {
                        for (Selection s : selections) {
                            if (s.window == w && s.client == target && s.wants(evType, DEVICE_MASTER_KEYBOARD)) deeper = true;
                        }
                    }
                }
                if (!deeper) target.sendEvent(event);
            }
        }

        int rawType = pressed ? EVENT_RAW_KEY_PRESS : EVENT_RAW_KEY_RELEASE;
        XI2Event raw = new XI2Event(MAJOR_OPCODE, rawType, DEVICE_MASTER_KEYBOARD, DEVICE_SLAVE_KEYBOARD, keycode & 0xff, null, null, null,
                (short)0, (short)0, (short)0, (short)0, 0, 0, false, true, 0, 0);
        deliver(rawType, DEVICE_MASTER_KEYBOARD, true, xServer.windowManager.rootWindow, raw);
    }
}
