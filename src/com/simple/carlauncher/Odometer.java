package com.simple.carlauncher;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

/**
 * GPS odometer for the oil-change reminder (the car's CAN box does not report mileage).
 *
 * The user sets the dashboard reading once; from then on distance between good GPS fixes
 * is added. Fixes while parked or with poor accuracy are ignored so drift does not count.
 */
public class Odometer extends Service implements LocationListener {

    private static final String TAG = "Odometer";
    static final int DEFAULT_INTERVAL_KM = 5000;
    static final int WARN_KM = 500;

    private LocationManager lm;
    private Location last;
    private float pendingMeters;

    @Override
    public void onCreate() {
        super.onCreate();
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        // A fresh boot of the head unit (the car was off for a while) starts a new trip.
        if (android.os.SystemClock.elapsedRealtime() < 3 * 60_000) resetTrip(this);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 10, this);
            } catch (Exception e) {
                Log.w(TAG, "no GPS", e);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onLocationChanged(Location l) {
        if (!l.hasAccuracy() || l.getAccuracy() > 25) return;
        if (last != null) {
            float d = last.distanceTo(l);
            float secs = Math.max(1, (l.getTime() - last.getTime()) / 1000f);
            float kmh = d / secs * 3.6f;
            boolean moving = (l.hasSpeed() ? l.getSpeed() * 3.6f : kmh) > 4;
            // Count only real driving; drop parked drift and impossible jumps.
            if (moving && kmh < 200) {
                pendingMeters += d;
                addToTrip(d, Math.min(secs, 10), l.hasSpeed() ? l.getSpeed() * 3.6f : kmh);
            }
        }
        last = l;
        if (pendingMeters >= 100) { // persist every 100 m
            SharedPreferences p = prefs(this);
            p.edit().putFloat("gps_km", p.getFloat("gps_km", 0) + pendingMeters / 1000f).apply();
            pendingMeters = 0;
        }
    }

    public void onStatusChanged(String p, int s, Bundle b) { }
    public void onProviderEnabled(String p) { }
    public void onProviderDisabled(String p) { }

    @Override
    public void onDestroy() {
        super.onDestroy();
        lm.removeUpdates(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- Shared state ----------

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("odometer", Context.MODE_PRIVATE);
    }

    static boolean isConfigured(Context c) {
        return prefs(c).contains("base_km");
    }

    /** Estimated dashboard reading: the value the user entered plus GPS distance since. */
    static int currentKm(Context c) {
        SharedPreferences p = prefs(c);
        return Math.round(p.getInt("base_km", 0) + p.getFloat("gps_km", 0));
    }

    static void setCurrentKm(Context c, int km) {
        prefs(c).edit().putInt("base_km", km).putFloat("gps_km", 0).apply();
    }

    static int lastChangeKm(Context c) {
        return prefs(c).getInt("last_change_km", currentKm(c));
    }

    static void setLastChangeKm(Context c, int km) {
        prefs(c).edit().putInt("last_change_km", km).apply();
    }

    static int intervalKm(Context c) {
        return prefs(c).getInt("interval_km", DEFAULT_INTERVAL_KM);
    }

    static void setIntervalKm(Context c, int km) {
        prefs(c).edit().putInt("interval_km", km).apply();
    }

    /** Km left until the next oil change (negative when overdue). */
    static int remainingKm(Context c) {
        return lastChangeKm(c) + intervalKm(c) - currentKm(c);
    }

    // ---------- Current trip (speedometer screen) ----------

    private void addToTrip(float meters, float movingSecs, float kmh) {
        SharedPreferences p = prefs(this);
        p.edit()
                .putFloat("trip_m", p.getFloat("trip_m", 0) + meters)
                .putFloat("trip_moving_s", p.getFloat("trip_moving_s", 0) + movingSecs)
                .putFloat("trip_max", Math.max(p.getFloat("trip_max", 0), Math.min(kmh, 200)))
                .apply();
    }

    static void resetTrip(Context c) {
        prefs(c).edit()
                .putLong("trip_start", System.currentTimeMillis())
                .putFloat("trip_m", 0).putFloat("trip_moving_s", 0).putFloat("trip_max", 0)
                .apply();
    }

    static long tripStart(Context c) {
        return prefs(c).getLong("trip_start", System.currentTimeMillis());
    }

    static float tripKm(Context c) {
        return prefs(c).getFloat("trip_m", 0) / 1000f;
    }

    static float tripMaxKmh(Context c) {
        return prefs(c).getFloat("trip_max", 0);
    }

    /** Average while moving (stops at traffic lights do not drag it down). */
    static float tripAvgKmh(Context c) {
        float s = prefs(c).getFloat("trip_moving_s", 0);
        return s < 30 ? 0 : tripKm(c) / (s / 3600f);
    }
}
