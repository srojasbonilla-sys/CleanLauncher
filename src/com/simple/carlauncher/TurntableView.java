package com.simple.carlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Record player for the "Multimedia" side of the music card: a vinyl record on a platter and a
 * tonearm. While music plays the record spins and the arm rests on it; on pause the record slows
 * to a stop and the arm swings back to its rest. The record label shows the album art if known.
 */
final class TurntableView extends View {

    private static final float RPM = 33.3f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private final Matrix m = new Matrix();
    private final float d;

    private boolean playing;
    private float cue; // seconds left of a "change track" arm move
    private float angle;      // record rotation, degrees
    private float speed;      // 0..1 of full speed (spins up / down smoothly)
    private float arm;        // 0 = resting, 1 = on the record
    private Bitmap art;
    private ValueAnimator anim;

    TurntableView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
    }

    void setPlaying(boolean on) {
        if (on == playing) return;
        playing = on;
        start();
    }

    /** New song: the arm lifts, swings out and drops back onto the record. */
    void cue() {
        if (arm < 0.5f) return;
        cue = 1.3f;
        start();
    }

    void setArt(Bitmap b) {
        if (b == art) return;
        art = b;
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
                targetArm = cue > 0.6f ? 0.2f : 1f;
            }
            arm += Math.signum(targetArm - arm) * Math.min(Math.abs(targetArm - arm), dt * (cue > 0 ? 2.6f : 1.4f));
            // the record only spins up once the needle is down, and coasts to a stop on pause
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
        float pad = 12 * d;

        // ---- Plinth ----
        r.set(pad, 0, w - pad, h);
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFF2C2C33, 0xFF141418, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 14 * d, 14 * d, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f * d);
        p.setColor(0x33FFFFFF);
        c.drawRoundRect(r, 14 * d, 14 * d, p);
        p.setStyle(Paint.Style.FILL);

        // ---- Platter + record (left part of the plinth, the arm lives on the right) ----
        float rad = Math.min((r.width() - 40 * d) / 2f, r.height() / 2f - 10 * d);
        float cx = r.left + 12 * d + rad, cy = r.centerY();

        p.setColor(0xFF09090B);                      // platter shadow
        c.drawCircle(cx + 2 * d, cy + 3 * d, rad + 3 * d, p);
        p.setShader(new SweepGradient(cx, cy,
                new int[]{0xFF8E8E98, 0xFFD6D6DE, 0xFF7A7A84, 0xFFC8C8D0, 0xFF8E8E98}, null));
        c.drawCircle(cx, cy, rad + 2.5f * d, p);    // platter rim
        p.setShader(null);

        c.save();
        c.rotate(angle, cx, cy);
        p.setColor(0xFF0C0C0E);
        c.drawCircle(cx, cy, rad, p);               // vinyl
        p.setStyle(Paint.Style.STROKE);             // grooves
        p.setStrokeWidth(0.8f * d);
        for (float gr = rad * 0.97f; gr > rad * 0.42f; gr -= Math.max(2.2f * d, rad * 0.035f)) {
            p.setColor(0x14FFFFFF);
            c.drawCircle(cx, cy, gr, p);
        }
        p.setStyle(Paint.Style.FILL);
        // light sheen: two soft wedges that turn with the record
        p.setShader(new SweepGradient(cx, cy,
                new int[]{0x00FFFFFF, 0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x1FFFFFFF, 0x00FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.08f, 0.16f, 0.5f, 0.58f, 0.66f, 1f}));
        c.drawCircle(cx, cy, rad * 0.98f, p);
        p.setShader(null);

        // label: album art when we have it, else a colored label with a marker
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
            p.setShader(new RadialGradient(cx, cy, lr, 0xFF00C2D6, 0xFF006F8A, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, lr, p);
            p.setShader(null);
            p.setColor(0x99FFFFFF);
            r.set(cx - lr * 0.55f, cy - lr * 0.62f, cx + lr * 0.55f, cy - lr * 0.5f);
            c.drawRoundRect(r, 2 * d, 2 * d, p);
            r.set(cx - lr * 0.38f, cy + lr * 0.45f, cx + lr * 0.38f, cy + lr * 0.55f);
            c.drawRoundRect(r, 2 * d, 2 * d, p);
        }
        c.restore();
        p.setShader(new RadialGradient(cx - 1 * d, cy - 1 * d, 4 * d, 0xFFFFFFFF, 0xFF7A7A84, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, 3.2f * d, p);          // spindle
        p.setShader(null);

        // ---- Tonearm ----
        float px = (w - pad) - 26 * d, py = cy - rad * 0.72f;
        // base
        p.setShader(new RadialGradient(px - 4 * d, py - 4 * d, 20 * d, 0xFFE4E4EA, 0xFF55555E, Shader.TileMode.CLAMP));
        c.drawCircle(px, py, 15 * d, p);
        p.setShader(null);
        p.setColor(0xFF1C1C21);
        c.drawCircle(px, py, 6 * d, p);
        // rest post
        float restX = px + 2 * d, restY = cy + rad * 0.75f;
        p.setColor(0xFF3A3A42);
        c.drawCircle(restX, restY, 5 * d, p);

        // the arm points from the pivot toward the outer grooves when playing
        float playTipX = cx + rad * 0.62f, playTipY = cy + rad * 0.35f;
        float baseAngle = (float) Math.toDegrees(Math.atan2(playTipY - py, playTipX - px));
        float restAngle = (float) Math.toDegrees(Math.atan2(restY - py, restX - px));
        float a = restAngle + (baseAngle - restAngle) * ease(arm);
        float len = (float) Math.hypot(playTipX - px, playTipY - py);
        c.save();
        c.rotate(a, px, py);
        // counterweight
        r.set(px - 24 * d, py - 7 * d, px - 10 * d, py + 7 * d);
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFFD8D8E0, 0xFF5E5E68, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 3 * d, 3 * d, p);
        // arm tube
        r.set(px, py - 2.2f * d, px + len - 12 * d, py + 2.2f * d);
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFFF0F0F6, 0xFF8A8A94, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 2 * d, 2 * d, p);
        // headshell
        r.set(px + len - 16 * d, py - 5.5f * d, px + len + 4 * d, py + 5.5f * d);
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFF4A4A54, 0xFF1A1A20, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 2.5f * d, 2.5f * d, p);
        p.setShader(null);
        p.setColor(0xFF00C2D6);                      // small accent on the headshell
        c.drawRect(px + len - 2 * d, py - 5.5f * d, px + len + 1 * d, py + 5.5f * d, p);
        c.restore();
        p.setShader(new RadialGradient(px - 2 * d, py - 2 * d, 8 * d, 0xFFFFFFFF, 0xFF777780, Shader.TileMode.CLAMP));
        c.drawCircle(px, py, 5 * d, p);              // pivot cap
        p.setShader(null);
    }

    private static float ease(float x) {
        return x * x * (3 - 2 * x);
    }
}
