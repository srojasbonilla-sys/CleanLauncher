package com.simple.carlauncher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * Bluetooth music screen: the phone's song on a big spinning record with a tonearm, warm copper
 * background, sound waves and the transport buttons. The audio itself is received by the
 * radio's factory Bluetooth app; this screen shows it and sends play / pause / next / previous.
 */
public class BtPlayerActivity extends Activity implements NowPlaying.Listener {

    private NowPlaying nowPlaying;
    private RecordView record;
    private WaveView wave;
    private TextView title, artist, status;
    private KnobButton playBtn;
    private String lastTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        nowPlaying = NowPlaying.get(this);
        // our own player gives the speakers and the media buttons to Bluetooth
        if (MusicService.isPlaying()) MusicService.command(this, MusicService.ACTION_YIELD);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(14));
        // dark brushed metal with a blue light behind the deck
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF26282D, 0xFF141519, 0xFF0A0B0D});
        GradientDrawable glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientRadius(dp(420));
        glow.setGradientCenter(0.28f, 0.55f);
        glow.setColors(new int[]{0x2200A8FF, 0x0000A8FF});
        root.setBackground(new LayerDrawable(new android.graphics.drawable.Drawable[]{base, glow}));

        // ---- Top bar ----
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(Ui.iconButton(this, R.drawable.ic_back, 48, false, this::finish),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = Ui.text(this, "Bluetooth", 24, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        t.setPadding(dp(16), 0, 0, 0);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView usb = Ui.text(this, "Ir al reproductor USB", 13, Color.WHITE);
        usb.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(dp(16));
        chip.setColor(0x33FFFFFF);
        usb.setBackground(chip);
        Ui.pressable(usb, 0.94f);
        usb.setOnClickListener(v -> {
            startActivity(new Intent(this, PlayerActivity.class));
            finish();
        });
        bar.addView(usb);
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("hh:mm");
        clock.setTextSize(24);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Typeface.DEFAULT_BOLD);
        clock.setPadding(dp(18), 0, dp(4), 0);
        bar.addView(clock);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(52)));

        // ---- Body: record | song ----
        LinearLayout body = new LinearLayout(this);
        body.setGravity(Gravity.CENTER_VERTICAL);
        record = new RecordView(this);
        body.addView(record, new LinearLayout.LayoutParams(0, -1, 1.1f));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        info.setPadding(dp(18), 0, dp(4), 0);
        // blue backlit LCD with the source bands, the song and the waves
        LinearLayout lcd = new LinearLayout(this);
        lcd.setOrientation(LinearLayout.VERTICAL);
        lcd.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable lcdBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF38C8FF, 0xFF0F8FD6, 0xFF0A4F8A});
        lcdBg.setCornerRadius(dp(10));
        lcdBg.setStroke(dp(3), 0xFF2A2C32);
        lcd.setBackground(lcdBg);
        LinearLayout bands = new LinearLayout(this);
        for (String b : new String[]{"BT", "USB", "FM", "AM", "AUX"}) {
            TextView tv = Ui.text(this, b, 12, b.equals("BT") ? 0xFF06233F : 0x6606233F);
            tv.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            tv.setLetterSpacing(0.12f);
            bands.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        lcd.addView(bands);
        info.addView(lcd, new LinearLayout.LayoutParams(-1, -2));
        status = Ui.text(this, "", 12, 0xFF06233F);
        status.setLetterSpacing(0.15f);
        status.setGravity(Gravity.START);
        status.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        status.setPadding(0, dp(10), 0, 0);
        lcd.addView(status);
        title = Ui.text(this, "", 30, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START);
        title.setShadowLayer(dp(6), 0, 0, 0xAA00E5FF);
        title.setPadding(0, dp(4), 0, 0);
        lcd.addView(title);
        artist = Ui.text(this, "", 17, 0xE6FFFFFF);
        artist.setGravity(Gravity.START);
        artist.setPadding(0, dp(2), 0, dp(8));
        lcd.addView(artist);
        wave = new WaveView(this);
        lcd.addView(wave, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(22), 0, 0);
        // turntable-style chrome knobs
        controls.addView(new KnobButton(this, R.drawable.ic_phone, false, this::openFactoryBluetooth), sq(54, 0));
        controls.addView(new KnobButton(this, R.drawable.ic_prev, false,
                () -> nowPlaying.skip(nowPlaying.btSource(), false)), sq(62, 18));
        playBtn = new KnobButton(this, R.drawable.ic_play, true,
                () -> nowPlaying.playPause(nowPlaying.btSource()));
        controls.addView(playBtn, sq(92, 18));
        controls.addView(new KnobButton(this, R.drawable.ic_next, false,
                () -> nowPlaying.skip(nowPlaying.btSource(), true)), sq(62, 18));
        controls.addView(new KnobButton(this, R.drawable.ic_bluetooth, false,
                () -> safeStart(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))), sq(54, 18));
        info.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        body.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1f);
        blp.topMargin = dp(6);
        root.addView(body, blp);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        nowPlaying.addListener(this);
        onNowPlayingChanged();
    }

    @Override
    protected void onPause() {
        super.onPause();
        nowPlaying.removeListener(this);
    }

    @Override
    public void onNowPlayingChanged() {
        NowPlaying.Source s = nowPlaying.btSource();
        NowPlaying.Track tr = nowPlaying.track(s);
        boolean empty = tr.title.isEmpty();
        title.setText(empty ? "Bluetooth" : tr.title);
        artist.setText(empty ? "Pon música en tu celular" : tr.artist);
        status.setText(tr.playing ? "SONANDO DESDE TU CELULAR" : empty ? "ESPERANDO AL CELULAR" : "EN PAUSA");
        playBtn.setIcon(tr.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (lastTitle != null && !empty && !tr.title.equals(lastTitle)) record.cue(); // arm moves on a new song
        lastTitle = tr.title;
        record.setPlaying(tr.playing);
        record.setArt(tr.art);
        record.setLabel(empty ? "Bluetooth" : tr.artist.isEmpty() ? tr.title : tr.artist);
        wave.setPlaying(tr.playing);
    }

    /** The radio's own Bluetooth app (calls, contacts, pairing). */
    private void openFactoryBluetooth() {
        if (Profile.isNwd(this)) {
            startActivity(new Intent(this, BtActivity.class));
            return;
        }
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            String text = (ri.loadLabel(pm) + " " + ri.activityInfo.packageName).toLowerCase();
            if (text.contains("bluetooth") || text.contains("btmusic") || text.contains("bt music")
                    || text.contains("phone link") || text.contains("teléfono") || text.contains("telefono")) {
                if (safeStart(new Intent(Intent.ACTION_MAIN).setClassName(ri.activityInfo.packageName, ri.activityInfo.name)))
                    return;
            }
        }
        safeStart(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    private boolean safeStart(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private LinearLayout.LayoutParams sq(int size, int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
        lp.leftMargin = dp(left);
        return lp;
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
}
