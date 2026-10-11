package com.winlator.cmod.xserver.extensions;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Keyboard;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * A minimal XKEYBOARD: it announces version 1.0 and answers the few requests SDL makes when it opens the keyboard
 * (UseExtension, SelectEvents, GetState, PerClientFlags, GetMap) with valid but empty replies. The keyboard itself is
 * still the core one. SDL takes a different path when XKB is present, and without it BeamNG.drive never gets focus.
 */
public class XkbExtension implements Extension {
    public static final byte MAJOR_OPCODE = -113;
    private static final byte FIRST_EVENT_ID = 110;
    private static final byte FIRST_ERROR_ID = (byte)175;

    private static final int USE_EXTENSION = 0;
    private static final int GET_STATE = 4;
    private static final int GET_MAP = 8;
    private static final int GET_NAMES = 17;
    private static final int PER_CLIENT_FLAGS = 21;

    /**
     * The requests that carry a reply. One of them left unanswered hangs the client for ever rather than failing it,
     * so the ones without an implementation below are refused instead of ignored.
     */
    private static final int[] WITH_REPLY = {0, 4, 6, 8, 10, 12, 13, 15, 17, 19, 21, 22, 23, 24};

    private static boolean expectsReply(int minor) {
        for (int value : WITH_REPLY) if (value == minor) return true;
        return false;
    }

    @Override
    public String getName() {
        return "XKEYBOARD";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return FIRST_ERROR_ID;
    }

