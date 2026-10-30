package com.simple.carlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

/**
 * A mechanical piano key of a cassette deck. It sinks while pressed and can stay latched down
 * (like PLAY while the tape runs). REW / FF support a long press that repeats while held.
 */
final class DeckKey extends View {

    static final int REW = 0, PLAY = 1, PAUSE = 2, FF = 3, EJECT = 4;

    private final int kind;
    private final String label;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final float d;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean latched, touching, held;
    private int accent = 0xFFFFFFFF;
    private Runnable onTap, onHold;

    DeckKey(Context c, int kind, String label) {
        super(c);
        this.kind = kind;
        this.label = label;
        d = getResources().getDisplayMetrics().density;
        setClickable(true);
    }

    void onTap(Runnable r) {
        onTap = r;
    }

    /** Repeated every 300 ms while the key is held (after 500 ms). */
    void onHold(Runnable r) {
        onHold = r;
    }

    void setLatched(boolean on) {
        if (on != latched) {
            latched = on;
            invalidate();
        }
    }

    void setAccent(int color) {
        accent = color;
        invalidate();
    }

    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            held = true;
            if (onHold != null) onHold.run();
            handler.postDelayed(this, 300);
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                held = false;
                if (onHold != null) handler.postDelayed(repeat, 500);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(repeat);
                if (touching && !held && onTap != null) {
                    performClick();
                    onTap.run();
                }
                touching = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(repeat);
                touching = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        boolean down = touching || latched;
        float sink = down ? 5 * d : 0;
        // slot the key sits in
        r.set(0, 0, w, h);
        p.setColor(0xFF050506);
        c.drawRoundRect(r, 8 * d, 8 * d, p);
        // key body: brushed silver, darker when pressed
        float top = 3 * d + sink, bottom = h - 3 * d;
        r.set(3 * d, top, w - 3 * d, bottom);
        p.setShader(new LinearGradient(0, top, 0, bottom,
                down ? new int[]{0xFF9A9AA4, 0xFF6E6E78, 0xFF4A4A52} : new int[]{0xFFEDEDF2, 0xFFBDBDC6, 0xFF8A8A94},
                null, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 6 * d, 6 * d, p);
        p.setShader(null);
        // front lip (shadow under the key when it is up)
        if (!down) {
            p.setColor(0xFF3A3A42);
            r.set(3 * d, bottom - 5 * d, w - 3 * d, bottom);
            c.drawRoundRect(r, 6 * d, 6 * d, p);
        }
        // symbol
        float cx = w / 2f, cy = top + (bottom - top - (down ? 0 : 5 * d)) * 0.42f;
        float s = Math.min(w, h) * 0.17f;
        p.setColor(latched ? accent : 0xFF1C1C22);
        path.reset();
        switch (kind) {
            case REW:
                tri(cx, cy, s, false, -s * 0.5f);
                tri(cx, cy, s, false, s * 0.5f);
                break;
            case FF:
                tri(cx, cy, s, true, -s * 0.5f);
                tri(cx, cy, s, true, s * 0.5f);
                break;
            case PLAY:
                tri(cx, cy, s * 1.1f, true, s * 0.15f);
                break;
            case PAUSE:
                c.drawRect(cx - s * 0.75f, cy - s, cx - s * 0.25f, cy + s, p);
                c.drawRect(cx + s * 0.25f, cy - s, cx + s * 0.75f, cy + s, p);
                break;
            case EJECT:
                path.moveTo(cx, cy - s);
                path.lineTo(cx + s, cy + s * 0.15f);
                path.lineTo(cx - s, cy + s * 0.15f);
                path.close();
                c.drawRect(cx - s, cy + s * 0.45f, cx + s, cy + s * 0.8f, p);
                break;
            default:
                break;
        }
        c.drawPath(path, p);
        // small printed label under the symbol
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(Math.max(9 * d, h * 0.13f));
        p.setLetterSpacing(0.08f);
        p.setColor(0xFF2A2A30);
        c.drawText(label, cx, cy + s + p.getTextSize() * 1.3f, p);
        // red indicator stripe on the latched key
        if (latched) {
            p.setColor(accent);
            r.set(w * 0.3f, top + 4 * d, w * 0.7f, top + 6.5f * d);
            c.drawRoundRect(r, 2 * d, 2 * d, p);
        }
    }

    /** Triangle pointing right (or left), centered at cx + dx. */
    private void tri(float cx, float cy, float s, boolean right, float dx) {
        float x = cx + dx;
        if (right) {
            path.moveTo(x - s * 0.6f, cy - s);
            path.lineTo(x + s * 0.6f, cy);
            path.lineTo(x - s * 0.6f, cy + s);
        } else {
            path.moveTo(x + s * 0.6f, cy - s);
            path.lineTo(x - s * 0.6f, cy);
            path.lineTo(x + s * 0.6f, cy + s);
        }
        path.close();
    }
}
