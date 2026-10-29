package com.simple.carlauncher;

import android.location.Location;

/**
 * Speed and heading from GPS fixes. Many head-unit GPS modules report positions but no speed
 * (or always 0); then the speed is worked out from the distance and time between fixes.
 */
final class GpsSpeed {

    private Location prev;
    private float lastKmh;
    private float bearing = -1;

    /** Km/h for this fix (0 when standing still or the movement is just GPS jitter). */
    float update(Location l) {
        float kmh;
        boolean reported = l.hasSpeed() && l.getSpeed() > 0;
        if (reported) {
            kmh = l.getSpeed() * 3.6f;
        } else if (prev != null) {
            float secs = (l.getTime() - prev.getTime()) / 1000f;
            if (secs <= 0 || secs > 10) {
                kmh = lastKmh;                 // duplicate or stale fix: keep the last value
            } else {
                float meters = prev.distanceTo(l);
                float noise = Math.max(5f, l.hasAccuracy() ? l.getAccuracy() * 0.5f : 5f);
                kmh = meters < noise ? 0 : meters / secs * 3.6f;
                kmh = lastKmh * 0.4f + kmh * 0.6f; // smooth the jumps between fixes
            }
        } else {
            kmh = 0;
        }
        if (prev != null && prev.distanceTo(l) > 8) bearing = prev.bearingTo(l);
        if (l.hasBearing() && reported) bearing = l.getBearing();
        if (kmh < 3) kmh = 0;
        if (kmh > 250) kmh = lastKmh;          // impossible jump
        lastKmh = kmh;
        if (prev == null || l.getTime() != prev.getTime()) prev = l;
        return kmh;
    }

    /** Last known heading in degrees (0-359), or -1 if unknown. */
    int bearing() {
        return bearing < 0 ? -1 : Math.round((bearing + 360) % 360) % 360;
    }
}
