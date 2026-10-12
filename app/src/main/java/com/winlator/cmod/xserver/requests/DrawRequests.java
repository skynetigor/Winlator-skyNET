package com.winlator.cmod.xserver.requests;

import android.util.Log;
import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.GraphicsContext;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.errors.BadDrawable;
import com.winlator.cmod.xserver.errors.BadGraphicsContext;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.ByteBuffer;

public abstract class DrawRequests {
    public enum Format {BITMAP, XY_PIXMAP, Z_PIXMAP}
    private enum CoordinateMode {ORIGIN, PREVIOUS}

    public static void putImage(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        Format format = Format.values()[client.getRequestData()];
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        short dstX = inputStream.readShort();
        short dstY = inputStream.readShort();
        byte leftPad = inputStream.readByte();
        byte depth = inputStream.readByte();
        inputStream.skip(2);
        int length = client.getRemainingRequestLength();
        ByteBuffer data = inputStream.readByteBuffer(length);

        Drawable drawable =  client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        if (!(graphicsContext.getFunction() == GraphicsContext.Function.COPY || format == Format.Z_PIXMAP)) {
            throw new UnsupportedOperationException("GC Function other than COPY is not supported.");
        }

        switch (format) {
            case BITMAP:
                if (leftPad != 0) throw new UnsupportedOperationException("PutImage.leftPad cannot be != 0.");
                if (depth == 1) {
                    drawable.drawImage((short)0, (short)0, dstX, dstY, width, height, (byte)1, data, width, height);
                }
                else throw new BadMatch();
                break;
            case XY_PIXMAP:
                if (drawable.visual.depth != depth) throw new BadMatch();
                break;
            case Z_PIXMAP:
                if (leftPad == 0) {
                    drawable.drawImage((short)0, (short)0, dstX, dstY, width, height, depth, data, width, height);
                }
                else throw new BadMatch();
                break;
        }
    }

    public static void getImage(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        Format format = Format.values()[client.getRequestData()];
        int drawableId = inputStream.readInt();
        short x = inputStream.readShort();
        short y = inputStream.readShort();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        inputStream.skip(4);

        if (format != Format.Z_PIXMAP) throw new UnsupportedOperationException("Only Z_PIXMAP is supported.");

        Drawable drawable =  client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        int visualId = client.xServer.pixmapManager.getPixmap(drawableId) == null ? drawable.visual.id : 0;
        ByteBuffer data = drawable.getImage(x, y, width, height);
        int length = data.limit();

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(drawable.visual.depth);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((length + 3) / 4);
            outputStream.writeInt(visualId);
            outputStream.writePad(20);
            outputStream.write(data);
            if ((-length & 3) > 0) outputStream.writePad(-length & 3);
        }
    }

    public static void copyArea(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int srcDrawableId = inputStream.readInt();
        int dstDrawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short srcX = inputStream.readShort();
        short srcY = inputStream.readShort();
        short dstX = inputStream.readShort();
        short dstY = inputStream.readShort();
        short width = inputStream.readShort();
        short height = inputStream.readShort();

        Drawable srcDrawable = client.xServer.drawableManager.getDrawable(srcDrawableId);
        if (srcDrawable == null) throw new BadDrawable(srcDrawableId);
        if (srcDrawable.backingAHB == 0) {
            throw new IllegalStateException("srcDrawable has null data!");
        }

        Drawable dstDrawable = client.xServer.drawableManager.getDrawable(dstDrawableId);
        if (dstDrawable == null) throw new BadDrawable(dstDrawableId);
        if (dstDrawable.backingAHB == 0) {
            throw new IllegalStateException("dstDrawable has null data!");
        }
        
        if (srcDrawable.isOffscreen()) {
            client.xServer.getXServerView().nativeCompositeRedirect(srcDrawableId, dstDrawableId, dstX, dstY);
            return;
        }

        GraphicsContext graphicsContext =  client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        if (srcDrawable.visual.depth != dstDrawable.visual.depth) throw new BadMatch();

        dstDrawable.copyArea(srcX, srcY, dstX, dstY, width, height, srcDrawable, graphicsContext.getFunction());
    }

    public static void polyLine(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        CoordinateMode coordinateMode = CoordinateMode.values()[client.getRequestData()];
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);
        int length = client.getRemainingRequestLength();

        short[] points = new short[length / 2];
        int i = 0;
        while (length != 0) {
            points[i++] = inputStream.readShort();
            points[i++] = inputStream.readShort();
            length -= 4;
        }

        if (coordinateMode == CoordinateMode.ORIGIN && graphicsContext.getLineWidth() > 0) {
            drawable.drawLines(graphicsContext.getForeground(), graphicsContext.getLineWidth(), points);
        }
    }

    public static void polyFillRectangle(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);
        int length = client.getRemainingRequestLength();

        while (length != 0) {
            short x = inputStream.readShort();
            short y = inputStream.readShort();
            short width = inputStream.readShort();
            short height = inputStream.readShort();
            drawable.fillRect(x, y, width, height, graphicsContext.getForeground());
            length -= 8;
        }
    }

    /** Outlines: one pixel wide (or the GC's line width), in the foreground colour. */
    public static void polyRectangle(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        int length = client.getRemainingRequestLength();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) {
            inputStream.skip(length);
            throw new BadDrawable(drawableId);
        }
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) {
            inputStream.skip(length);
            throw new BadGraphicsContext(gcId);
        }
        int lineWidth = Math.max(1, graphicsContext.getLineWidth());
        int color = graphicsContext.getForeground();

        while (length >= 8) {
            int x = inputStream.readShort();
            int y = inputStream.readShort();
            int width = inputStream.readUnsignedShort();
            int height = inputStream.readUnsignedShort();
            length -= 8;
            // The outline covers width + 1 by height + 1 pixels.
            drawable.fillRect(x, y, width + 1, lineWidth, color);
            drawable.fillRect(x, y + height + 1 - lineWidth, width + 1, lineWidth, color);
            drawable.fillRect(x, y, lineWidth, height + 1, color);
            drawable.fillRect(x + width + 1 - lineWidth, y, lineWidth, height + 1, color);
        }
        if (length > 0) inputStream.skip(length);
    }

    public static void polySegment(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        int length = client.getRemainingRequestLength();

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) {
            inputStream.skip(length);
            throw new BadDrawable(drawableId);
        }
        GraphicsContext graphicsContext = client.xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) {
            inputStream.skip(length);
            throw new BadGraphicsContext(gcId);
        }
        int lineWidth = Math.max(1, graphicsContext.getLineWidth());
        int color = graphicsContext.getForeground();

        while (length >= 8) {
            short x1 = inputStream.readShort();
            short y1 = inputStream.readShort();
            short x2 = inputStream.readShort();
            short y2 = inputStream.readShort();
            length -= 8;
            if (x1 == x2 || y1 == y2) {
                // Horizontal and vertical lines (bevels, rules) are the common case: a filled strip, ends included.
                drawable.fillRect(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1) + lineWidth, Math.abs(y2 - y1) + lineWidth, color);
            } else {
                drawable.drawLine(x1, y1, x2, y2, color, lineWidth);
            }
        }
        if (length > 0) inputStream.skip(length);
    }
}