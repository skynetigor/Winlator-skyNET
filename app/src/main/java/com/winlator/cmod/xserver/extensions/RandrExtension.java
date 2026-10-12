package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.ScreenInfo;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.XRequestError;
import com.winlator.cmod.xserver.events.Event;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * RandR. By default the 1.1 subset a program that only asks the screen's size and refresh rate needs. After
 * {@link #setModesEnabled}, RandR 1.3: one screen, one output, one CRTC and, as modes, the screen's own size and every
 * standard resolution that is not larger than it. Setting a mode (SetCrtcConfig, SetScreenSize, SetScreenConfig) gives the
 * screen that size, so that a game can offer its own resolution list and pick one. The screen never grows past its own size.
 */
public class RandrExtension implements Extension {
    public static final byte MAJOR_OPCODE = -106;

    private static final byte FIRST_EVENT_ID = 65;
    private static final short RR_ROTATE_0 = 1;
    private static final byte SET_OF_ROTATIONS = (byte)RR_ROTATE_0;

    private static abstract class ClientOpcodes {
        private static final int QUERY_VERSION = 0;
        private static final int SET_SCREEN_CONFIG = 2;
        private static final int SELECT_INPUT = 4;
        private static final int GET_SCREEN_INFO = 5;
        private static final int GET_SCREEN_SIZE_RANGE = 6;
        private static final int SET_SCREEN_SIZE = 7;
        private static final int GET_SCREEN_RESOURCES = 8;
        private static final int GET_OUTPUT_INFO = 9;
        private static final int LIST_OUTPUT_PROPERTIES = 10;
        private static final int QUERY_OUTPUT_PROPERTY = 11;
        private static final int CONFIGURE_OUTPUT_PROPERTY = 12;
        private static final int CHANGE_OUTPUT_PROPERTY = 13;
        private static final int DELETE_OUTPUT_PROPERTY = 14;
        private static final int GET_OUTPUT_PROPERTY = 15;
        private static final int CREATE_MODE = 16;
        private static final int DESTROY_MODE = 17;
        private static final int ADD_OUTPUT_MODE = 18;
        private static final int DELETE_OUTPUT_MODE = 19;
        private static final int GET_CRTC_INFO = 20;
        private static final int SET_CRTC_CONFIG = 21;
        private static final int GET_CRTC_GAMMA_SIZE = 22;
        private static final int GET_CRTC_GAMMA = 23;
        private static final int SET_CRTC_GAMMA = 24;
        private static final int GET_SCREEN_RESOURCES_CURRENT = 25;
        private static final int SET_CRTC_TRANSFORM = 26;
        private static final int GET_CRTC_TRANSFORM = 27;
        private static final int GET_PANNING = 28;
        private static final int SET_PANNING = 29;
        private static final int SET_OUTPUT_PRIMARY = 30;
        private static final int GET_OUTPUT_PRIMARY = 31;
    }

    /** The standard resolutions offered when they are not larger than the screen's own size (which is offered too). */
    private static final int[][] STANDARD_SIZES = {
            {640, 360}, {640, 480}, {720, 480}, {780, 360}, {800, 600}, {854, 480}, {960, 544}, {1024, 768}, {1152, 864},
            {1280, 576}, {1280, 720}, {1280, 768}, {1280, 800}, {1280, 960}, {1280, 1024}, {1360, 768}, {1366, 768},
            {1440, 900}, {1600, 720}, {1600, 900}, {1600, 1200}, {1680, 1050}, {1920, 1080}, {1920, 1200}, {2560, 1440}
    };

    // The ids the screen's one CRTC, one output and the modes have (a server's own XIDs; clients only hand them back).
    private static final int CRTC_ID = 0x300;
    private static final int OUTPUT_ID = 0x301;
    private static final int MODE_ID_BASE = 0x400;
    private static final int GAMMA_SIZE = 256;

    private static final int NOTIFY_SCREEN_CHANGE = 1;
    private static final int NOTIFY_CRTC_CHANGE = 2;

    private static final class Mode {
        final int width;
        final int height;

        Mode(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static final class Subscription {
        final XClient client;
        final Window window;
        int mask;

        Subscription(XClient client, Window window, int mask) {
            this.client = client;
            this.window = window;
            this.mask = mask;
        }
    }

    private final XServer xServer;
    private final ScreenInfo screenInfo;
    private volatile short[] refreshRates = {60};
    private volatile short currentRate = 60;
    private volatile boolean modesEnabled;
    /** The size the screen had when the modes were turned on: the largest it can be. */
    private volatile int maxWidth = 8192;
    private volatile int maxHeight = 8192;
    private final List<Mode> modes = new ArrayList<>();
    private final List<Subscription> subscriptions = new ArrayList<>();
    private volatile int configTimestamp = 1;

    public RandrExtension(XServer xServer) {
        this.xServer = xServer;
        this.screenInfo = xServer.screenInfo;
    }

    public void setRefreshRates(short[] rates, short activeRate) {
        if (rates == null || rates.length == 0) return;

        short[] copy = rates.clone();
        refreshRates = copy;

        currentRate = copy[0];
    }

    /** Offers the screen's own size and the standard resolutions not larger than it as modes; RandR is then 1.3. */
    public synchronized void setModesEnabled(boolean enabled) {
        modesEnabled = enabled;
        if (enabled && modes.isEmpty()) {
            maxWidth = screenInfo.width;
            maxHeight = screenInfo.height;
            for (int[] size : STANDARD_SIZES) addMode(size[0], size[1]);
            addMode(screenInfo.width, screenInfo.height);
        }
    }

    private synchronized void addMode(int width, int height) {
        if (width < 64 || height < 64 || width > maxWidth || height > maxHeight) return;
        for (Mode mode : modes) if (mode.width == width && mode.height == height) return;
        modes.add(new Mode(width, height));
        modes.sort((a, b) -> a.width != b.width ? Integer.compare(a.width, b.width) : Integer.compare(a.height, b.height));
    }

    @Override
    public String getName() {
        return "RANDR";
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

    // ---- Helpers -----------------------------------------------------------------------------------------------------

    private static int now() {
        return (int)(System.currentTimeMillis() & 0x7fffffff);
    }

    private synchronized int modeIdOf(int width, int height) {
        addMode(width, height);
        for (int i = 0; i < modes.size(); i++) {
            if (modes.get(i).width == width && modes.get(i).height == height) return MODE_ID_BASE + i;
        }
        return MODE_ID_BASE;
    }

    private int currentModeId() {
        return modeIdOf(screenInfo.width, screenInfo.height);
    }

    private synchronized Mode modeById(int id) {
        int index = id - MODE_ID_BASE;
        return index >= 0 && index < modes.size() ? modes.get(index) : null;
    }

    private static short mm(int pixels) {
        return (short)Math.max(1, Math.round(pixels * 25.4f / 96f));
    }

    private static String modeName(Mode mode) {
        return mode.width + "x" + mode.height;
    }

    private void writeReplyHeader(XClient client, XOutputStream outputStream, int data, int lengthWords) {
        outputStream.writeByte(RESPONSE_CODE_SUCCESS);
        outputStream.writeByte((byte)data);
        outputStream.writeShort(client.getSequenceNumber());
        outputStream.writeInt(lengthWords);
    }

    private void writeModeInfo(XOutputStream outputStream, int id, Mode mode) {
        int hTotal = mode.width + 64;
        int vTotal = mode.height + 20;
        outputStream.writeInt(id);
        outputStream.writeShort((short)mode.width);
        outputStream.writeShort((short)mode.height);
        outputStream.writeInt(hTotal * vTotal * currentRate);   // dot clock
        outputStream.writeShort((short)(mode.width + 16));      // hsync start
        outputStream.writeShort((short)(mode.width + 32));      // hsync end
        outputStream.writeShort((short)hTotal);
        outputStream.writeShort((short)0);                      // hskew
        outputStream.writeShort((short)(mode.height + 3));      // vsync start
        outputStream.writeShort((short)(mode.height + 8));      // vsync end
        outputStream.writeShort((short)vTotal);
        outputStream.writeShort((short)modeName(mode).length());
        outputStream.writeInt(0);                               // flags
    }

    // ---- Version, 1.1 style screen info ------------------------------------------------------------------------------

    private void queryVersion(XClient client, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0, 0);
            outputStream.writeInt(1);
            outputStream.writeInt(modesEnabled ? 3 : 1);
            outputStream.writePad(16);
        }
    }

    private void getScreenInfo(XClient client, ByteBuffer body, XOutputStream outputStream) throws IOException {
        int window = body.getInt(0);
        short[] rates = refreshRates;
        List<Mode> sizes = new ArrayList<>();
        int current = 0;
        synchronized (this) {
            if (modesEnabled) {
                sizes.addAll(modes);
                for (int i = 0; i < sizes.size(); i++) {
                    if (sizes.get(i).width == screenInfo.width && sizes.get(i).height == screenInfo.height) current = i;
                }
            } else sizes.add(new Mode(screenInfo.width, screenInfo.height));
        }
        int rateEntries = sizes.size() + sizes.size() * rates.length;     // per size: a count and its rates
        int bodyBytes = sizes.size() * 8 + rateEntries * 2;
        int padBytes = (4 - bodyBytes % 4) % 4;

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, SET_OF_ROTATIONS, (bodyBytes + padBytes) / 4);
            outputStream.writeInt(xServer.windowManager.rootWindow.id);
            outputStream.writeInt(now());
            outputStream.writeInt(configTimestamp);
            outputStream.writeShort((short)sizes.size());
            outputStream.writeShort((short)current);
            outputStream.writeShort(RR_ROTATE_0);
            outputStream.writeShort(currentRate);
            outputStream.writeShort((short)rateEntries);
            outputStream.writeShort((short)0);

            for (Mode mode : sizes) {
                outputStream.writeShort((short)mode.width);
                outputStream.writeShort((short)mode.height);
                outputStream.writeShort(mm(mode.width));
                outputStream.writeShort(mm(mode.height));
            }
            for (int i = 0; i < sizes.size(); i++) {
                outputStream.writeShort((short)rates.length);
                for (short rate : rates) outputStream.writeShort(rate);
            }
            if (padBytes > 0) outputStream.writePad(padBytes);
        }
    }

    private void setScreenConfig(XClient client, ByteBuffer body, XOutputStream outputStream) throws IOException {
        int sizeId = body.getShort(12) & 0xffff;
        short requestedRate = body.getShort(16);

        for (short rate : refreshRates) {
            if (rate == requestedRate) {
                currentRate = requestedRate;
                break;
            }
        }
        if (modesEnabled) {
            Mode mode;
            synchronized (this) {
                mode = sizeId < modes.size() ? modes.get(sizeId) : null;
            }
            if (mode != null) applyMode(mode.width, mode.height, body.getInt(0));
        }

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0, 0);
            outputStream.writeInt(now());
            outputStream.writeInt(configTimestamp);
            outputStream.writeInt(xServer.windowManager.rootWindow.id);
            outputStream.writeShort((short)0);
            outputStream.writePad(10);
        }
    }

    // ---- 1.2 / 1.3 -----------------------------------------------------------------------------------------------------

    private void getScreenSizeRange(XClient client, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0, 0);
            outputStream.writeShort((short)64);
            outputStream.writeShort((short)64);
            outputStream.writeShort((short)maxWidth);
            outputStream.writeShort((short)maxHeight);
            outputStream.writePad(16);
        }
    }

    private void getScreenResources(XClient client, XOutputStream outputStream) throws IOException {
        List<Mode> list;
        synchronized (this) {
            list = new ArrayList<>(modes);
        }
        int nameBytes = 0;
        for (Mode mode : list) nameBytes += modeName(mode).length();
        int namePad = (4 - nameBytes % 4) % 4;
        int dataBytes = 4 + 4 + list.size() * 32 + nameBytes + namePad;

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0, dataBytes / 4);
            outputStream.writeInt(now());
            outputStream.writeInt(configTimestamp);
            outputStream.writeShort((short)1);                   // CRTCs
            outputStream.writeShort((short)1);                   // outputs
            outputStream.writeShort((short)list.size());
            outputStream.writeShort((short)nameBytes);
            outputStream.writePad(8);
            outputStream.writeInt(CRTC_ID);
            outputStream.writeInt(OUTPUT_ID);
            for (int i = 0; i < list.size(); i++) writeModeInfo(outputStream, MODE_ID_BASE + i, list.get(i));
            for (Mode mode : list) outputStream.write(modeName(mode).getBytes(XServer.LATIN1_CHARSET));
            if (namePad > 0) outputStream.writePad(namePad);
        }
    }

    private void getOutputInfo(XClient client, XOutputStream outputStream) throws IOException {
        List<Mode> list;
        synchronized (this) {
            list = new ArrayList<>(modes);
        }
        String name = "screen";
        int namePad = (4 - name.length() % 4) % 4;
        int dataBytes = 4 + list.size() * 4 + name.length() + namePad;
        int current = currentModeId();

        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0, 1 + dataBytes / 4);
            outputStream.writeInt(now());
            outputStream.writeInt(CRTC_ID);
            outputStream.writeInt(mm(screenInfo.width));
            outputStream.writeInt(mm(screenInfo.height));
            outputStream.writeByte((byte)0);                     // connected
            outputStream.writeByte((byte)0);                     // subpixel order unknown
            outputStream.writeShort((short)1);                   // CRTCs
            outputStream.writeShort((short)list.size());
            outputStream.writeShort((short)1);                   // preferred modes
            outputStream.writeShort((short)0);                   // clones
            outputStream.writeShort((short)name.length());
            outputStream.writeInt(CRTC_ID);
            // The current mode first, as the preferred one.
            outputStream.writeInt(current);
            for (int i = 0; i < list.size(); i++) if (MODE_ID_BASE + i != current) outputStream.writeInt(MODE_ID_BASE + i);
            outputStream.write(name.getBytes(XServer.LATIN1_CHARSET));
            if (namePad > 0) outputStream.writePad(namePad);
        }
    }

    private void getCrtcInfo(XClient client, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0, 2);
            outputStream.writeInt(now());
            outputStream.writeShort((short)0);
            outputStream.writeShort((short)0);
            outputStream.writeShort(screenInfo.width);
            outputStream.writeShort(screenInfo.height);
            outputStream.writeInt(currentModeId());
            outputStream.writeShort(RR_ROTATE_0);
            outputStream.writeShort(RR_ROTATE_0);                // rotations possible
            outputStream.writeShort((short)1);                   // outputs
            outputStream.writeShort((short)1);                   // possible outputs
            outputStream.writeInt(OUTPUT_ID);
            outputStream.writeInt(OUTPUT_ID);
        }
    }

    private void setCrtcConfig(XClient client, ByteBuffer body, XOutputStream outputStream) throws IOException {
        int modeId = body.getInt(16);
        if (modeId != 0) {
            Mode mode = modeById(modeId);
            if (mode != null) applyMode(mode.width, mode.height, xServer.windowManager.rootWindow.id);
        }
        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0, 0);        // status: success
            outputStream.writeInt(now());
            outputStream.writePad(20);
        }
    }

    private void setScreenSize(ByteBuffer body) {
        int width = body.getShort(4) & 0xffff;
        int height = body.getShort(6) & 0xffff;
        applyMode(width, height, body.getInt(0));
    }

    private void getCrtcGamma(XClient client, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0, GAMMA_SIZE * 3 * 2 / 4);
            outputStream.writeShort((short)GAMMA_SIZE);
            outputStream.writePad(22);
            for (int channel = 0; channel < 3; channel++) {
                for (int i = 0; i < GAMMA_SIZE; i++) outputStream.writeShort((short)(i * 65535 / (GAMMA_SIZE - 1)));
            }
        }
    }

    private void writeEmptyReply(XClient client, XOutputStream outputStream, int data) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, data, 0);
            outputStream.writePad(24);
        }
    }

    /** The identity transform, pending and current, with no filters. */
    private void getCrtcTransform(XClient client, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0, 16);
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < 9; i++) outputStream.writeInt(i % 4 == 0 ? 0x10000 : 0);
                if (pass == 0) {
                    outputStream.writeByte((byte)0);             // has transforms
                    outputStream.writePad(3);
                }
            }
            outputStream.writePad(4);
            outputStream.writePad(8);                            // no filter parameters, no filter names
        }
    }

    private void getOutputPrimary(XClient client, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0, 0);
            outputStream.writeInt(OUTPUT_ID);
            outputStream.writePad(20);
        }
    }

    private void getPanning(XClient client, XOutputStream outputStream) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            writeReplyHeader(client, outputStream, 0, 1);
            outputStream.writeInt(now());
            outputStream.writePad(24);
        }
    }

    // ---- Changing the size, and telling clients ---------------------------------------------------------------------

    /** Gives the screen this size, and sends the events clients asked for. */
    private void applyMode(int width, int height, int requestWindow) {
        if (width == screenInfo.width && height == screenInfo.height) return;
        if (width < 64 || height < 64 || width > maxWidth || height > maxHeight) return;
        xServer.resizeScreen(width, height);
        configTimestamp++;
        int modeId = currentModeId();
        List<Subscription> copy;
        synchronized (this) {
            copy = new ArrayList<>(subscriptions);
        }
        int rootId = xServer.windowManager.rootWindow.id;
        for (Subscription subscription : copy) {
            if ((subscription.mask & NOTIFY_SCREEN_CHANGE) != 0) {
                subscription.client.sendEvent(new ScreenChangeNotify(FIRST_EVENT_ID, rootId, subscription.window.id, modeId - MODE_ID_BASE));
            }
            if ((subscription.mask & NOTIFY_CRTC_CHANGE) != 0) {
                subscription.client.sendEvent(new CrtcChangeNotify(FIRST_EVENT_ID + 1, subscription.window.id, modeId));
            }
        }
    }

    private void selectInput(XClient client, ByteBuffer body) {
        Window window = xServer.windowManager.getWindow(body.getInt(0));
        if (window == null) return;
        int mask = body.getShort(4) & 0xffff;
        synchronized (this) {
            for (int i = subscriptions.size() - 1; i >= 0; i--) {
                Subscription existing = subscriptions.get(i);
                if (existing.client == client && existing.window == window) subscriptions.remove(i);
            }
            if (mask != 0) subscriptions.add(new Subscription(client, window, mask));
        }
    }

    /** RRScreenChangeNotify. */
    private final class ScreenChangeNotify extends Event {
        private final int rootId;
        private final int windowId;
        private final int sizeId;

        ScreenChangeNotify(int code, int rootId, int windowId, int sizeId) {
            super(code);
            this.rootId = rootId;
            this.windowId = windowId;
            this.sizeId = sizeId;
        }

        @Override
        public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
            try (XStreamLock lock = outputStream.lock()) {
                outputStream.writeByte(code);
                outputStream.writeByte((byte)RR_ROTATE_0);
                outputStream.writeShort(sequenceNumber);
                outputStream.writeInt(now());
                outputStream.writeInt(configTimestamp);
                outputStream.writeInt(rootId);
                outputStream.writeInt(windowId);
                outputStream.writeShort((short)sizeId);
                outputStream.writeShort((short)0);               // subpixel order
                outputStream.writeShort(screenInfo.width);
                outputStream.writeShort(screenInfo.height);
                outputStream.writeShort(mm(screenInfo.width));
                outputStream.writeShort(mm(screenInfo.height));
            }
        }
    }

    /** RRNotify with the CRTC-change sub-code. */
    private final class CrtcChangeNotify extends Event {
        private final int windowId;
        private final int modeId;

        CrtcChangeNotify(int code, int windowId, int modeId) {
            super(code);
            this.windowId = windowId;
            this.modeId = modeId;
        }

        @Override
        public void send(short sequenceNumber, XOutputStream outputStream) throws IOException {
            try (XStreamLock lock = outputStream.lock()) {
                outputStream.writeByte(code);
                outputStream.writeByte((byte)0);                 // CrtcChange
                outputStream.writeShort(sequenceNumber);
                outputStream.writeInt(now());
                outputStream.writeInt(windowId);
                outputStream.writeInt(CRTC_ID);
                outputStream.writeInt(modeId);
                outputStream.writeShort(RR_ROTATE_0);
                outputStream.writeShort((short)0);
                outputStream.writeShort((short)0);               // x
                outputStream.writeShort((short)0);               // y
                outputStream.writeShort(screenInfo.width);
                outputStream.writeShort(screenInfo.height);
            }
        }
    }

    // ---- Requests ----------------------------------------------------------------------------------------------------

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int minor = client.getRequestData() & 0xff;
        // The body is read whole, so that a request that has one is always consumed completely.
        byte[] bytes = new byte[client.getRemainingRequestLength()];
        inputStream.read(bytes);
        ByteBuffer body = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        if (!modesEnabled) {
            switch (minor) {
                case ClientOpcodes.QUERY_VERSION:
                    queryVersion(client, outputStream);
                    return;
                case ClientOpcodes.GET_SCREEN_INFO:
                    getScreenInfo(client, body, outputStream);
                    return;
                case ClientOpcodes.SET_SCREEN_CONFIG:
                    setScreenConfig(client, body, outputStream);
                    return;
                case ClientOpcodes.SELECT_INPUT:
                    return;
                default:
                    throw new BadImplementation();
            }
        }

        switch (minor) {
            case ClientOpcodes.QUERY_VERSION:
                queryVersion(client, outputStream);
                break;
            case ClientOpcodes.GET_SCREEN_INFO:
                getScreenInfo(client, body, outputStream);
                break;
            case ClientOpcodes.SET_SCREEN_CONFIG:
                setScreenConfig(client, body, outputStream);
                break;
            case ClientOpcodes.SELECT_INPUT:
                selectInput(client, body);
                break;
            case ClientOpcodes.GET_SCREEN_SIZE_RANGE:
                getScreenSizeRange(client, outputStream);
                break;
            case ClientOpcodes.SET_SCREEN_SIZE:
                setScreenSize(body);
                break;
            case ClientOpcodes.GET_SCREEN_RESOURCES:
            case ClientOpcodes.GET_SCREEN_RESOURCES_CURRENT:
                getScreenResources(client, outputStream);
                break;
            case ClientOpcodes.GET_OUTPUT_INFO:
                getOutputInfo(client, outputStream);
                break;
            case ClientOpcodes.LIST_OUTPUT_PROPERTIES:
                writeEmptyReply(client, outputStream, 0);        // no properties
                break;
            case ClientOpcodes.QUERY_OUTPUT_PROPERTY:
                writeEmptyReply(client, outputStream, 0);
                break;
            case ClientOpcodes.GET_OUTPUT_PROPERTY:
                writeEmptyReply(client, outputStream, 0);        // no such property: type None, nothing after it
                break;
            case ClientOpcodes.CONFIGURE_OUTPUT_PROPERTY:
            case ClientOpcodes.CHANGE_OUTPUT_PROPERTY:
            case ClientOpcodes.DELETE_OUTPUT_PROPERTY:
            case ClientOpcodes.DESTROY_MODE:
            case ClientOpcodes.ADD_OUTPUT_MODE:
            case ClientOpcodes.DELETE_OUTPUT_MODE:
            case ClientOpcodes.SET_CRTC_GAMMA:
            case ClientOpcodes.SET_CRTC_TRANSFORM:
            case ClientOpcodes.SET_OUTPUT_PRIMARY:
                break;
            case ClientOpcodes.CREATE_MODE: {
                // A mode of the client's own: window, then the mode's info (id, width, height ...).
                int width = body.remaining() >= 12 ? body.getShort(8) & 0xffff : 0;
                int height = body.remaining() >= 12 ? body.getShort(10) & 0xffff : 0;
                int id = width > 0 && height > 0 ? modeIdOf(width, height) : MODE_ID_BASE;
                try (XStreamLock lock = outputStream.lock()) {
                    writeReplyHeader(client, outputStream, 0, 0);
                    outputStream.writeInt(id);
                    outputStream.writePad(20);
                }
                break;
            }
            case ClientOpcodes.GET_CRTC_INFO:
                getCrtcInfo(client, outputStream);
                break;
            case ClientOpcodes.SET_CRTC_CONFIG:
                setCrtcConfig(client, body, outputStream);
                break;
            case ClientOpcodes.GET_CRTC_GAMMA_SIZE:
                try (XStreamLock lock = outputStream.lock()) {
                    writeReplyHeader(client, outputStream, 0, 0);
                    outputStream.writeShort((short)GAMMA_SIZE);
                    outputStream.writePad(22);
                }
                break;
            case ClientOpcodes.GET_CRTC_GAMMA:
                getCrtcGamma(client, outputStream);
                break;
            case ClientOpcodes.GET_CRTC_TRANSFORM:
                getCrtcTransform(client, outputStream);
                break;
            case ClientOpcodes.GET_PANNING:
                getPanning(client, outputStream);
                break;
            case ClientOpcodes.SET_PANNING:
                writeEmptyReply(client, outputStream, 0);
                break;
            case ClientOpcodes.GET_OUTPUT_PRIMARY:
                getOutputPrimary(client, outputStream);
                break;
            default:
                throw new BadImplementation();
        }
    }
}
