package com.simple.carlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Locale;

/**
 * A compact cassette tape for the player. The white label shows the song title, the artist,
 * the file type and the elapsed / total time with a progress line. Through the window the two
 * reels turn while playing, and the tape moves from the left reel to the right one as the song
 * advances.
 */
final class CassetteView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final float d;

    private String title = "", artist = "", type = "";
    private int position, duration, total;
    private boolean playing;
    private float spin;      // reel rotation, degrees
    private ValueAnimator anim;

    CassetteView(Context c) {
        super(c);
        d = getResources().getDisplayMetrics().density;
    }

    void set(MusicService.State s) {
        title = s.hasSong ? s.title : "Sin canción";
        artist = s.hasSong ? s.artist : "Elige una carpeta para empezar";
        type = s.hasSong ? s.type : "";
        position = s.position;
        duration = s.duration;
        total = s.hasSong ? s.total : 0;
        if (s.playing != playing) {
            playing = s.playing;
            if (playing) start();
            else if (anim != null) anim.cancel();
        }
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
            spin = (spin + dt * 140f) % 360f;
            invalidate();
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
        float vw = getWidth(), vh = getHeight();
        // keep a cassette's proportions (about 1.7 : 1), centered
        float w = Math.min(vw, vh * 1.7f), h = w / 1.7f;
        float x0 = (vw - w) / 2f, y0 = (vh - h) / 2f;
        float u = w / 100f; // one unit = 1% of the width

        // ---- Shell ----
        r.set(x0, y0, x0 + w, y0 + h);
        p.setShader(new LinearGradient(0, y0, 0, y0 + h, 0xFF2A2A30, 0xFF121215, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 3 * u, 3 * u, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, 0.3f * u));
        p.setColor(0x33FFFFFF);
        c.drawRoundRect(r, 3 * u, 3 * u, p);
        p.setStyle(Paint.Style.FILL);
        // fine ribs on the shell
        p.setColor(0x0DFFFFFF);
        for (float x = x0 + 2 * u; x < x0 + w - 2 * u; x += 1.2f * u) c.drawRect(x, y0 + 2 * u, x + 0.3f * u, y0 + h - 2 * u, p);
        // corner screws
        for (float[] s : new float[][]{{3.5f, 4.5f}, {96.5f, 4.5f}, {3.5f, 54.3f}, {96.5f, 54.3f}, {50f, 54.3f}}) {
            float sx = x0 + s[0] * u, sy = y0 + s[1] * u;
            p.setShader(new RadialGradient(sx - 0.4f * u, sy - 0.4f * u, 1.6f * u, 0xFF8A8A94, 0xFF2A2A30, Shader.TileMode.CLAMP));
            c.drawCircle(sx, sy, 1.3f * u, p);
            p.setShader(null);
            p.setColor(0xFF15151A);
            c.drawRect(sx - 0.9f * u, sy - 0.15f * u, sx + 0.9f * u, sy + 0.15f * u, p);
        }

        // ---- Label ----
        float lx0 = x0 + 7 * u, lx1 = x0 + w - 7 * u, ly0 = y0 + 4 * u, ly1 = y0 + 44 * u;
        r.set(lx0, ly0, lx1, ly1);
        p.setColor(0xFFF4F1EA);
        c.drawRoundRect(r, 1.5f * u, 1.5f * u, p);
        // side "A" badge and top accent stripe
        p.setColor(0xFFE53935);
        c.drawRect(lx0, ly0 + 2 * u, lx0 + 9 * u, ly0 + 11 * u, p);
        tp.setColor(0xFFFFFFFF);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextSize(7 * u);
        tp.setTextAlign(Paint.Align.CENTER);
        c.drawText("A", lx0 + 4.5f * u, ly0 + 9.5f * u, tp);

        tp.setTextAlign(Paint.Align.LEFT);
        float tx = lx0 + 12 * u, tw = lx1 - tx - 3 * u;
        // title (bold) and artist, cut with "…" when too long
        tp.setColor(0xFF1C1C22);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextSize(4.8f * u);
        c.drawText(TextUtils.ellipsize(title, tp, tw, TextUtils.TruncateAt.END).toString(), tx, ly0 + 6.5f * u, tp);
        tp.setTypeface(Typeface.DEFAULT);
        tp.setTextSize(3.6f * u);
        tp.setColor(0xFF55555E);
        c.drawText(TextUtils.ellipsize(artist, tp, tw, TextUtils.TruncateAt.END).toString(), tx, ly0 + 11f * u, tp);
        // ruled writing lines like a real label
        p.setColor(0x22000000);
        c.drawRect(lx0 + 2 * u, ly0 + 13f * u, lx1 - 2 * u, ly0 + 13f * u + Math.max(1f, 0.2f * u), p);

        // file type chip, time, and progress line
        float rowY = ly0 + 17.5f * u;
        if (!type.isEmpty()) {
            tp.setTextSize(3.2f * u);
            tp.setTypeface(Typeface.DEFAULT_BOLD);
            float chipW = tp.measureText(type) + 3 * u;
            r.set(lx0 + 2 * u, rowY - 3.4f * u, lx0 + 2 * u + chipW, rowY + 1.2f * u);
            p.setColor(0xFF1C1C22);
            c.drawRoundRect(r, 1.2f * u, 1.2f * u, p);
            tp.setColor(0xFF00E5FF);
            c.drawText(type, lx0 + 3.5f * u, rowY, tp);
        }
        tp.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        tp.setTextSize(3.6f * u);
        tp.setColor(0xFF1C1C22);
        tp.setTextAlign(Paint.Align.RIGHT);
        String time = duration > 0 ? fmt(position) + " / " + fmt(duration) : fmt(position);
        c.drawText(time, lx1 - 2 * u, rowY, tp);
        tp.setTextAlign(Paint.Align.LEFT);

        float barY = ly0 + 20.3f * u, bx0 = lx0 + 2 * u, bx1 = lx1 - 2 * u;
        r.set(bx0, barY, bx1, barY + 1.1f * u);
        p.setColor(0x22000000);
        c.drawRoundRect(r, u, u, p);
        float frac = duration > 0 ? Math.min(1f, position / (float) duration) : 0f;
        if (frac > 0) {
            r.set(bx0, barY, bx0 + (bx1 - bx0) * frac, barY + 1.1f * u);
            p.setShader(new LinearGradient(bx0, 0, bx1, 0, 0xFF0091EA, 0xFFE53935, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, u, u, p);
            p.setShader(null);
        }

        // ---- Window with the two reels ----
        float wy0 = ly0 + 23.5f * u, wy1 = ly1 - 2 * u;
        float wx0 = x0 + 22 * u, wx1 = x0 + w - 22 * u;
        r.set(wx0, wy0, wx1, wy1);
        p.setColor(0xFF0A0A0C);
        c.drawRoundRect(r, 4 * u, 4 * u, p);
        float cy = (wy0 + wy1) / 2f;
        float lcx = x0 + 31 * u, rcx = x0 + w - 31 * u;
        float hub = 3.4f * u;
        float maxTape = (wy1 - wy0) / 2f + 2.5f * u; // tape pack may look bigger than the window
        // tape packs: left full at the start, right full at the end
        float leftR = hub + (maxTape - hub) * (1f - frac) + 0.6f * u;
        float rightR = hub + (maxTape - hub) * frac + 0.6f * u;
        c.save();
        c.clipRect(wx0, wy0, wx1, wy1);
        p.setColor(0xFF3B2A22);
        c.drawCircle(lcx, cy, leftR, p);
        c.drawCircle(rcx, cy, rightR, p);
        // the tape running between the packs, along the bottom
        p.setColor(0xFF2A1D18);
        c.drawRect(lcx, wy1 - 1.4f * u, rcx, wy1 - 0.6f * u, p);
        c.restore();
        // window glass shine
        p.setShader(new LinearGradient(wx0, wy0, wx0, wy1, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        r.set(wx0, wy0, wx1, wy1);
        c.drawRoundRect(r, 4 * u, 4 * u, p);
        p.setShader(null);
        drawReel(c, lcx, cy, hub, spin);
        drawReel(c, rcx, cy, hub, spin + 30);

        // ---- Bottom head area (trapezoid with guide holes) ----
        path.reset();
        path.moveTo(x0 + 18 * u, y0 + h);
        path.lineTo(x0 + 22 * u, y0 + 47 * u);
        path.lineTo(x0 + w - 22 * u, y0 + 47 * u);
        path.lineTo(x0 + w - 18 * u, y0 + h);
        path.close();
        p.setColor(0xFF1A1A1F);
        c.drawPath(path, p);
        p.setColor(0xFF050506);
        for (float hx : new float[]{30, 42, 58, 70}) {
            c.drawCircle(x0 + hx * u, y0 + 53 * u, 1.6f * u, p);
        }
        r.set(x0 + 46 * u, y0 + 50.5f * u, x0 + 54 * u, y0 + 55.5f * u);
        c.drawRoundRect(r, 0.8f * u, 0.8f * u, p);
        // small brand-free model mark
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextSize(3.2f * u);
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setColor(0xFFE53935);
        c.drawText("Hi-Fi STEREO", x0 + 6 * u, y0 + 50 * u, tp);
        tp.setTextAlign(Paint.Align.RIGHT);
        tp.setColor(0x99FFFFFF);
        // play time of the whole folder, like the minutes printed on a real tape
        if (total > 0) c.drawText("TOTAL " + fmt(total), x0 + w - 6 * u, y0 + 50 * u, tp);
        tp.setTextAlign(Paint.Align.LEFT);
    }

    /** White toothed hub of a reel, rotated by {@code deg}. */
    private void drawReel(Canvas c, float cx, float cy, float rad, float deg) {
        p.setColor(0xFFE8E8EE);
        c.drawCircle(cx, cy, rad, p);
        p.setColor(0xFF0A0A0C);
        c.drawCircle(cx, cy, rad * 0.55f, p);
        c.save();
        c.rotate(deg, cx, cy);
        p.setColor(0xFFE8E8EE);
        for (int i = 0; i < 6; i++) {
            c.save();
            c.rotate(i * 60, cx, cy);
            c.drawRect(cx - rad * 0.1f, cy - rad * 0.58f, cx + rad * 0.1f, cy - rad * 0.32f, p);
            c.restore();
        }
        c.restore();
    }

    static String fmt(int ms) {
        int s = Math.max(0, ms / 1000);
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
