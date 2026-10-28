package com.simple.carlauncher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * Draws numbers as classic seven-segment LCD digits (like a car dashboard), with the unlit
 * segments still faintly visible. Digits are drawn with beveled segment ends and a slight slant.
 */
final class SevenSeg {

    private SevenSeg() { }

    //                                      a  b  c  d  e  f  g
    private static final boolean[][] DIGITS = {
            {true, true, true, true, true, true, false},     // 0
            {false, true, true, false, false, false, false}, // 1
            {true, true, false, true, true, false, true},    // 2
            {true, true, true, true, false, false, true},    // 3
            {false, true, true, false, false, true, true},   // 4
            {true, false, true, true, false, true, true},    // 5
            {true, false, true, true, true, true, true},     // 6
            {true, true, true, false, false, false, false},  // 7
            {true, true, true, true, true, true, true},      // 8
            {true, true, true, true, false, true, true},     // 9
    };
    private static final boolean[] DASH = {false, false, false, false, false, false, true};
    private static final boolean[] BLANK = new boolean[7];

    /** Width of {@code count} digits of the given height, including the spacing between them. */
    static float width(int count, float h) {
        float w = h * 0.52f;
        return count * w + (count - 1) * w * 0.22f;
    }

    /**
     * Draws {@code text} (digits, '-' or ' ') with its top-left corner at (x, y).
     * Leading positions given as ' ' show only the faint unlit segments.
     */
    static void draw(Canvas c, String text, float x, float y, float h, Paint on, Paint off) {
        float w = h * 0.52f, step = w * 1.22f;
        c.save();
        c.skew(-0.08f, 0f);
        float shift = 0.08f * (y + h); // keep the slanted digits where they were asked to be
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            boolean[] segs = ch >= '0' && ch <= '9' ? DIGITS[ch - '0'] : ch == '-' ? DASH : BLANK;
            digit(c, segs, x + shift + i * step, y, w, h, on, off);
        }
        c.restore();
    }

    private static void digit(Canvas c, boolean[] s, float x, float y, float w, float h, Paint on, Paint off) {
        float t = w * 0.19f, g = t * 0.14f, ht = t / 2f;
        float midY = y + h / 2f;
        // a, b, c, d, e, f, g
        horiz(c, x + ht + g, x + w - ht - g, y + ht, ht, s[0] ? on : off);
        vert(c, x + w - ht, y + ht + g, midY - g, ht, s[1] ? on : off);
        vert(c, x + w - ht, midY + g, y + h - ht - g, ht, s[2] ? on : off);
        horiz(c, x + ht + g, x + w - ht - g, y + h - ht, ht, s[3] ? on : off);
        vert(c, x + ht, midY + g, y + h - ht - g, ht, s[4] ? on : off);
        vert(c, x + ht, y + ht + g, midY - g, ht, s[5] ? on : off);
        horiz(c, x + ht + g, x + w - ht - g, midY, ht, s[6] ? on : off);
    }

    private static void horiz(Canvas c, float x1, float x2, float yc, float ht, Paint p) {
        Path path = new Path();
        path.moveTo(x1, yc);
        path.lineTo(x1 + ht, yc - ht);
        path.lineTo(x2 - ht, yc - ht);
        path.lineTo(x2, yc);
        path.lineTo(x2 - ht, yc + ht);
        path.lineTo(x1 + ht, yc + ht);
        path.close();
        c.drawPath(path, p);
    }

    private static void vert(Canvas c, float xc, float y1, float y2, float ht, Paint p) {
        Path path = new Path();
        path.moveTo(xc, y1);
        path.lineTo(xc + ht, y1 + ht);
        path.lineTo(xc + ht, y2 - ht);
        path.lineTo(xc, y2);
        path.lineTo(xc - ht, y2 - ht);
        path.lineTo(xc - ht, y1 + ht);
        path.close();
        c.drawPath(path, p);
    }

    /** Speed as three LCD positions: "078" style with unlit leading digits, "---" without GPS. */
    static String speedText(boolean fix, float kmh) {
        if (!fix) return "---";
        int v = Math.max(0, Math.min(999, Math.round(kmh)));
        String s = String.valueOf(v);
        while (s.length() < 3) s = " " + s;
        return s;
    }
}
