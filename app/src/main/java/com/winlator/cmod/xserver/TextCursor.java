package com.winlator.cmod.xserver;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Whether a cursor is a text cursor (the I-beam a program shows over a text field). X11 has no "a text field has the
 * focus" signal a server can see, but a program shows the I-beam when the pointer is over something editable, and the
 * app uses that to bring up the on-screen keyboard after a tap.
 *
 * The cursor's picture is judged by its shape: tall and narrow, a thin stem in the middle and wider ends (the serifs).
 */
public final class TextCursor {
    private static final String TAG = "TextCursor";

    private TextCursor() {}

    public static boolean isTextCursor(Cursor cursor) {
        if (cursor == null || !cursor.isVisible() || cursor.cursorImage == null) return false;
        Drawable image = cursor.cursorImage;
        int width = image.width, height = image.height;
        if (width < 3 || height < 8 || width > 128 || height > 128) return false;

        // Which pixels belong to the cursor: the mask's set bits when it has one, else the picture's own alpha.
        boolean[][] solid = new boolean[height][width];
        Drawable mask = cursor.maskImage;
        ByteBuffer maskBuffer = mask != null ? mask.lockBuffer(mask.backingAHB) : null;
        ByteBuffer imageBuffer = image.lockBuffer(image.backingAHB);
        try {
            if (imageBuffer == null) return false;
            IntBuffer pixels = imageBuffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            IntBuffer maskPixels = maskBuffer != null ? maskBuffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer() : null;
            int stride = image.getStride();
            int maskStride = mask != null ? mask.getStride() : 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    boolean set;
                    if (maskPixels != null && x < mask.width && y < mask.height) set = (maskPixels.get(y * maskStride + x) & 0xffffff) != 0;
                    else set = (pixels.get(y * stride + x) >>> 24) > 0x40;
                    solid[y][x] = set;
                }
            }
        }
        finally {
            image.unlockBuffer(image.backingAHB);
            if (mask != null) mask.unlockBuffer(mask.backingAHB);
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            StringBuilder art = new StringBuilder("cursor picture:");
            for (int y = 0; y < height; y++) {
                art.append('\n');
                for (int x = 0; x < width; x++) art.append(solid[y][x] ? '#' : '.');
            }
            Log.d(TAG, art.toString());
        }
        int top = -1, bottom = -1, left = width, right = -1;
        int[] spans = new int[height];
        for (int y = 0; y < height; y++) {
            int rowLeft = -1, rowRight = -1;
            for (int x = 0; x < width; x++) {
                if (!solid[y][x]) continue;
                if (rowLeft < 0) rowLeft = x;
                rowRight = x;
            }
            if (rowLeft < 0) continue;
            spans[y] = rowRight - rowLeft + 1;
            if (top < 0) top = y;
            bottom = y;
            left = Math.min(left, rowLeft);
            right = Math.max(right, rowRight);
        }
        if (top < 0) return false;
        int shapeHeight = bottom - top + 1, shapeWidth = right - left + 1;

        // The stem: the widest the middle half of the cursor is. The ends: the widest of its first and last rows.
        int stem = 0;
        for (int y = top + shapeHeight / 4; y <= bottom - shapeHeight / 4; y++) stem = Math.max(stem, spans[y]);
        int serifTop = 0, serifBottom = 0;
        for (int y = top; y < top + Math.max(2, shapeHeight / 6); y++) serifTop = Math.max(serifTop, spans[y]);
        for (int y = bottom - Math.max(2, shapeHeight / 6) + 1; y <= bottom; y++) serifBottom = Math.max(serifBottom, spans[y]);

        boolean tall = shapeHeight >= 8 && shapeHeight >= shapeWidth * 1.4f;
        boolean thinStem = stem <= Math.max(3, shapeWidth * 2 / 5);
        boolean serifs = serifTop >= stem + 2 && serifBottom >= stem + 2;
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "cursor " + width + "x" + height + " shape " + shapeWidth + "x" + shapeHeight + " stem " + stem
                    + " serifs " + serifTop + "/" + serifBottom + " -> " + (tall && thinStem && serifs));
        }
        return tall && thinStem && serifs;
    }
}
