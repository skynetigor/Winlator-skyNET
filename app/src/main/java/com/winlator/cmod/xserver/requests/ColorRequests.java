package com.winlator.cmod.xserver.requests;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.errors.BadName;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;

/**
 * Colour allocation for the one TrueColor visual the server has: a colour's pixel is its 24-bit RGB value, so nothing
 * is allocated and nothing needs freeing.
 */
public abstract class ColorRequests {
    private static final HashMap<String, Integer> NAMES = new HashMap<>();
    static {
        String[] table = {
                "black", "000000", "white", "ffffff", "red", "ff0000", "green", "00ff00", "blue", "0000ff",
                "yellow", "ffff00", "cyan", "00ffff", "magenta", "ff00ff", "gray", "bebebe", "grey", "bebebe",
                "lightgray", "d3d3d3", "lightgrey", "d3d3d3", "darkgray", "a9a9a9", "darkgrey", "a9a9a9",
                "orange", "ffa500", "purple", "a020f0", "brown", "a52a2a", "pink", "ffc0cb", "navy", "000080",
                "darkred", "8b0000", "darkgreen", "006400", "darkblue", "00008b", "gold", "ffd700",
                "steelblue", "4682b4", "lightblue", "add8e6", "lightyellow", "ffffe0", "lightgreen", "90ee90",
                "gray50", "7f7f7f", "grey50", "7f7f7f", "gray75", "bfbfbf", "gray25", "404040",
        };
        for (int i = 0; i < table.length; i += 2) NAMES.put(table[i], Integer.parseInt(table[i + 1], 16));
    }

    /** A colour by name ("red", "gray40"), or as "#rgb", "#rrggbb" or "#rrrrggggbbbb"; null when it is not known. */
    private static Integer parse(String name) {
        name = name.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        try {
            if (name.startsWith("#")) {
                String hex = name.substring(1);
                if (hex.length() % 3 != 0 || hex.isEmpty()) return null;
                int digits = hex.length() / 3;
                int[] c = new int[3];
                for (int i = 0; i < 3; i++) {
                    long v = Long.parseLong(hex.substring(i * digits, (i + 1) * digits), 16);
                    c[i] = (int) (digits == 1 ? v * 0x11 : v >> (4 * (digits - 2)));
                }
                return (c[0] << 16) | (c[1] << 8) | c[2];
            }
            Integer known = NAMES.get(name);
            if (known != null) return known;
            if (name.startsWith("gray") || name.startsWith("grey")) {
                int percent = Integer.parseInt(name.substring(4));
                if (percent < 0 || percent > 100) return null;
                int v = (percent * 255 + 50) / 100;
                return (v << 16) | (v << 8) | v;
            }
        }
        catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    private static int scale16(int channel8) {
        return channel8 * 0x101;
    }

    public static void allocColor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.readInt(); // colormap
        int red = inputStream.readUnsignedShort() >> 8;
        int green = inputStream.readUnsignedShort() >> 8;
        int blue = inputStream.readUnsignedShort() >> 8;
        inputStream.skip(2);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short) scale16(red));
            outputStream.writeShort((short) scale16(green));
            outputStream.writeShort((short) scale16(blue));
            outputStream.writePad(2);
            outputStream.writeInt((red << 16) | (green << 8) | blue);
            outputStream.writePad(12);
        }
    }

    public static void allocNamedColor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.readInt(); // colormap
        int length = inputStream.readUnsignedShort();
        inputStream.skip(2);
        String name = inputStream.readString8(length);

        Integer rgb = parse(name);
        if (rgb == null) throw new BadName();
        int red = (rgb >> 16) & 0xff, green = (rgb >> 8) & 0xff, blue = rgb & 0xff;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(rgb);
            for (int pass = 0; pass < 2; pass++) { // exact, then what the visual can show (the same)
                outputStream.writeShort((short) scale16(red));
                outputStream.writeShort((short) scale16(green));
                outputStream.writeShort((short) scale16(blue));
            }
            outputStream.writePad(8);
        }
    }
}
