package com.simple.carlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Thin sound-wave bars under the song (decorative: Bluetooth audio does not pass through the
 * launcher, so it cannot be measured). They dance while playing and lie flat when paused.
 */
final class WaveView extends View {

    private static final int BARS = 72;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] level = new float[BARS], target = new float[BARS];
    private final float d;
    private boolean playing;
    private float t;
    private ValueAnimator anim;

    WaveView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
        p.setStrokeCap(Paint.Cap.ROUND);
    }

    void setPlaying(boolean on) {
        if (on == playing) return;
        playing = on;
        start();
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
            t += dt;
            boolean moving = false;
            for (int i = 0; i < BARS; i++) {
                if (playing && Math.random() < dt * 9) {
                    // louder in the middle, like a song's spectrum seen from afar
                    float shape = (float) Math.exp(-Math.pow((i - BARS * 0.45) / (BARS * 0.28), 2));
                    target[i] = (float) (0.08 + Math.random() * 0.92 * (0.35 + 0.65 * shape));
                }
                if (!playing) target[i] = 0.04f;
                level[i] += (target[i] - level[i]) * Math.min(1f, dt * 14);
                if (Math.abs(level[i] - target[i]) > 0.005f) moving = true;
            }
            invalidate();
            if (!playing && !moving) a.cancel();
        });
        anim.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) anim.cancel();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), mid = h / 2f;
        float step = w / BARS;
        p.setStrokeWidth(Math.max(1.5f * d, step * 0.35f));
        for (int i = 0; i < BARS; i++) {
            float x = step * (i + 0.5f);
            float half = Math.max(1.2f * d, level[i] * mid * 0.95f);
            p.setColor(i % 9 == 4 ? 0xFF06233F : 0xE6FFFFFF);
            c.drawLine(x, mid - half, x, mid + half, p);
        }
    }
}
