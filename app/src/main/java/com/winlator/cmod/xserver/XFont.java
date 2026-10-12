package com.winlator.cmod.xserver;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A core X11 font. The server has no font files: a font is the system's monospace typeface at the pixel size its name asks
 * for ("fixed", "6x13", or an XLFD such as -misc-fixed-medium-r-normal--13-120-75-75-c-70-iso8859-1). Every glyph has the same
 * advance, so a client that works out its layout from QueryFont (Xlib does) places the text where the server draws it.
 */
public final class XFont {
    public static final int DEFAULT_SIZE = 13;
    private static final int[] SIZES = {8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 20, 22, 24};
    // family -> foundry, as XLFD names them
    private static final String[][] FAMILIES = {
            {"misc", "fixed"}, {"adobe", "courier"}, {"adobe", "helvetica"}, {"adobe", "times"}, {"adobe", "new century schoolbook"},
            {"b&h", "lucida"}, {"b&h", "lucidatypewriter"}, {"misc", "clean"}
    };
    private static final List<String> NAMES = new ArrayList<>();

    static {
        NAMES.add("fixed");
        NAMES.add("variable");
        NAMES.add("6x13");
        NAMES.add("7x13");
        NAMES.add("8x13");
        NAMES.add("9x15");
        for (String[] family : FAMILIES) {
            for (String weight : new String[]{"medium", "bold"}) {
                for (String slant : new String[]{"r", "i"}) {
                    for (int size : SIZES) {
                        NAMES.add("-" + family[0] + "-" + family[1] + "-" + weight + "-" + slant + "-normal--" + size + "-" + (size * 10)
                                + "-75-75-c-" + Math.round(size * 0.55f * 10) + "-iso8859-1");
                    }
                }
            }
        }
    }

    public final String name;
    public final int ascent;
    public final int descent;
    /** The advance of every glyph. */
    public final int width;
    private final Paint paint;

    private XFont(String name, int pixelSize, boolean bold, boolean italic) {
        this.name = name;
        int style = (bold ? Typeface.BOLD : 0) | (italic ? Typeface.ITALIC : 0);
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create(Typeface.MONOSPACE, style));
        paint.setTextSize(pixelSize);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float height = metrics.descent - metrics.ascent;
        if (height > 0) paint.setTextSize(pixelSize * pixelSize / height);
        metrics = paint.getFontMetrics();
        ascent = Math.max(1, Math.round(-metrics.ascent));
        descent = Math.max(0, Math.round(metrics.descent));
        width = Math.max(1, Math.round(paint.measureText("M")));
    }

    /** The font a name asks for; any name gives one (an unknown name gets the default size). */
    public static XFont open(String name) {
        int size = DEFAULT_SIZE;
        boolean bold = false;
        boolean italic = false;
        String lower = name.toLowerCase();
        java.util.regex.Matcher cell = Pattern.compile("^(\\d+)x(\\d+)(bold)?$").matcher(lower);
        if (cell.matches()) {
            size = Integer.parseInt(cell.group(2));
            bold = cell.group(3) != null;
        } else if (lower.startsWith("-")) {
            String[] parts = lower.split("-", -1);
            if (parts.length > 8) {
                bold = parts[3].equals("bold") || parts[3].equals("demibold");
                italic = parts[4].equals("i") || parts[4].equals("o");
                try {
                    int pixels = Integer.parseInt(parts[7]);
                    if (pixels > 0) size = pixels;
                    else if (parts.length > 8) {
                        int points = Integer.parseInt(parts[8]);
                        if (points > 0) size = Math.round(points / 10f * 1.0f);
                    }
                } catch (NumberFormatException ignored) {}
            }
        } else if (lower.contains("bold")) {
            bold = true;
        }
        return new XFont(name, Math.max(4, Math.min(size, 96)), bold, italic);
    }

    /** The names that match an X pattern ("*" any run, "?" one character, case does not matter). */
    public static List<String> list(String pattern, int max) {
        String regex = Pattern.quote(pattern.toLowerCase()).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q");
        Pattern compiled = Pattern.compile(regex);
        List<String> matches = new ArrayList<>();
        for (String candidate : NAMES) {
            if (compiled.matcher(candidate.toLowerCase()).matches()) {
                matches.add(candidate);
                if (matches.size() >= max) break;
            }
        }
        return matches;
    }

    /**
     * Draws {@code codes} with the baseline at (x, y). With {@code background} (an ImageText request) the cells are filled
     * with it first; without, only the glyphs change the drawable.
     */
    public void draw(Drawable drawable, int x, int y, int foreground, Integer background, int[] codes) {
        if (codes.length == 0) return;
        int left = x;
        int top = y - ascent;
        int w = codes.length * width;
        int h = ascent + descent;
        // Clip to the drawable.
        int x0 = Math.max(0, left);
        int y0 = Math.max(0, top);
        int x1 = Math.min(drawable.width, left + w);
        int y1 = Math.min(drawable.height, top + h);
        if (x1 <= x0 || y1 <= y0 || w > 8192 || h > 512) return;

        Bitmap mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(mask);
        paint.setColor(Color.WHITE);
        char[] one = new char[1];
        for (int i = 0; i < codes.length; i++) {
            int code = codes[i];
            if (code < 32 || code == 127) continue;
            one[0] = (char) code;
            canvas.drawText(one, 0, 1, i * width, ascent, paint);
        }
        int[] coverage = new int[w * h];
        mask.getPixels(coverage, 0, w, 0, 0, w, h);
        mask.recycle();

        int regionW = x1 - x0;
        int regionH = y1 - y0;
        ByteBuffer region = drawable.getImage((short) x0, (short) y0, (short) regionW, (short) regionH);
        IntBuffer pixels = region.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int fr = (foreground >> 16) & 0xff, fg = (foreground >> 8) & 0xff, fb = foreground & 0xff;
        for (int row = 0; row < regionH; row++) {
            for (int col = 0; col < regionW; col++) {
                int index = row * regionW + col;
                int alpha = (coverage[(y0 - top + row) * w + (x0 - left + col)] >>> 24) & 0xff;
                int dst = pixels.get(index);
                if (background != null) dst = background;
                if (alpha != 0) {
                    int dr = (dst >> 16) & 0xff, dg = (dst >> 8) & 0xff, db = dst & 0xff;
                    dr += (fr - dr) * alpha / 255;
                    dg += (fg - dg) * alpha / 255;
                    db += (fb - db) * alpha / 255;
                    dst = (dr << 16) | (dg << 8) | db;
                }
                pixels.put(index, 0xff000000 | (dst & 0xffffff));
            }
        }
        region.rewind();
        drawable.drawImage((short) 0, (short) 0, (short) x0, (short) y0, (short) regionW, (short) regionH, (byte) 32, region,
                (short) regionW, (short) regionH);
    }
}
