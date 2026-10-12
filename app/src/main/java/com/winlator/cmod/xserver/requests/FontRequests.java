package com.winlator.cmod.xserver.requests;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.GraphicsContext;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XFont;
import com.winlator.cmod.xserver.errors.BadGraphicsContext;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;
import java.util.List;

/** Core fonts: the names a client can ask for, their metrics, and the text requests that use them (see {@link XFont}). */
public abstract class FontRequests {
    public static void openFont(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int fontId = inputStream.readInt();
        int length = inputStream.readUnsignedShort();
        inputStream.skip(2);
        String name = inputStream.readString8(length);
        // The cursor font only serves CreateGlyphCursor, which does not look it up.
        if (name.equals("cursor")) return;
        client.xServer.fonts.put(fontId, XFont.open(name));
    }

    public static void closeFont(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        client.xServer.fonts.remove(inputStream.readInt());
    }

    /** A font by the id of a font or of a graphics context that has one (what the protocol calls a "fontable"). */
    private static XFont fontable(XClient client, int id) throws XRequestError {
        XFont font = client.xServer.fonts.get(id);
        if (font != null) return font;
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(id);
        if (graphicsContext != null) font = client.xServer.fonts.get(graphicsContext.getFont());
        return font != null ? font : XFont.open("fixed");
    }

    public static void queryFont(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        XFont font = fontable(client, inputStream.readInt());
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(7);                    // 60 bytes in all, no properties, no per-character infos
            writeCharInfo(outputStream, font);           // min bounds
            outputStream.writePad(4);
            writeCharInfo(outputStream, font);           // max bounds
            outputStream.writePad(4);
            outputStream.writeShort((short) 0);          // min char
            outputStream.writeShort((short) 255);        // max char
            outputStream.writeShort((short) 32);         // default char
            outputStream.writeShort((short) 0);          // properties
            outputStream.writeByte((byte) 0);            // left to right
            outputStream.writeByte((byte) 0);            // min byte 1
            outputStream.writeByte((byte) 0);            // max byte 1
            outputStream.writeByte((byte) 1);            // all characters exist
            outputStream.writeShort((short) font.ascent);
            outputStream.writeShort((short) font.descent);
            outputStream.writeInt(0);                    // no per-character infos: every glyph has the bounds above
        }
    }

    private static void writeCharInfo(XOutputStream outputStream, XFont font) {
        outputStream.writeShort((short) 0);              // left side bearing
        outputStream.writeShort((short) font.width);     // right side bearing
        outputStream.writeShort((short) font.width);     // character width
        outputStream.writeShort((short) font.ascent);
        outputStream.writeShort((short) font.descent);
        outputStream.writeShort((short) 0);              // attributes
    }

    public static void queryTextExtents(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        boolean oddLength = client.getRequestData() != 0;
        XFont font = fontable(client, inputStream.readInt());
        int bytes = client.getRemainingRequestLength();
        int chars = Math.max(0, bytes / 2 - (oddLength ? 1 : 0));
        inputStream.skip(bytes);
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort((short) font.ascent);
            outputStream.writeShort((short) font.descent);
            outputStream.writeShort((short) font.ascent);    // overall ascent
            outputStream.writeShort((short) font.descent);   // overall descent
            outputStream.writeInt(chars * font.width);       // overall width
            outputStream.writeInt(0);                        // overall left
            outputStream.writeInt(chars * font.width);       // overall right
            outputStream.writePad(4);
        }
    }

    public static void listFonts(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int maxNames = inputStream.readUnsignedShort();
        int patternLength = inputStream.readUnsignedShort();
        String pattern = inputStream.readString8(patternLength);
        List<String> names = XFont.list(pattern, Math.max(1, maxNames));

        int bytes = 0;
        for (String name : names) bytes += 1 + name.length();
        int pad = -bytes & 3;
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((bytes + pad) / 4);
            outputStream.writeShort((short) names.size());
            outputStream.writePad(22);
            for (String name : names) {
                outputStream.writeByte((byte) name.length());
                outputStream.write(name.getBytes(com.winlator.cmod.xserver.XServer.LATIN1_CHARSET));
            }
            if (pad > 0) outputStream.writePad(pad);
        }
    }

    // ---- Text ----------------------------------------------------------------------------------------------------

    /** PolyText8 / PolyText16: strings with a shift between them and possibly a change of font; no background. */
    public static void polyText(XClient client, XInputStream inputStream, boolean wide) throws XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        int x = inputStream.readShort();
        int y = inputStream.readShort();
        int remaining = client.getRemainingRequestLength();

        com.winlator.cmod.xserver.Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) {
            inputStream.skip(remaining);
            throw new BadGraphicsContext(gcId);
        }
        XFont font = client.xServer.fonts.get(graphicsContext.getFont());
        if (font == null) font = XFont.open("fixed");

        while (remaining > 1) {
            int count = inputStream.readUnsignedByte();
            remaining--;
            if (count == 255) {
                // A new font, four bytes, most significant first.
                if (remaining < 4) break;
                int id = 0;
                for (int i = 0; i < 4; i++) id = (id << 8) | inputStream.readUnsignedByte();
                remaining -= 4;
                XFont changed = client.xServer.fonts.get(id);
                if (changed != null) font = changed;
                continue;
            }
            int delta = inputStream.readByte();
            remaining--;
            int bytes = count * (wide ? 2 : 1);
            if (bytes > remaining) bytes = remaining;
            int[] codes = new int[wide ? bytes / 2 : bytes];
            for (int i = 0; i < codes.length; i++) {
                if (wide) {
                    int high = inputStream.readUnsignedByte();
                    int low = inputStream.readUnsignedByte();
                    codes[i] = (high << 8) | low;
                } else codes[i] = inputStream.readUnsignedByte();
            }
            remaining -= wide ? codes.length * 2 : codes.length;
            x += delta;
            if (drawable != null) font.draw(drawable, x, y, graphicsContext.getForeground(), null, codes);
            x += codes.length * font.width;
        }
        if (remaining > 0) inputStream.skip(remaining);
    }

    /** ImageText8 / ImageText16: one string on its own background. */
    public static void imageText(XClient client, XInputStream inputStream, boolean wide) throws XRequestError {
        int count = client.getRequestData() & 0xff;
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        int x = inputStream.readShort();
        int y = inputStream.readShort();
        int remaining = client.getRemainingRequestLength();

        com.winlator.cmod.xserver.Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) {
            inputStream.skip(remaining);
            throw new BadGraphicsContext(gcId);
        }
        XFont font = client.xServer.fonts.get(graphicsContext.getFont());
        if (font == null) font = XFont.open("fixed");

        int[] codes = new int[count];
        for (int i = 0; i < count; i++) {
            if (wide) {
                int high = inputStream.readUnsignedByte();
                int low = inputStream.readUnsignedByte();
                codes[i] = (high << 8) | low;
                remaining -= 2;
            } else {
                codes[i] = inputStream.readUnsignedByte();
                remaining--;
            }
        }
        if (remaining > 0) inputStream.skip(remaining);
        if (drawable != null) font.draw(drawable, x, y, graphicsContext.getForeground(), graphicsContext.getBackground(), codes);
    }
}
