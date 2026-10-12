package com.winlator.cmod.xserver;

import android.util.Log;

import com.winlator.cmod.xconnector.Client;
import com.winlator.cmod.xconnector.RequestHandler;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.XRequestError;
import com.winlator.cmod.xserver.extensions.Extension;
import com.winlator.cmod.xserver.requests.AtomRequests;
import com.winlator.cmod.xserver.requests.ColorRequests;
import com.winlator.cmod.xserver.requests.CursorRequests;
import com.winlator.cmod.xserver.requests.DrawRequests;
import com.winlator.cmod.xserver.requests.ExtensionRequests;
import com.winlator.cmod.xserver.requests.FontRequests;
import com.winlator.cmod.xserver.requests.GrabRequests;
import com.winlator.cmod.xserver.requests.GraphicsContextRequests;
import com.winlator.cmod.xserver.requests.KeyboardRequests;
import com.winlator.cmod.xserver.requests.PixmapRequests;
import com.winlator.cmod.xserver.requests.SelectionRequests;
import com.winlator.cmod.xserver.requests.WindowRequests;

import java.io.IOException;
import java.nio.ByteOrder;

public class XClientRequestHandler implements RequestHandler {
    public static final byte RESPONSE_CODE_ERROR = 0;
    public static final byte RESPONSE_CODE_SUCCESS = 1;
    public static final int MAX_REQUEST_LENGTH = 65535;

    /** Core requests that are answered with a reply; an unsupported one gets an error so the client does not hang. */
    private static final java.util.Set<Integer> REPLY_OPCODES = new java.util.HashSet<>(java.util.Arrays.asList(
            3, 14, 15, 16, 17, 20, 21, 23, 26, 31, 38, 39, 40, 43, 44, 47, 48, 49, 50, 52, 73, 83, 84, 85, 86, 87,
            91, 92, 97, 98, 99, 101, 103, 106, 108, 110, 116, 117, 118, 119));

    /** Log every request (client, sequence number, opcode) to logcat tag XTrace; for finding what a program waits on. */
    public static volatile boolean trace = false;

    /** Count and time every request, and log a summary every few seconds (logcat tag XStats); cheap enough to leave on. */
    public static volatile boolean stats = false;
    private static final java.util.HashMap<Integer, long[]> statTable = new java.util.HashMap<>();
    private static long statSince = 0;

    private static void recordStat(int opcode, int data, int bytes, long nanos) {
        // Extension requests are told apart by their minor opcode; core ones by the opcode alone.
        int key = opcode < 0 ? ((opcode & 0xff) << 8) | (data & 0xff) : (opcode & 0xff) << 8;
        long now = System.nanoTime();
        String report = null;
        synchronized (statTable) {
            long[] row = statTable.get(key);
            if (row == null) statTable.put(key, row = new long[3]);
            row[0]++;
            row[1] += bytes;
            row[2] += nanos;
            if (statSince == 0) statSince = now;
            if (now - statSince >= 5_000_000_000L) {
                StringBuilder sb = new StringBuilder("per second over ").append((now - statSince) / 1_000_000).append(" ms:");
                double seconds = (now - statSince) / 1e9;
                java.util.ArrayList<java.util.Map.Entry<Integer, long[]>> rows = new java.util.ArrayList<>(statTable.entrySet());
                rows.sort((a, b) -> Long.compare(b.getValue()[2], a.getValue()[2]));
                for (int i = 0; i < Math.min(8, rows.size()); i++) {
                    java.util.Map.Entry<Integer, long[]> e = rows.get(i);
                    int op = e.getKey() >> 8, minor = e.getKey() & 0xff;
                    long[] v = e.getValue();
                    sb.append("\n  op=").append(op).append(op >= 128 ? "/" + minor : "")
                            .append(" n=").append(Math.round(v[0] / seconds))
                            .append(" KB=").append(Math.round(v[1] / 1024.0 / seconds))
                            .append(" busy=").append(Math.round(v[2] / 1e7 / seconds)).append("% of a core");
                }
                report = sb.toString();
                statTable.clear();
                statSince = now;
            }
        }
        if (report != null) Log.d("XStats", report);
    }

    @Override
    public boolean handleRequest(Client client) throws IOException {
        XClient xClient = (XClient)client.getTag();
        XInputStream inputStream = client.getInputStream();
        XOutputStream outputStream = client.getOutputStream();

        if (xClient.isAuthenticated()) {
            return handleNormalRequest(xClient, inputStream, outputStream);
        }
        else return handleAuthRequest(xClient, inputStream, outputStream);
    }

