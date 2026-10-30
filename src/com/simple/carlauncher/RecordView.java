package com.simple.carlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/**
 * Big vinyl record seen from above, with a tonearm coming from the top right corner (Bluetooth
 * player). The label is printed asymmetrically (text, stripes, a marker) and the vinyl has
 * scratches and dust, so the spinning is easy to see; the light reflections stay fixed like on
 * a real turntable. The arm swings onto the record while music plays.
 */
final class RecordView extends View {

    private static final float RPM = 33.3f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final Matrix m = new Matrix();
    private final float d;
    private final float[][] specks; // {angle, radius fraction, length, alpha}

    private boolean playing;
    private float angle, speed, arm;
    private float cue;          // seconds left of a "change track" arm move
    private Bitmap art;
    private String labelText = "BLUETOOTH";
    private ValueAnimator anim;

    RecordView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        Random rnd = new Random(7);
        specks = new float[46][];
        for (int i = 0; i < specks.length; i++) {
            specks[i] = new float[]{rnd.nextFloat() * 360f, 0.45f + rnd.nextFloat() * 0.52f,
                    2f + rnd.nextFloat() * (i < 6 ? 40f : 9f), 40 + rnd.nextInt(90)};
        }
    }

    void setPlaying(boolean on) {
        if (on == playing) return;
        playing = on;
        start();
    }

    void setArt(Bitmap b) {
        if (b != art) {
            art = b;
            invalidate();
        }
    }

    /** New song: the arm lifts, swings out and drops back onto the record. */
    void cue() {
        if (arm < 0.5f) return; // not on the record yet
        cue = 1.3f;
        start();
    }

    void setLabel(String s) {
        labelText = s == null || s.isEmpty() ? "BLUETOOTH" : s;
        invalidate();
    }

    private void start() {
        if (anim != null && anim.isRunning()) return;
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
            float targetArm = playing ? 1f : 0f;
            if (cue > 0) {
                cue -= dt;
                targetArm = cue > 0.6f ? 0.2f : 1f; // out to the rest, then back down
            }
            arm += Math.signum(targetArm - arm) * Math.min(Math.abs(targetArm - arm), dt * (cue > 0 ? 2.6f : 1.4f));
            float targetSpeed = playing && arm > 0.95f ? 1f : 0f;
            speed += (targetSpeed - speed) * Math.min(1f, dt * (playing ? 2.5f : 1.2f));
            angle = (angle + speed * RPM * 6f * dt) % 360f;
            invalidate();
            if (!playing && cue <= 0 && arm <= 0f && speed < 0.01f) {
                speed = 0;
                a.cancel();
            }
        });
        anim.start();
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
        float rad = Math.min(w, h) / 2f - 30 * d;
        float cx = w / 2f, cy = h / 2f;

        // soft blue glow behind the deck
        p.setShader(new RadialGradient(cx, cy, rad * 1.3f, new int[]{0x4400A8FF, 0x0000A8FF},
                null, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rad * 1.3f, p);
        float ring = rad + 16 * d; // platter rim with strobe dots
        p.setShader(new SweepGradient(cx, cy, new int[]{0xFF2A2C32, 0xFFB8BCC6, 0xFF2A2C32, 0xFF9CA0AA, 0xFF2A2C32}, null));
        c.drawCircle(cx, cy, ring, p);
        p.setShader(null);
        p.setColor(0xFF15161A);
        c.drawCircle(cx, cy, ring - 3 * d, p);

        // ---- rotating part ----
        c.save();
        c.rotate(angle, cx, cy);
        // strobe dots: two rows on the platter rim, turning with it (the spin is easy to see)
        for (int row = 0; row < 2; row++) {
            float rr = rad + (row == 0 ? 5.5f : 11f) * d;
            int count = row == 0 ? 72 : 60;
            p.setColor(row == 0 ? 0xFFD8DCE6 : 0xFF8A90A0);
            for (int i = 0; i < count; i++) {
                double a = Math.toRadians(i * 360.0 / count);
                c.drawCircle(cx + (float) Math.cos(a) * rr, cy + (float) Math.sin(a) * rr, 1.6f * d, p);
            }
        }
        p.setColor(0xFF0B0B0D);
        c.drawCircle(cx, cy, rad, p);
        // grooves, with brighter gaps between tracks
        p.setStyle(Paint.Style.STROKE);
        float step = Math.max(2.4f * d, rad * 0.022f);
        int n = 0;
        for (float g = rad * 0.98f; g > rad * 0.4f; g -= step, n++) {
            boolean gap = n % 11 == 0;
            p.setStrokeWidth(gap ? 1.6f * d : 0.7f * d);
            p.setColor(gap ? 0x24FFFFFF : 0x10FFFFFF);
            c.drawCircle(cx, cy, g, p);
        }
        // scratches and dust that turn with the record
        p.setStrokeCap(Paint.Cap.ROUND);
        for (float[] s : specks) {
            float a0 = s[0], rr = rad * s[1];
            p.setStrokeWidth(s[2] > 12 ? 1.2f * d : 2f * d);
            p.setColor(((int) s[3] << 24) | 0xFFFFFF);
            r.set(cx - rr, cy - rr, cx + rr, cy + rr);
            c.drawArc(r, a0, s[2] > 12 ? s[2] : 0.6f, false, p);
        }
        p.setStyle(Paint.Style.FILL);

        // label: album art if known, otherwise a printed copper label
        float lr = rad * 0.36f;
        if (art != null) {
            BitmapShader bs = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = 2 * lr / Math.min(art.getWidth(), art.getHeight());
            m.reset();
            m.setScale(scale, scale);
            m.postTranslate(cx - art.getWidth() * scale / 2f, cy - art.getHeight() * scale / 2f);
            bs.setLocalMatrix(m);
            p.setShader(bs);
            c.drawCircle(cx, cy, lr, p);
            p.setShader(null);
        } else {
            p.setColor(0xFF0D47A1);                              // blue rim around the label
            c.drawCircle(cx, cy, lr, p);
            p.setShader(new RadialGradient(cx - lr * 0.2f, cy - lr * 0.2f, lr * 1.1f,
                    new int[]{0xFFFF5A4E, 0xFFD32F2F, 0xFF7F0000}, null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, lr * 0.88f, p);
            p.setShader(null);
        }
        // printed details (always on top, so the turning shows even over album art)
        p.setColor(0xCC1A0A04);
        r.set(cx - lr * 0.92f, cy - lr * 0.08f, cx - lr * 0.48f, cy + lr * 0.08f);
        c.drawRoundRect(r, lr * 0.05f, lr * 0.05f, p);           // stripe on the left
        p.setColor(0xFFFFFFFF);
        c.drawCircle(cx + lr * 0.72f, cy, lr * 0.08f, p);       // white marker dot on the right
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(lr * 0.2f);
        p.setColor(0xEEFFFFFF);
        path.reset();
        r.set(cx - lr * 0.72f, cy - lr * 0.72f, cx + lr * 0.72f, cy + lr * 0.72f);
        path.addArc(r, 200, 140);
        c.drawTextOnPath(labelText.toUpperCase(), path, 0, 0, p); // curved text along the top
        p.setTextSize(lr * 0.15f);
        c.drawText("33⅓ RPM · STEREO", cx, cy + lr * 0.55f, p);
        p.setColor(0xFF0B0B0D);
        c.drawCircle(cx, cy, lr * 0.06f, p);                   // spindle hole
        c.restore();

        // ---- fixed light reflections (do not turn) ----
        p.setShader(new SweepGradient(cx, cy,
                new int[]{0x0000B0FF, 0x3300B0FF, 0x0000B0FF, 0x0000B0FF, 0x2600B0FF, 0x0000B0FF, 0x0000B0FF},
                new float[]{0.05f, 0.12f, 0.2f, 0.55f, 0.62f, 0.7f, 1f}));
        path.reset();
        path.addCircle(cx, cy, rad, Path.Direction.CW);
        path.addCircle(cx, cy, lr, Path.Direction.CCW);
        c.drawPath(path, p);
        p.setShader(null);
        // spindle
        p.setShader(new RadialGradient(cx - 1.5f * d, cy - 1.5f * d, 6 * d, 0xFFFFFFFF, 0xFF6E6E78, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, 4 * d, p);
        p.setShader(null);

        // ---- tonearm from the top right corner ----
        float px = Math.min(w - 22 * d, cx + rad * 1.12f), py = Math.max(22 * d, cy - rad * 1.0f);
        float playX = cx + rad * 0.55f, playY = cy - rad * 0.2f;   // outer grooves
        float restX = px + 4 * d, restY = cy + rad * 0.2f;         // off the record, on the right
        float aPlay = (float) Math.toDegrees(Math.atan2(playY - py, playX - px));
        float aRest = (float) Math.toDegrees(Math.atan2(restY - py, restX - px));
        float e = arm * arm * (3 - 2 * arm);
        float ang = aRest + (aPlay - aRest) * e;
        float len = (float) Math.hypot(playX - px, playY - py);
        // base
        p.setShader(new RadialGradient(px - 4 * d, py - 4 * d, 26 * d, 0xFFE8E8EE, 0xFF4A4A52, Shader.TileMode.CLAMP));
        c.drawCircle(px, py, 18 * d, p);
        p.setShader(null);
        c.save();
        c.rotate(ang, px, py);
        r.set(px - 28 * d, py - 8 * d, px - 12 * d, py + 8 * d);  // counterweight
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFFD8D8E0, 0xFF5E5E68, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 3 * d, 3 * d, p);
        r.set(px, py - 2.6f * d, px + len - 14 * d, py + 2.6f * d); // arm tube
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFFF4F4FA, 0xFF8A8A94, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 2.6f * d, 2.6f * d, p);
        r.set(px + len - 18 * d, py - 6.5f * d, px + len + 6 * d, py + 6.5f * d); // headshell
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFF4A4A54, 0xFF1A1A20, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 3 * d, 3 * d, p);
        p.setShader(null);
        p.setColor(0xFF00B0FF);
        c.drawRect(px + len - 2 * d, py - 6.5f * d, px + len + 1.5f * d, py + 6.5f * d, p);
        c.restore();
        p.setShader(new RadialGradient(px - 2 * d, py - 2 * d, 10 * d, 0xFFFFFFFF, 0xFF777780, Shader.TileMode.CLAMP));
        c.drawCircle(px, py, 7 * d, p);
        p.setShader(null);
    }
}