    @Override
    public byte getFirstEventId() {
        return FIRST_EVENT_ID;
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int minor = client.getRequestData() & 0xff;
        int want = 0;
        if (minor == GET_MAP) {
            inputStream.skip(2); // device
            want = inputStream.readUnsignedShort() | inputStream.readUnsignedShort(); // full | partial
        }
        client.skipRequest();

        switch (minor) {
            case USE_EXTENSION:
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                    outputStream.writeByte((byte)1); // supported
                    outputStream.writeShort(client.getSequenceNumber());
                    outputStream.writeInt(0);
                    outputStream.writeShort((short)1); // server major
                    outputStream.writeShort((short)0); // server minor
                    outputStream.writePad(20);
                }
                break;
            case GET_STATE:
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                    outputStream.writeByte((byte)3); // device id (core keyboard)
                    outputStream.writeShort(client.getSequenceNumber());
                    outputStream.writeInt(0);
                    outputStream.writePad(24); // no modifiers, group 0
                }
                break;
            case PER_CLIENT_FLAGS:
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                    outputStream.writeByte((byte)3);
                    outputStream.writeShort(client.getSequenceNumber());
                    outputStream.writeInt(0);
                    outputStream.writeInt(0x1f); // supported: detectable auto-repeat, grabs-use-xkb-state and friends
                    outputStream.writeInt(0x1);  // value: detectable auto-repeat on
                    outputStream.writeInt(0);    // auto controls
                    outputStream.writeInt(0);    // auto control values
                    outputStream.writePad(8);
                }
                break;
            case GET_MAP:
                writeMap(client, outputStream, want);
                break;
            case GET_NAMES:
                // No names are kept, so none are reported: a client reads the value list by the reply's own
                // "which", and an empty one leaves it with the keyboard it already has from GetMap.
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                    outputStream.writeByte((byte)3);
                    outputStream.writeShort(client.getSequenceNumber());
                    outputStream.writeInt(0);                        // no value list
                    outputStream.writeInt(0);                        // which: nothing reported
                    outputStream.writeByte((byte)Keyboard.MIN_KEYCODE);
                    outputStream.writeByte((byte)Keyboard.MAX_KEYCODE);
                    outputStream.writePad(2);                        // nTypes, group names
                    outputStream.writeShort((short)0);               // virtual mods
                    outputStream.writePad(2);                        // first key, key count
                    outputStream.writeInt(0);                        // indicators
                    outputStream.writePad(2);                        // radio groups, key aliases
                    outputStream.writeShort((short)0);               // key type levels
                    outputStream.writePad(4);
                }
                break;
            default:
                // SelectEvents, SetMap, Bell and the like need no answer.
                if (expectsReply(minor)) throw new BadImplementation();
                break;
        }
    }

    private static final int KEY_TYPES = 1;
    private static final int KEY_SYMS = 2;
    private static final int MOD_MAP = 4;

    // Key types in the canonical order: ONE_LEVEL, TWO_LEVEL, ALPHABETIC, KEYPAD. Each is {mask, level-1 modifier masks...}.
    private static final int[][] TYPES = {{0}, {1, 1}, {3, 1, 2}, {0x11, 1, 0x10}};

    private void writeType(XOutputStream out, int[] type) {
        int entries = type.length - 1;
        out.writeByte((byte)type[0]); // mask
        out.writeByte((byte)type[0]); // real mods
        out.writeShort((short)0);     // virtual mods
        out.writeByte((byte)(entries == 0 ? 1 : 2)); // levels
        out.writeByte((byte)entries);
        out.writeByte((byte)0);       // no preserve
        out.writeByte((byte)0);
        for (int i = 0; i < entries; i++) {
            out.writeByte((byte)1);   // active
            out.writeByte((byte)type[i + 1]);
            out.writeByte((byte)1);   // level
            out.writeByte((byte)type[i + 1]);
            out.writeShort((short)0);
            out.writeShort((short)0);
        }
    }

    private void writeMap(XClient client, XOutputStream out, int want) throws IOException {
        int present = want & (KEY_TYPES | KEY_SYMS | MOD_MAP);
        int[] keysyms = client.xServer.keyboard.keysyms;
        int keys = Keyboard.MAX_KEYCODE - Keyboard.MIN_KEYCODE + 1;

        int typesBytes = 0;
        if ((present & KEY_TYPES) != 0) for (int[] t : TYPES) typesBytes += 8 + (t.length - 1) * 8;

        int totalSyms = 0;
        for (int i = 0; i < keys; i++) if (i * 2 + 1 < keysyms.length && (keysyms[i * 2] | keysyms[i * 2 + 1]) != 0) totalSyms += 2;
        int symsBytes = (present & KEY_SYMS) != 0 ? keys * 8 + totalSyms * 4 : 0;

        try (XStreamLock lock = out.lock()) {
            out.writeByte(RESPONSE_CODE_SUCCESS);
            out.writeByte((byte)3);
            out.writeShort(client.getSequenceNumber());
            out.writeInt(2 + (typesBytes + symsBytes) / 4); // the 40-byte header is 8 bytes past the standard 32
            out.writeShort((short)0);
            out.writeByte((byte)Keyboard.MIN_KEYCODE);
            out.writeByte((byte)Keyboard.MAX_KEYCODE);
            out.writeShort((short)present);
            out.writeByte((byte)0);                                                   // firstType
            out.writeByte((byte)((present & KEY_TYPES) != 0 ? TYPES.length : 0));    // nTypes
            out.writeByte((byte)((present & KEY_TYPES) != 0 ? TYPES.length : 0));    // totalTypes
            out.writeByte((byte)Keyboard.MIN_KEYCODE);                                // firstKeySym
            out.writeShort((short)((present & KEY_SYMS) != 0 ? totalSyms : 0));      // totalSyms
            out.writeByte((byte)((present & KEY_SYMS) != 0 ? keys : 0));             // nKeySyms
            out.writePad(19); // actions, behaviors, explicit, modmap (all empty), virtual mods
            if ((present & KEY_TYPES) != 0) for (int[] t : TYPES) writeType(out, t);
            if ((present & KEY_SYMS) != 0) {
                for (int i = 0; i < keys; i++) {
                    boolean has = i * 2 + 1 < keysyms.length && (keysyms[i * 2] | keysyms[i * 2 + 1]) != 0;
                    out.writeByte((byte)(has ? 1 : 0)); // key type, group 1: TWO_LEVEL
                    out.writePad(3);
                    out.writeByte((byte)(has ? 1 : 0)); // one group
                    out.writeByte((byte)(has ? 2 : 0)); // width
                    out.writeShort((short)(has ? 2 : 0));
                    if (has) {
                        out.writeInt(keysyms[i * 2]);
                        out.writeInt(keysyms[i * 2 + 1]);
                    }
                }
            }
        }
    }
}
