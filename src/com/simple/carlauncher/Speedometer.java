package com.simple.carlauncher;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

/**
 * GPS speedometer: a 240° arc with the speed in km/h in the middle. Shows "--" until the GPS
 * has a fix, and again if no fix arrives for a few seconds (tunnel, parking garage).
 * The arc turns orange above {@link #WARN_KMH} as a gentle speed reminder.
 */
final class Speedometer extends View implements LocationListener {

    private static final int MAX_KMH = 160;
    private static final int WARN_KMH = 90;
    private static final long STALE_MS = 12000; // brief signal drops keep the last speed
    private static final float START = 150f, SWEEP = 240f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LocationManager lm;

    private float shown;          // animated value drawn on screen
    private boolean hasFix;
    private long lastFix;
    private ValueAnimator anim;
    private boolean running;
    private final GpsSpeed gps = new GpsSpeed();

    Speedometer(Context c) {
        super(c);
        lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        float d = getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setStrokeWidth(7 * d);
        track.setColor(0x26FFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setStrokeWidth(7 * d);
        tick.setColor(0x55FFFFFF);
        tick.setStrokeWidth(1.5f * d);
        number.setColor(Color.WHITE);
        number.setTypeface(Typeface.DEFAULT_BOLD);
        number.setTextAlign(Paint.Align.CENTER);
        unit.setColor(0xB3FFFFFF);
        unit.setTextAlign(Paint.Align.CENTER);
    }

    /** Starts listening to the GPS (call from onResume). */
    void start() {
        if (running) return;
        if (getContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
            running = true;
        } catch (Exception ignored) {
            return;
        }
        handler.post(staleCheck);
    }

    /** Stops the GPS updates (call from onPause). */
    void stop() {
        if (!running) return;
        running = false;
        lm.removeUpdates(this);
        handler.removeCallbacks(staleCheck);
    }

    private final Runnable staleCheck = new Runnable() {
        @Override
        public void run() {
            if (hasFix && SystemClock.elapsedRealtime() - lastFix > STALE_MS) {
                hasFix = false;
                animateTo(0);
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onLocationChanged(Location l) {
        float kmh = gps.update(l); // falls back to distance/time when the GPS gives no speed
        hasFix = true;
        lastFix = SystemClock.elapsedRealtime();
        animateTo(Math.min(kmh, 250));
    }

    private void animateTo(float target) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(shown, target);
        anim.setDuration(800);
        anim.addUpdateListener(a -> {
            shown = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    public void onStatusChanged(String p, int s, Bundle b) { }
    public void onProviderEnabled(String p) { }
    public void onProviderDisabled(String p) {
        hasFix = false;
        animateTo(0);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float stroke = arc.getStrokeWidth();
        // A 240° arc is about 1.25 times as wide as it is tall.
        float r = Math.min(w / 2f, h / 1.75f) - stroke;
        float cx = w / 2f, cy = h / 2f + r * 0.2f;
        box.set(cx - r, cy - r, cx + r, cy + r);

        c.drawArc(box, START, SWEEP, false, track);

        // Small ticks every 20 km/h just inside the arc.
        for (int v = 0; v <= MAX_KMH; v += 20) {
            double ang = Math.toRadians(START + SWEEP * v / MAX_KMH);
            float r1 = r - stroke * 1.4f, r2 = r - stroke * 2.2f;
            c.drawLine(cx + (float) Math.cos(ang) * r1, cy + (float) Math.sin(ang) * r1,
                    cx + (float) Math.cos(ang) * r2, cy + (float) Math.sin(ang) * r2, tick);
        }

        float frac = Math.min(shown, MAX_KMH) / MAX_KMH;
        if (frac > 0.003f) {
            boolean fast = shown > WARN_KMH;
            arc.setShader(new LinearGradient(cx - r, 0, cx + r, 0,
                    fast ? 0xFFFFB74D : 0xFF4FC3F7, fast ? 0xFFFF5722 : 0xFF9C6BFF, Shader.TileMode.CLAMP));
            c.drawArc(box, START, SWEEP * frac, false, arc);
        }

        number.setTextSize(r * 0.62f);
        unit.setTextSize(r * 0.22f);
        String txt = hasFix ? String.valueOf(Math.round(shown)) : "--";
        c.drawText(txt, cx, cy + number.getTextSize() * 0.32f, number);
        c.drawText("km/h", cx, cy + r * 0.62f, unit);
    }
}
