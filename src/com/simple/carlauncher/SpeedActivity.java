package com.simple.carlauncher;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.Locale;

/**
 * Full-screen GPS speedometer: a big analog gauge (or a giant digital readout) in the middle,
 * the current trip on the left (time, distance, max and average speed) and GPS details on the
 * right (heading, altitude, satellites, accuracy). The trip itself is recorded by the
 * {@link Odometer} service, so it keeps counting while this screen is closed.
 */
public class SpeedActivity extends Activity implements LocationListener {

    private static final long STALE_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager lm;
    private GnssStatus.Callback gnss;

    private Gauge gauge;
    private TextView tripTime, tripDist, tripMax, tripAvg;
    private TextView heading, altitude, sats, accuracy, gpsChip;
    private TextView modeAnalog, modeDigital;
    private android.widget.ImageView car, carLights;
    private boolean hasLightsImage, lightsShown, lastPolled;

    private boolean hasFix;
    private long lastFix;
    private int satsUsed, satsSeen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(14));
        root.setBackgroundColor(Color.BLACK); // dashboard look: pure black

        // ---- Top bar: back, title, GPS chip, clock ----
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(Ui.iconButton(this, R.drawable.ic_back, 48, false, this::finish),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = Ui.text(this, "Velocímetro", 24, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setPadding(dp(16), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        gpsChip = Ui.text(this, "Buscando GPS…", 13, Color.WHITE);
        gpsChip.setPadding(dp(14), dp(6), dp(14), dp(6));
        bar.addView(gpsChip);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("hh:mm");
        clock.setTextSize(24);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Typeface.DEFAULT_BOLD);
        clock.setPadding(dp(18), 0, dp(4), 0);
        bar.addView(clock);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(52)));

        // ---- Body: trip | gauge | GPS ----
        LinearLayout body = new LinearLayout(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1f);
        blp.topMargin = dp(8);
        root.addView(body, blp);

        LinearLayout left = column();
        tripTime = stat(left, "TIEMPO DE VIAJE", true);
        tripDist = stat(left, "DISTANCIA", false);
        tripMax = stat(left, "VELOCIDAD MÁXIMA", false);
        tripAvg = stat(left, "PROMEDIO EN MOVIMIENTO", false);
        TextView reset = Ui.text(this, "Reiniciar viaje", 14, Color.WHITE);
        reset.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable rb = new GradientDrawable();
        rb.setCornerRadius(dp(14));
        rb.setColor(0x26FFFFFF);
        reset.setBackground(rb);
        Ui.pressable(reset, 0.94f);
        reset.setOnClickListener(v -> {
            Odometer.resetTrip(this);
            refreshTrip();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(6);
        left.addView(reset, rlp);
        body.addView(left, new LinearLayout.LayoutParams(0, -1, 0.95f));

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        gauge = new Gauge(this);
        gauge.setDigital(prefs().getBoolean("digital", false));
        gauge.setOnClickListener(v -> setDigital(!gauge.digital));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 1f);
        glp.bottomMargin = dp(8);
        center.addView(gauge, glp);
        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER);
        modeAnalog = modeChip("Analógico", false);
        modeDigital = modeChip("Digital", true);
        modes.addView(modeAnalog);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
        mlp.leftMargin = dp(8);
        modes.addView(modeDigital, mlp);
        center.addView(modes, new LinearLayout.LayoutParams(-1, dp(40)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -1, 1.9f);
        clp.leftMargin = dp(12);
        clp.rightMargin = dp(12);
        body.addView(center, clp);

        // Right: the user's car on top, GPS details in a 2x2 grid below.
        LinearLayout right = column();
        right.addView(buildCar(), new LinearLayout.LayoutParams(-1, 0, 1.15f));
        LinearLayout row1 = gridRow(right, true), row2 = gridRow(right, false);
        heading = stat(row1, "RUMBO", true);
        altitude = stat(row1, "ALTITUD", true);
        sats = stat(row2, "SATÉLITES", true);
        accuracy = stat(row2, "PRECISIÓN", true);
        body.addView(right, new LinearLayout.LayoutParams(0, -1, 0.95f));

        setContentView(root);
        updateModeChips();
        showNoFix();
    }

    // ---------- Building blocks ----------

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF111116, 0xFF0A0A0D});
        bg.setCornerRadius(dp(18));
        bg.setStroke(1, 0x1AFFFFFF);
        col.setBackground(bg);
        return col;
    }

    private LinearLayout gridRow(LinearLayout col, boolean first) {
        View line = new View(this);
        line.setBackgroundColor(0x1AFFFFFF);
        col.addView(line, new LinearLayout.LayoutParams(-1, 1));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
        return row;
    }

    /** Own photo if picked, else the bundled car (headlights version when the lights are on). */
    /**
     * The car photo, mirrored so it faces the gauge. With the bundled pictures, the
     * headlights version fades in while the car's lights are on (ILL wire), live.
     */
    private View buildCar() {
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        car = new android.widget.ImageView(this);
        carLights = new android.widget.ImageView(this);
        for (android.widget.ImageView v : new android.widget.ImageView[]{car, carLights}) {
            v.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            v.setScaleX(-1f);
            box.addView(v, new android.widget.FrameLayout.LayoutParams(-1, -1));
        }
        carLights.setAlpha(0f);
        android.graphics.Bitmap own = Profile.carPhoto(this);
        int off = Profile.bundledCar(this, false), on = Profile.bundledCar(this, true);
        if (own != null) {
            car.setImageBitmap(own);
        } else if (off != 0) {
            car.setImageResource(off);
            if (on != 0) {
                carLights.setImageResource(on);
                hasLightsImage = true;
            }
        } else {
            car.setImageResource(R.drawable.ic_car);
            car.setColorFilter(0x40FFFFFF);
        }
        return box;
    }

    private boolean carLightsOn() {
        int ill = android.provider.Settings.System.getInt(getContentResolver(), "mcu_no_source_device_state", 0);
        return (ill & 2) != 0;
    }

    private void showLights(boolean on, boolean animate) {
        if (!hasLightsImage || (on == lightsShown && animate)) return;
        lightsShown = on;
        long ms = animate ? 600 : 0;
        carLights.animate().alpha(on ? 1f : 0f).setDuration(ms).start();
        car.animate().alpha(on ? 0f : 1f).setDuration(ms).start();
    }

    private final android.content.BroadcastReceiver illReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, android.content.Intent i) {
            showLights(i.getByteExtra("extra_ill_state", (byte) 0) != 0, true);
        }
    };

    /** Label + value pair; returns the value view. In a row, pairs share the width. */
    private TextView stat(LinearLayout col, String label, boolean first) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = Ui.text(this, label, 11, 0x99FFFFFF);
        l.setLetterSpacing(0.12f);
        l.setGravity(Gravity.START);
        box.addView(l);
        TextView v = Ui.text(this, "--", 26, Color.WHITE);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.START);
        box.addView(v);
        boolean inRow = col.getOrientation() == LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams lp = inRow
                ? new LinearLayout.LayoutParams(0, -1, 1f)
                : new LinearLayout.LayoutParams(-1, 0, 1f);
        if (inRow) v.setTextSize(20);
        if (!first) {
            View line = new View(this);
            line.setBackgroundColor(0x1AFFFFFF);
            col.addView(line, new LinearLayout.LayoutParams(-1, 1));
        }
        col.addView(box, lp);
        return v;
    }

    private TextView modeChip(String text, boolean digital) {
        TextView t = Ui.text(this, text, 14, Color.WHITE);
        t.setPadding(dp(18), dp(7), dp(18), dp(7));
        Ui.pressable(t, 0.94f);
        t.setOnClickListener(v -> setDigital(digital));
        return t;
    }

    private void setDigital(boolean digital) {
        gauge.setDigital(digital);
        prefs().edit().putBoolean("digital", digital).apply();
        updateModeChips();
    }

    private void updateModeChips() {
        styleChip(modeAnalog, !gauge.digital);
        styleChip(modeDigital, gauge.digital);
    }

    private void styleChip(TextView t, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(on ? 0xFF00ACC1 : 0x1AFFFFFF);
        t.setBackground(bg);
        t.setAlpha(on ? 1f : 0.7f);
    }

    // ---------- GPS ----------

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
                gnss = new GnssStatus.Callback() {
                    @Override
                    public void onSatelliteStatusChanged(GnssStatus status) {
                        int used = 0, n = status.getSatelliteCount();
                        for (int i = 0; i < n; i++) if (status.usedInFix(i)) used++;
                        satsUsed = used;
                        satsSeen = n;
                    }
                };
                lm.registerGnssStatusCallback(gnss);
            } catch (Exception ignored) { }
        } else {
            gpsChip.setText("Sin permiso de ubicación");
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
        registerReceiver(illReceiver, new android.content.IntentFilter("com.nwd.ACTION_ILL_STATE_CHANGE"));
        lastPolled = carLightsOn();
        lightsShown = !lastPolled;
        showLights(lastPolled, false);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        lm.removeUpdates(this);
        if (gnss != null) lm.unregisterGnssStatusCallback(gnss);
        handler.removeCallbacks(tick);
        unregisterReceiver(illReceiver);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) recreate();
    }

    @Override
    public void onLocationChanged(Location l) {
        hasFix = true;
        lastFix = SystemClock.elapsedRealtime();
        float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0;
        if (kmh < 3) kmh = 0;
        gauge.setSpeed(Math.min(kmh, 250));

        if (kmh >= 5 && l.hasBearing()) {
            int b = Math.round(l.getBearing()) % 360;
            heading.setText(cardinal(b) + "  " + b + "°");
        } else if (kmh < 5) {
            heading.setText("Detenido");
        }
        altitude.setText(l.hasAltitude() ? Math.round(l.getAltitude()) + " m" : "--");
        accuracy.setText(l.hasAccuracy() ? "± " + Math.round(l.getAccuracy()) + " m" : "--");
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(14));
        boolean good = l.hasAccuracy() && l.getAccuracy() <= 15;
        chip.setColor(good ? 0x552ECC71 : 0x55FF9800);
        gpsChip.setBackground(chip);
        gpsChip.setText(good ? "GPS: buena señal" : "GPS: señal débil");
    }

    public void onStatusChanged(String p, int s, Bundle b) { }
    public void onProviderEnabled(String p) { }
    public void onProviderDisabled(String p) {
        hasFix = false;
        showNoFix();
    }

    private void showNoFix() {
        gauge.setNoFix();
        heading.setText("--");
        altitude.setText("--");
        accuracy.setText("--");
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(14));
        chip.setColor(0x33FFFFFF);
        gpsChip.setBackground(chip);
        gpsChip.setText("Buscando GPS…");
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (hasFix && SystemClock.elapsedRealtime() - lastFix > STALE_MS) {
                hasFix = false;
                showNoFix();
            }
            sats.setText(satsSeen == 0 ? "--" : satsUsed + " / " + satsSeen);
            // Backup for a missed broadcast: react only when the stored light state itself changes,
            // so a broadcast that arrives before the kernel stores the state is not undone.
            boolean polled = carLightsOn();
            if (polled != lastPolled) {
                lastPolled = polled;
                showLights(polled, true);
            }
            gauge.setOdometer(Odometer.isConfigured(SpeedActivity.this) ? Odometer.currentKm(SpeedActivity.this) : -1);
            refreshTrip();
            handler.postDelayed(this, 1000);
        }
    };

    private void refreshTrip() {
        long secs = Math.max(0, (System.currentTimeMillis() - Odometer.tripStart(this)) / 1000);
        tripTime.setText(String.format(Locale.US, "%d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60));
        float km = Odometer.tripKm(this);
        tripDist.setText(km < 10 ? String.format(Locale.US, "%.1f km", km) : Math.round(km) + " km");
        tripMax.setText(Math.round(Odometer.tripMaxKmh(this)) + " km/h");
        float avg = Odometer.tripAvgKmh(this);
        tripAvg.setText(avg <= 0 ? "--" : Math.round(avg) + " km/h");
    }

    private static String cardinal(int deg) {
        String[] names = {"N", "NE", "E", "SE", "S", "SO", "O", "NO"};
        return names[Math.round(deg / 45f) % 8];
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("speed", MODE_PRIVATE);
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    // ---------- Gauge ----------

    /**
     * Analog gauge 0-180 km/h over a 270° arc with a glowing needle and the digital speed in
     * the middle, or (digital mode) one giant number with a thin progress bar.
     */
    static final class Gauge extends View {

        private static final int MAX = 180, WARN = 90;
        private static final float START = 135f, SWEEP = 270f;

        private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint progress = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tickMajor = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tickMinor = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hub = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint odoBox = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint segOn = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint segGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint segOff = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint segClear = new Paint();
        private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private android.graphics.Bitmap logo; // brand badge at the top of the dial (optional)
        private final RectF logoBox = new RectF();
        private final RectF box = new RectF();
        private final Path path = new Path();
        private final float d;

        boolean digital;
        private boolean fix;
        private float shown;
        private int odo = -1;
        private ValueAnimator anim;

        Gauge(Context c) {
            super(c);
            d = getResources().getDisplayMetrics().density;
            setLayerType(LAYER_TYPE_SOFTWARE, null); // needed for the blur glow
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeCap(Paint.Cap.ROUND);
            track.setColor(0x1FFFFFFF);
            progress.setStyle(Paint.Style.STROKE);
            progress.setStrokeCap(Paint.Cap.ROUND);
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeCap(Paint.Cap.ROUND);
            glow.setMaskFilter(new BlurMaskFilter(14 * d, BlurMaskFilter.Blur.NORMAL));
            tickMajor.setColor(0xCCFFFFFF);
            tickMajor.setStrokeCap(Paint.Cap.ROUND);
            tickMinor.setColor(0x55FFFFFF);
            label.setColor(0xB3FFFFFF);
            label.setTextAlign(Paint.Align.CENTER);
            needle.setStyle(Paint.Style.FILL);
            hub.setStyle(Paint.Style.FILL);
            big.setColor(Color.WHITE);
            big.setTypeface(Typeface.DEFAULT_BOLD);
            big.setTextAlign(Paint.Align.CENTER);
            small.setColor(0x99FFFFFF);
            small.setTextAlign(Paint.Align.CENTER);
            small.setLetterSpacing(0.15f);
            odoBox.setStyle(Paint.Style.FILL);
            odoBox.setColor(0x33000000);
            segOff.setColor(0x1FFFFFFF);  // unlit segments, faintly visible
            segClear.setColor(0x00000000);
            int logoId = getResources().getIdentifier("brand_logo", "drawable", c.getPackageName());
            if (logoId != 0) logo = android.graphics.BitmapFactory.decodeResource(getResources(), logoId);
            logoPaint.setAlpha(235);
        }

        void setDigital(boolean on) {
            digital = on;
            invalidate();
        }

        void setOdometer(int km) {
            if (km != odo) {
                odo = km;
                invalidate();
            }
        }

        void setNoFix() {
            fix = false;
            animateTo(0);
        }

        void setSpeed(float kmh) {
            fix = true;
            animateTo(kmh);
        }

        private void animateTo(float target) {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(shown, target);
            anim.setDuration(900);
            anim.addUpdateListener(a -> {
                shown = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        private int accent(boolean end) {
            boolean fast = shown > WARN;
            if (fast) return end ? 0xFFFF3D6E : 0xFFFFA14D;
            return end ? 0xFF00F0FF : 0xFF0091EA;
        }

        /** Lit LCD segments in the accent color (cyan, red over the reminder) with a soft glow. */
        private void lcd(Canvas c, String text, float x, float y, float h) {
            segOn.setColor(accent(true));
            segGlow.setColor(accent(true));
            segGlow.setAlpha(150);
            segGlow.setMaskFilter(new BlurMaskFilter(Math.max(1f, h * 0.06f), BlurMaskFilter.Blur.NORMAL));
            SevenSeg.draw(c, text, x, y, h, segGlow, segOff);
            SevenSeg.draw(c, text, x, y, h, segOn, segClear);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (digital) drawDigital(c);
            else drawAnalog(c);
        }

        private void drawAnalog(Canvas c) {
            float w = getWidth(), h = getHeight();
            // Centered and kept inside the view so it never runs into the mode buttons below.
            float r = Math.min(w, h) / 2f - 6 * d;
            float cx = w / 2f, cy = h / 2f;
            float stroke = r * 0.075f;

            // Dark round face with a soft inner light.
            face.setShader(new RadialGradient(cx, cy - r * 0.3f, r * 1.2f,
                    new int[]{0xFF232A4A, 0xFF101225, 0xFF07070D}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, face);

            float ar = r - stroke * 1.2f;
            box.set(cx - ar, cy - ar, cx + ar, cy + ar);
            track.setStrokeWidth(stroke);
            c.drawArc(box, START, SWEEP, false, track);

            // Red band over the speed reminder.
            Paint warnBand = new Paint(track);
            warnBand.setColor(0x33FF3D6E);
            c.drawArc(box, START + SWEEP * WARN / MAX, SWEEP * (MAX - WARN) / MAX, false, warnBand);

            float frac = Math.min(shown, MAX) / MAX;
            if (frac > 0.002f) {
                SweepGradient sg = new SweepGradient(cx, cy, new int[]{accent(false), accent(true), accent(false)},
                        new float[]{0f, 0.75f, 1f});
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setRotate(START, cx, cy);
                sg.setLocalMatrix(m);
                glow.setShader(sg);
                glow.setStrokeWidth(stroke * 1.3f);
                glow.setAlpha(150);
                c.drawArc(box, START, SWEEP * frac, false, glow);
                progress.setShader(sg);
                progress.setStrokeWidth(stroke);
                c.drawArc(box, START, SWEEP * frac, false, progress);
            }

            // Ticks every 5 km/h, labels every 20.
            label.setTextSize(r * 0.085f);
            tickMajor.setStrokeWidth(3 * d);
            tickMinor.setStrokeWidth(1.5f * d);
            for (int v = 0; v <= MAX; v += 5) {
                double a = Math.toRadians(START + SWEEP * v / MAX);
                boolean major = v % 20 == 0;
                boolean mid = v % 10 == 0;
                float r1 = ar - stroke * 0.9f;
                float r2 = r1 - (major ? r * 0.08f : mid ? r * 0.05f : r * 0.03f);
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                c.drawLine(cx + cos * r1, cy + sin * r1, cx + cos * r2, cy + sin * r2, major ? tickMajor : tickMinor);
                if (major) {
                    float rl = r2 - r * 0.09f;
                    c.drawText(String.valueOf(v), cx + cos * rl, cy + sin * rl + label.getTextSize() * 0.35f, label);
                }
            }

            // Brand badge in the upper part of the dial, between the numbers and the hub.
            drawLogo(c, cx, cy - r * 0.5f, r * 0.22f);

            // Needle: a slim tapered blade with a glow, pointing at the current speed.
            float ang = START + SWEEP * frac;
            c.save();
            c.rotate(ang, cx, cy);
            float len = ar - stroke * 1.1f;
            path.reset();
            path.moveTo(cx - r * 0.12f, cy - 2.5f * d);
            path.lineTo(cx + len, cy - 0.8f * d);
            path.lineTo(cx + len, cy + 0.8f * d);
            path.lineTo(cx - r * 0.12f, cy + 2.5f * d);
            path.close();
            needle.setShader(new LinearGradient(cx, cy, cx + len, cy, 0x00FFFFFF, accent(true), Shader.TileMode.CLAMP));
            needle.setMaskFilter(new BlurMaskFilter(6 * d, BlurMaskFilter.Blur.NORMAL));
            c.drawPath(path, needle);
            needle.setMaskFilter(null);
            needle.setShader(new LinearGradient(cx, cy, cx + len, cy, 0xFFFFFFFF, accent(true), Shader.TileMode.CLAMP));
            c.drawPath(path, needle);
            c.restore();

            // Hub with the digital speed.
            float hr = r * 0.36f;
            hub.setShader(new RadialGradient(cx, cy - hr * 0.4f, hr * 1.3f, 0xFF262B4D, 0xFF0B0C16, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, hr, hub);
            Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2 * d);
            ring.setColor(accent(false) & 0x88FFFFFF);
            c.drawCircle(cx, cy, hr, ring);

            float dh = hr * 0.72f;
            lcd(c, SevenSeg.speedText(fix, shown), cx - SevenSeg.width(3, dh) / 2f, cy - dh * 0.62f, dh);
            small.setTextSize(r * 0.06f);
            c.drawText("KM/H", cx, cy + hr * 0.66f, small);

            // Odometer window at the bottom of the face.
            if (odo >= 0) {
                String t = String.format(Locale.US, "%06d", odo);
                label.setTextSize(r * 0.08f);
                float tw = label.measureText(t + " km") + r * 0.08f;
                float oy = cy + r * 0.62f;
                box.set(cx - tw / 2, oy - r * 0.075f, cx + tw / 2, oy + r * 0.075f);
                c.drawRoundRect(box, 6 * d, 6 * d, odoBox);
                label.setColor(0xE6FFFFFF);
                c.drawText(t + " km", cx, oy + label.getTextSize() * 0.35f, label);
                label.setColor(0xB3FFFFFF);
            }
        }

        /** Draws the brand badge centered at (cx, cy) with the given height. */
        private void drawLogo(Canvas c, float cx, float cy, float h) {
            if (logo == null) return;
            float w = h * logo.getWidth() / logo.getHeight();
            logoBox.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
            c.drawBitmap(logo, null, logoBox, logoPaint);
        }

        private void drawDigital(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f;
            drawLogo(c, cx, h * 0.065f, h * 0.12f);

            // Three big LCD digits; leading positions stay as faint "8"s, like a real dashboard.
            float dh = Math.min(h * 0.56f, w * 0.92f / (SevenSeg.width(3, 1f)));
            float dx = cx - SevenSeg.width(3, dh) / 2f, dy = h * 0.14f;
            lcd(c, SevenSeg.speedText(fix, shown), dx, dy, dh);
            Paint unitP = new Paint(Paint.ANTI_ALIAS_FLAG);
            unitP.setColor(accent(true));
            unitP.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            unitP.setTextAlign(Paint.Align.RIGHT);
            unitP.setTextSize(h * 0.05f);
            unitP.setLetterSpacing(0.2f);
            c.drawText("KM/H", dx + SevenSeg.width(3, dh), dy - h * 0.025f, unitP);

            // Progress bar 0-180 with the reminder mark at 90.
            float bw = w * 0.8f, bx = cx - bw / 2, by = h * 0.82f, bh = 10 * d;
            box.set(bx, by, bx + bw, by + bh);
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(0x1FFFFFFF);
            c.drawRoundRect(box, bh, bh, t);
            float frac = Math.min(shown, MAX) / MAX;
            if (frac > 0.002f) {
                box.set(bx, by, bx + bw * frac, by + bh);
                t.setShader(new LinearGradient(bx, 0, bx + bw, 0, accent(false), accent(true), Shader.TileMode.CLAMP));
                c.drawRoundRect(box, bh, bh, t);
            }
            Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
            mark.setColor(0x99FF3D6E);
            mark.setStrokeWidth(2 * d);
            float mx = bx + bw * WARN / MAX;
            c.drawLine(mx, by - 6 * d, mx, by + bh + 6 * d, mark);
            label.setTextSize(h * 0.045f);
            c.drawText("0", bx, by + bh + h * 0.07f, label);
            c.drawText(String.valueOf(WARN), mx, by + bh + h * 0.07f, label);
            c.drawText(String.valueOf(MAX), bx + bw, by + bh + h * 0.07f, label);

            if (odo >= 0) {
                label.setTextSize(h * 0.045f);
                label.setTextAlign(Paint.Align.LEFT);
                c.drawText(String.format(Locale.US, "ODO %06d km", odo), w * 0.1f, h * 0.075f, label);
                label.setTextAlign(Paint.Align.CENTER);
            }
        }
    }
}
