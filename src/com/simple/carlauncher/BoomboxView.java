package com.simple.carlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Retro boombox look for the MP3 side of the music card: a tuning-dial strip with a red needle,
 * a row of LED level bars and two speakers. While music plays the woofers pulse, the LEDs bounce
 * and the needle drifts; when paused everything rests.
 */
final class BoomboxView extends View {

    private static final int BARS = 14;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float d;
    private final float[] levels = new float[BARS];
    private final float[] targets = new float[BARS];

    private boolean playing;
    private float t;          // seconds of animation time
    private ValueAnimator anim;

    BoomboxView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
    }

    void setPlaying(boolean on) {
        if (on == playing) return;
        playing = on;
        if (on) start();
        else if (anim != null) anim.cancel();
        invalidate();
    }

    private void start() {
        if (anim != null) anim.cancel();
        if (!isAttachedToWindow()) return;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1000);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        final long[] last = {System.nanoTime()};
        anim.addUpdateListener(a -> {
            long now = System.nanoTime();
            float dt = Math.min(0.1f, (now - last[0]) / 1e9f);
            last[0] = now;
            t += dt;
            for (int i = 0; i < BARS; i++) {
                if (Math.random() < dt * 6) targets[i] = (float) (0.25 + Math.random() * 0.75 * bandShape(i));
                levels[i] += (targets[i] - levels[i]) * Math.min(1f, dt * 12);
            }
            invalidate();
        });
        anim.start();
    }

    /** Bass bands a bit higher than treble, like real music. */
    private static float bandShape(int i) {
        return 1f - 0.4f * i / (BARS - 1);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (playing) start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) anim.cancel();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float pad = 14 * d;

        // ---- Tuning dial ----
        float dialH = Math.min(h * 0.2f, 46 * d);
        r.set(pad, 0, w - pad, dialH);
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFF3A3A42, 0xFF16161B, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 8 * d, 8 * d, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f * d);
        p.setColor(0x66C0C0CC);
        c.drawRoundRect(r, 8 * d, 8 * d, p);
        p.setStyle(Paint.Style.FILL);

        float sx0 = r.left + 12 * d, sx1 = r.right - 12 * d;
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(Math.max(8 * d, dialH * 0.2f));
        int[] marks = {88, 92, 96, 100, 104, 108};
        for (int i = 0; i <= 40; i++) {
            float x = sx0 + (sx1 - sx0) * i / 40f;
            boolean major = i % 8 == 0;
            p.setColor(major ? 0xCCE8E8F0 : 0x66E8E8F0);
            c.drawRect(x - 0.6f * d, r.top + dialH * 0.18f, x + 0.6f * d,
                    r.top + dialH * (major ? 0.45f : 0.32f), p);
            if (major) {
                p.setColor(0x99E8E8F0);
                c.drawText(String.valueOf(marks[i / 8]), x, r.top + dialH * 0.78f, p);
            }
        }
        // red needle, resting or drifting with the music
        float pos = playing ? 0.55f + 0.18f * (float) Math.sin(t * 0.35) : 0.42f;
        float nx = sx0 + (sx1 - sx0) * pos;
        p.setColor(0xFFFF3B30);
        c.drawRect(nx - 1.4f * d, r.top + 4 * d, nx + 1.4f * d, r.bottom - 4 * d, p);

        // ---- LED level bars ----
        float vuTop = dialH + 10 * d, vuH = Math.min(h * 0.09f, 18 * d);
        float bw = (w - 2 * pad) / BARS;
        for (int i = 0; i < BARS; i++) {
            float lvl = playing ? levels[i] : 0.06f;
            int segs = 6;
            for (int sgm = 0; sgm < segs; sgm++) {
                float frac = (sgm + 1f) / segs;
                boolean lit = frac <= lvl + 0.001f;
                int color = sgm >= 5 ? 0xFFFF3B30 : sgm >= 3 ? 0xFFFFC107 : 0xFF00E5FF;
                p.setColor(lit ? color : 0x1FFFFFFF);
                float x0 = pad + i * bw + bw * 0.15f, x1 = pad + (i + 1) * bw - bw * 0.15f;
                float segH = vuH / segs;
                float y1 = vuTop + vuH - sgm * segH, y0 = y1 - segH * 0.7f;
                c.drawRect(x0, y0, x1, y1, p);
            }
        }

        // ---- Two speakers ----
        float top = vuTop + vuH + 10 * d;
        float avail = h - top;
        float rad = Math.min((w - 3 * pad) / 4f, avail / 2f) - 2 * d;
        float cy = top + avail / 2f;
        float gapX = (w - 4 * rad) / 3f;
        drawSpeaker(c, gapX + rad, cy, rad, 0f);
        drawSpeaker(c, w - gapX - rad, cy, rad, 0.37f);
    }

    private void drawSpeaker(Canvas c, float cx, float cy, float rad, float phase) {
        // chrome rim
        p.setShader(new LinearGradient(cx - rad, cy - rad, cx + rad, cy + rad,
                new int[]{0xFFE6E6EE, 0xFF6E6E78, 0xFFD0D0D8}, null, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rad, p);
        p.setShader(null);
        // grille
        p.setColor(0xFF0E0E12);
        float gr = rad * 0.9f;
        c.drawCircle(cx, cy, gr, p);
        p.setColor(0xFF2A2A31);
        float step = Math.max(3 * d, rad * 0.075f);
        for (float y = cy - gr; y <= cy + gr; y += step) {
            for (float x = cx - gr; x <= cx + gr; x += step) {
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy < gr * gr * 0.92f) c.drawCircle(x, y, step * 0.18f, p);
            }
        }
        // woofer cone, pulsing with the beat while playing
        float beat = playing ? (float) Math.pow(Math.max(0, Math.sin((t + phase) * Math.PI * 2 * 1.9)), 6) : 0f;
        float cone = rad * (0.58f + 0.05f * beat);
        p.setShader(new RadialGradient(cx - cone * 0.25f, cy - cone * 0.3f, cone * 1.3f,
                new int[]{0xFF4A4A54, 0xFF1A1A20, 0xFF060608}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, cone, p);
        // dust cap
        float cap = cone * 0.38f;
        p.setShader(new RadialGradient(cx - cap * 0.3f, cy - cap * 0.35f, cap * 1.2f,
                0xFFF2F2F8, 0xFF5A5A64, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, cap, p);
        p.setShader(null);
        // thin surround ring
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * d);
        p.setColor(0x55FFFFFF);
        c.drawCircle(cx, cy, cone, p);
        p.setStyle(Paint.Style.FILL);
    }
}