    private void sendServerInformation(XClient client, XOutputStream outputStream) throws IOException {
        short vendorNameLength = (short)XServer.VENDOR_NAME.length();
        byte pixmapFormatCount = (byte)client.xServer.pixmapManager.supportedPixmapFormats.length;
        short additionalDataLength = (short)(8 + (2 * pixmapFormatCount) + ((vendorNameLength + 3) / 4) + ((40 + 8 * client.xServer.pixmapManager.supportedVisuals.length + 24) + 3) / 4);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(XServer.VERSION);
            outputStream.writeShort((short)0);
            outputStream.writeShort(additionalDataLength);
            outputStream.writeInt(1);
            outputStream.writeInt(client.resourceIDBase);
            outputStream.writeInt(client.xServer.resourceIDs.idMask);
            outputStream.writeInt(256);
            outputStream.writeShort(vendorNameLength);
            outputStream.writeShort((short)MAX_REQUEST_LENGTH);
            outputStream.writeByte((byte)1);
            outputStream.writeByte(pixmapFormatCount);
            outputStream.writeByte((byte)0);
            outputStream.writeByte((byte)0);
            outputStream.writeByte((byte)32);
            outputStream.writeByte((byte)32);
            outputStream.writeByte((byte)Keyboard.MIN_KEYCODE);
            outputStream.writeByte((byte)Keyboard.MAX_KEYCODE);
            outputStream.writeInt(0);
            outputStream.writeString8(XServer.VENDOR_NAME);

            for (PixmapFormat pixmapFormat : client.xServer.pixmapManager.supportedPixmapFormats) {
                outputStream.writeByte(pixmapFormat.depth);
                outputStream.writeByte(pixmapFormat.bitsPerPixel);
                outputStream.writeByte(pixmapFormat.scanlinePad);
                outputStream.writePad(5);
            }

            Visual rootVisual = client.xServer.windowManager.rootWindow.getContent().visual;

            outputStream.writeInt(client.xServer.windowManager.rootWindow.id);
            outputStream.writeInt(0);
            outputStream.writeInt(0xffffff);
            outputStream.writeInt(0x000000);
            outputStream.writeInt(client.xServer.windowManager.rootWindow.getAllEventMasks().getBits());
            outputStream.writeShort(client.xServer.screenInfo.width);
            outputStream.writeShort(client.xServer.screenInfo.height);
            outputStream.writeShort(client.xServer.screenInfo.getWidthInMillimeters());
            outputStream.writeShort(client.xServer.screenInfo.getHeightInMillimeters());
            outputStream.writeShort((short)1);
            outputStream.writeShort((short)1);
            outputStream.writeInt(rootVisual.id);
            outputStream.writeByte((byte)0);
            outputStream.writeByte((byte)0);
            outputStream.writeByte(rootVisual.depth);
            outputStream.writeByte((byte)client.xServer.pixmapManager.supportedVisuals.length);

            for (Visual visual : client.xServer.pixmapManager.supportedVisuals) {
                outputStream.writeByte(visual.depth);
                outputStream.writeByte((byte)0);
                outputStream.writeShort((short)(visual.displayable ? 1 : 0));
                outputStream.writeInt(0);

                if (visual.displayable) {
                    outputStream.writeInt(visual.id);
                    outputStream.writeByte(visual.visualClass);
                    outputStream.writeByte(visual.bitsPerRGBValue);
                    outputStream.writeShort(visual.colormapEntries);
                    outputStream.writeInt(visual.redMask);
                    outputStream.writeInt(visual.greenMask);
                    outputStream.writeInt(visual.blueMask);
                    outputStream.writeInt(0);
                }
            }
        }
    }

    private boolean handleAuthRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        if (inputStream.available() < 12) return false;

        byte byteOrder = inputStream.readByte();
        if (byteOrder == 66) {
            inputStream.setByteOrder(ByteOrder.BIG_ENDIAN);
            outputStream.setByteOrder(ByteOrder.BIG_ENDIAN);
        }
        else if (byteOrder == 108) {
            inputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
            outputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        }

        inputStream.skip(1);

        short majorVersion = inputStream.readShort();
        if (majorVersion != 11) throw new UnsupportedOperationException("Unsupported major X protocol version "+majorVersion+".");

        inputStream.skip(2);
        int nameLength = inputStream.readShort();
        int dataLength = inputStream.readShort();
        inputStream.skip(2);

        if (nameLength > 0) inputStream.readString8(nameLength);
        if (dataLength > 0) inputStream.readString8(dataLength);

        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            sendServerInformation(client, outputStream);
        }

        client.setAuthenticated(true);
        return true;
    }

    private boolean handleNormalRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        if (inputStream.available() < 4) return false;
        byte opcode = inputStream.readByte();
        byte requestData = inputStream.readByte();

        int requestLength = inputStream.readUnsignedShort();
        if (requestLength != 0) {
            requestLength = requestLength * 4 - 4;
        }
        else if (inputStream.available() < 4) {
            return false;
        }
        else requestLength = inputStream.readInt() * 4 - 8;
        if (inputStream.available() < requestLength) {
            if (trace && requestLength > 8192) {
                Log.d("XTrace", "waiting for a large request: op=" + (opcode & 0xff) + " needs " + requestLength
                        + " bytes, has " + inputStream.available());
            }
            return false;
        }

        client.generateSequenceNumber();
        client.setRequestData(requestData);
        client.setRequestLength(requestLength);

        if (trace) {
            Log.d("XTrace", "client=" + client.resourceIDBase + " seq=" + client.getSequenceNumber()
                    + " op=" + (opcode & 0xff) + " data=" + (requestData & 0xff) + " len=" + requestLength);
        }

        final long statStart = stats ? System.nanoTime() : 0;
        try {
            switch (opcode) {
                case ClientOpcodes.CREATE_WINDOW:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.INPUT_DEVICE, XServer.Lockable.CURSOR_MANAGER)) {
                        WindowRequests.createWindow(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.CHANGE_WINDOW_ATTRIBUTES:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.CURSOR_MANAGER)) {
                        WindowRequests.changeWindowAttributes(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.GET_WINDOW_ATTRIBUTES:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.getWindowAttributes(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.DESTROY_WINDOW:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        WindowRequests.destroyWindow(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.DESTROY_SUB_WINDOWS:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        WindowRequests.destroySubWindows(client, inputStream, outputStream);
                    }
                case ClientOpcodes.REPARENT_WINDOW:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.reparentWindow(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.MAP_WINDOW:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        WindowRequests.mapWindow(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.MAP_SUB_WINDOWS:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        WindowRequests.mapSubWindows(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.UNMAP_WINDOW:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        WindowRequests.unmapWindow(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.CONFIGURE_WINDOW:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        WindowRequests.configureWindow(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.GET_GEOMETRY:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                        WindowRequests.getGeometry(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.QUERY_TREE:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.queryTree(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.INTERN_ATOM:
                    AtomRequests.internAtom(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.GET_ATOM_NAME:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        AtomRequests.getAtomName(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.CHANGE_PROPERTY:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.changeProperty(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.DELETE_PROPERTY:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.deleteProperty(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.GET_PROPERTY:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.getProperty(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.SET_SELECTION_OWNER:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        SelectionRequests.setSelectionOwner(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.GET_SELECTION_OWNER:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        SelectionRequests.getSelectionOwner(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.SEND_EVENT:
                    try (XLock lock = client.xServer.lockAll()) {
                        WindowRequests.sendEvent(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.GRAB_POINTER:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE, XServer.Lockable.CURSOR_MANAGER)) {
                        GrabRequests.grabPointer(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.UNGRAB_POINTER:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        GrabRequests.ungrabPointer(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.QUERY_POINTER:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        WindowRequests.queryPointer(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.TRANSLATE_COORDINATES:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.translateCoordinates(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.WARP_POINTER:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        WindowRequests.warpPointer(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.SET_INPUT_FOCUS:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.setInputFocus(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.GET_INPUT_FOCUS:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.getInputFocus(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.QUERY_KEYMAP:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER);
                         XStreamLock streamLock = outputStream.lock()) {
                        // The reply has to be flushed (the stream lock does that on close); without it the client waits for ever.
                        outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                        outputStream.writeByte((byte) 0);
                        outputStream.writeShort(client.getSequenceNumber());
                        outputStream.writeInt(2);
                        outputStream.writePad(32);
                    }
                    break;
                case ClientOpcodes.OPEN_FONT:
                    FontRequests.openFont(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.CLOSE_FONT:
                    FontRequests.closeFont(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.QUERY_FONT:
                    FontRequests.queryFont(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.QUERY_TEXT_EXTENTS:
                    FontRequests.queryTextExtents(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.LIST_FONTS:
                    FontRequests.listFonts(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.POLY_TEXT_8:
                case ClientOpcodes.POLY_TEXT_16:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        FontRequests.polyText(client, inputStream, opcode == ClientOpcodes.POLY_TEXT_16);
                    }
                    break;
                case ClientOpcodes.IMAGE_TEXT_8:
                case ClientOpcodes.IMAGE_TEXT_16:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        FontRequests.imageText(client, inputStream, opcode == ClientOpcodes.IMAGE_TEXT_16);
                    }
                    break;
                case ClientOpcodes.CREATE_PIXMAP:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                        PixmapRequests.createPixmap(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.FREE_PIXMAP:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                        PixmapRequests.freePixmap(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.CREATE_GC:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        GraphicsContextRequests.createGC(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.CHANGE_GC:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        GraphicsContextRequests.changeGC(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.COPY_GC:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        GraphicsContextRequests.copyGC(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.SET_CLIP_RECTANGLES:
                    client.skipRequest();
                    break;
                case ClientOpcodes.FREE_GC:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        GraphicsContextRequests.freeGC(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.COPY_AREA:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        DrawRequests.copyArea(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.POLY_LINE:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        DrawRequests.polyLine(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.POLY_SEGMENT:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        DrawRequests.polySegment(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.POLY_RECTANGLE:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        DrawRequests.polyRectangle(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.POLY_FILL_RECTANGLE:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        DrawRequests.polyFillRectangle(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.PUT_IMAGE:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        DrawRequests.putImage(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.GET_IMAGE:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                        DrawRequests.getImage(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.CREATE_COLORMAP:
                    client.skipRequest();
                    break;
                case ClientOpcodes.FREE_COLORMAP:
                    client.skipRequest();
                    break;
                case ClientOpcodes.CREATE_CURSOR:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.CURSOR_MANAGER)) {
                        CursorRequests.createCursor(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.CREATE_GLYPH_CURSOR:
                    client.skipRequest();
                    break;
                case ClientOpcodes.FREE_CURSOR:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.CURSOR_MANAGER)) {
                        CursorRequests.freeCursor(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.QUERY_EXTENSION:
                    ExtensionRequests.queryExtension(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.GET_KEYBOARD_MAPPING:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.INPUT_DEVICE)) {
                        KeyboardRequests.getKeyboardMapping(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.BELL:
                    client.skipRequest();
                    break;
                case ClientOpcodes.SET_SCREEN_SAVER:
                    client.skipRequest();
                    break;
                case ClientOpcodes.GET_SCREEN_SAVER:
                    WindowRequests.getScreenSaver(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.FORCE_SCREEN_SAVER:
                    client.skipRequest();
                    break;
                case ClientOpcodes.GET_MODIFIER_MAPPING:
                    KeyboardRequests.getModifierMapping(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.NO_OPERATION:
                    client.skipRequest();
                    break;
                case ClientOpcodes.GET_POINTER_MAPPING:
                    CursorRequests.getPointerMaping(client, inputStream, outputStream);
                    break;
                case 36:
                    try (XLock lock = client.xServer.lockAll()) {
                        client.xServer.setGrabbed(true, client);
                        outputStream.writeSuccessReply(client.getSequenceNumber(), 0);
                        Log.d("XClientRequestHandler", "X_GrabServer request handled successfully:" + outputStream.buffer.position());
                    }
                    break;

                case 37:
                    try (XLock lock = client.xServer.lockAll()) {
                        if (client.xServer.isGrabbedBy(client)) {
                            client.xServer.setGrabbed(false, null);
                        }
                        outputStream.writeSuccessReply(client.getSequenceNumber(), 0);
                        Log.d("XClientRequestHandler", "X_UngrabServer request handled successfully:" + outputStream.buffer.position());
                    }
                    break;
                case 84: // AllocColor
                    ColorRequests.allocColor(client, inputStream, outputStream);
                    break;
                case 85: // AllocNamedColor
                    ColorRequests.allocNamedColor(client, inputStream, outputStream);
                    break;
                case 32: // UngrabKeyboard: no keyboard grabs are kept, so nothing to release
                    client.skipRequest();
                    break;
                case 103: // GetKeyboardControl
                    try (XStreamLock lock = outputStream.lock()) {
                        outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                        outputStream.writeByte((byte) 1); // global auto repeat: on
                        outputStream.writeShort(client.getSequenceNumber());
                        outputStream.writeInt(5);
                        outputStream.writeInt(0); // LED mask
                        outputStream.writeByte((byte) 0); // key click percent
                        outputStream.writeByte((byte) 50); // bell percent
                        outputStream.writeShort((short) 400); // bell pitch
                        outputStream.writeShort((short) 100); // bell duration
                        outputStream.writePad(2);
                        for (int i = 0; i < 32; i++) outputStream.writeByte((byte) 0xff); // every key repeats
                    }
                    break;
                default:
                    if (opcode < 0) {
                        Extension extension = client.xServer.extensions.get(opcode);
                        if (extension != null) extension.handleRequest(client, inputStream, outputStream);
                    }
                    else {
                        Log.w("XClientRequestHandler", "Unsupported opcode " + (opcode & 0xff));
                        // Drop the request body, otherwise it is parsed as the next request header.
                        client.skipRequest();
                        // A client that asked for a reply would wait for ever; answer it with an error instead.
                        if (REPLY_OPCODES.contains(opcode & 0xff)) throw new BadImplementation();
                    }
                    break;
            }
        }
        catch (XRequestError e) {
            client.skipRequest();
            e.sendError(client, opcode);
        }

        if (stats) recordStat(opcode, requestData, requestLength, System.nanoTime() - statStart);
        return true;
    }
}