package com.simple.carlauncher;

import android.location.Location;

import java.util.ArrayDeque;

/**
 * Speed and heading from the head unit's GPS, filtered for weak signal. Cheap GPS modules
 * report positions that jump several metres when reception is poor, which reads as sudden
 * bursts of speed. This filter:
 *  - ignores fixes with poor accuracy,
 *  - works out the speed over a window of a few seconds instead of fix to fix,
 *  - limits how fast the speed may rise or fall (a car cannot gain 40 km/h in one second),
 *  - smooths the result,
 *  - and after a signal gap starts again instead of jumping.
 * Uses the speed reported by the module when it gives one, through the same limits.
 */
final class GpsSpeed {

    private static final float MAX_ACCURACY_M = 30f;   // worse fixes are ignored
    private static final long WINDOW_MS = 3000;        // distance measured over this span
    private static final long GAP_MS = 8000;           // longer without fixes = start again
    private static final float MAX_RISE_KMH_S = 12f;   // strong acceleration of a normal car
    private static final float MAX_FALL_KMH_S = 30f;   // hard braking

    private final ArrayDeque<Location> history = new ArrayDeque<>();
    private float kmh;
    private long lastTime;
    private float bearing = -1;
    private boolean weak;
    private Location lastGood;
    private int rejected; // consecutive fixes thrown away as jumps

    /** Filtered km/h after this fix. */
    float update(Location l) {
        long now = l.getTime();
        if (lastTime > 0 && now <= lastTime) return kmh; // duplicate fix
        weak = l.hasAccuracy() && l.getAccuracy() > MAX_ACCURACY_M;
        if (weak) return kmh; // keep the last good value while the signal is poor

        if (lastTime > 0 && now - lastTime > GAP_MS) {
            history.clear(); // signal came back: do not compare with an old position
            lastGood = null;
            kmh = 0;
        }
        float dtSec = lastTime > 0 ? Math.max(0.2f, (now - lastTime) / 1000f) : 1f;

        // Outlier: a position farther than the car could have gone at its current speed
        // (plus strong acceleration and the GPS error) is a jump. After 3 in a row, trust it.
        if (lastGood != null && !history.isEmpty()) {
            float err = Math.max(10f, ((l.hasAccuracy() ? l.getAccuracy() : 10f)
                    + (lastGood.hasAccuracy() ? lastGood.getAccuracy() : 10f)) * 0.8f);
            float reach = kmh / 3.6f * dtSec + 0.5f * 4f * dtSec * dtSec + err;
            if (lastGood.distanceTo(l) > reach && rejected < 2) {
                rejected++;
                return kmh;
            }
        }
        rejected = 0;
        lastGood = l;
        lastTime = now;

        history.addLast(l);
        while (history.size() > 2 && now - history.peekFirst().getTime() > WINDOW_MS + 1500) history.removeFirst();

        float raw;
        boolean reported = l.hasSpeed() && l.getSpeed() > 0.3f;
        Location first = history.peekFirst();
        float spanSec = (now - first.getTime()) / 1000f;
        float meters = first.distanceTo(l);
        float noise = Math.max(8f, (l.hasAccuracy() ? l.getAccuracy() : 10f)
                + (first.hasAccuracy() ? first.getAccuracy() : 10f)) * 0.6f;
        if (reported) {
            raw = l.getSpeed() * 3.6f;
            if (spanSec >= 2) {
                float byPosition = meters < noise ? 0 : meters / spanSec * 3.6f;
                // the module's speed must agree with how far the car really moved
                if (Math.abs(raw - byPosition) > 25 + byPosition * 0.3f) raw = byPosition;
                // a module that says "moving" while the position stays put is jitter
                if (meters < noise * 0.5f && raw < 15) raw = 0;
            }
        } else if (spanSec >= 1.5f) {
            raw = meters < noise ? 0 : meters / spanSec * 3.6f;
        } else {
            raw = kmh; // not enough history yet
        }
        if (raw > 220) raw = kmh; // impossible

        // limit how quickly the speed can change, then smooth it
        float maxUp = MAX_RISE_KMH_S * dtSec, maxDown = MAX_FALL_KMH_S * dtSec;
        float target = Math.max(kmh - maxDown, Math.min(kmh + maxUp, raw));
        kmh = kmh * 0.35f + target * 0.65f;
        if (kmh < 3) kmh = 0;

        if (kmh >= 5) {
            if (l.hasBearing() && reported) bearing = l.getBearing();
            else if (meters > noise) bearing = first.bearingTo(l);
        }
        return kmh;
    }

    /** Last known heading in degrees (0-359), or -1 if unknown. */
    int bearing() {
        return bearing < 0 ? -1 : Math.round((bearing + 360) % 360) % 360;
    }

    /** True while fixes arrive but are too inaccurate to use. */
    boolean weak() {
        return weak;
    }
}
