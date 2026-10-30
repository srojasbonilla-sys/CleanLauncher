package com.simple.carlauncher;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Spoken welcome ~20 s after the car is switched on: greeting, current weather,
 * seat-belt reminder, "pico y placa" on Thursdays and "drive carefully".
 *
 * The phrases are pre-rendered clips (res/raw/v_*.mp3) because the head unit has no
 * text-to-speech engine. The launcher is listed in /nwdappconfig/app/NaviSound.ini so the
 * NWD kernel mixes this audio over the radio/music like navigation prompts.
 */
public class GreeterService extends Service {

    private static final String TAG = "Greeter";
    private static final long DELAY_MS = 20_000;
    private static final long MIN_INTERVAL_MS = 60_000; // only skip duplicate wake signals

    // MCU source ids (ApplicationList.xml "Source") of the last audio source before ACC off.
    private static final int SRC_RADIO = 4, SRC_BT = -125, SRC_USB = -128;

    private final Handler handler = new Handler();
    private final List<Integer> queue = new ArrayList<>();
    private final RadioClient radio = new RadioClient();
    private final SoundClient sound = new SoundClient();
    private MediaPlayer player;
    private boolean waitingForPhone;
    private boolean btPlaying;

    @Override
    public void onCreate() {
        super.onCreate();
        radio.bind(this);
        sound.bind(this, () -> handler.post(() -> EqAuto.apply(this, sound)));
        IntentFilter f = new IntentFilter();
        f.addAction("com.nwd.action.ACTION_CHANGE_SOURCE");      // auto EQ: radio vs music curve
        f.addAction("com.nwd.action.ACTION_VOLUME_STATE_CHANGE"); // auto EQ: loudness
        f.addAction("com.nwd.action.ACTION_MCU_STATE_CHANGE"); // ACC off (3) / on (1)
        f.addAction("com.bt.ACTION_A2DP_ESTABLISHED");
        f.addAction("com.bt.ACTION_AVRCP_ESTABLISHED");
        f.addAction("com.bt.ACTION_BT_MUSIC_PLAY");
        f.addAction("com.bt.ACTION_BT_MUSIC_PAUSE");
        f.addAction("com.simple.carlauncher.TEST_CAR_ON"); // adb test hook
        registerReceiver(events, f);
        // When the car is turned back on after a short stop the unit only wakes up (no reboot),
        // and some units then show their factory launcher. Come back to the front on wake too.
        android.content.IntentFilter wake = new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
        wake.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(wakeReceiver, wake);
        // Right after boot, bring the last source back and greet. A plain restart of this app
        // (update, crash) must not touch the audio.
        if (android.os.SystemClock.elapsedRealtime() < 3 * 60_000) {
            handler.postDelayed(this::restoreAudio, 4000);
            schedule();
            // Some head units open their factory launcher on every cold boot, ignoring the
            // default home app. Come back to the front once it has finished starting.
            handler.postDelayed(() -> bringHome(this), 6000);
            handler.postDelayed(() -> bringHome(this), 15000);
        }
        if (!Profile.isNwd(this)) {
            // Remember whether multimedia was playing, so it can resume at the next power-on.
            NowPlaying np = NowPlaying.get(this);
            np.addListener(mediaWatcher);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if ("com.nwd.action.ACTION_CHANGE_SOURCE".equals(a)) {
                handler.postDelayed(() -> EqAuto.apply(GreeterService.this, sound), 1500);
                return;
            }
            if ("com.nwd.action.ACTION_VOLUME_STATE_CHANGE".equals(a)) {
                handler.postDelayed(() -> EqAuto.applyLoudness(GreeterService.this, sound), 300);
                return;
            }
            if ("com.nwd.action.ACTION_MCU_STATE_CHANGE".equals(a)) {
                int state = i.getByteExtra("extra_mcu_state", (byte) -1);
                Log.d(TAG, "mcu_state=" + state);
                if (state == 3) onCarOff();
                else if (state == 1) onCarOn();
            } else if ("com.simple.carlauncher.TEST_CAR_ON".equals(a)) {
                onCarOn();
            } else if ("com.bt.ACTION_BT_MUSIC_PAUSE".equals(a)) {
                btPlaying = false;
            } else if ("com.bt.ACTION_BT_MUSIC_PLAY".equals(a)) {
                // The phone *started* music (paused -> playing): make Bluetooth the source so the
                // radio stops instead of both playing. The phone repeats PLAY while streaming, so
                // react only to the transition, and never right after the user picked the radio.
                waitingForPhone = false;
                boolean started = !btPlaying;
                btPlaying = true;
                long sinceRadio = android.os.SystemClock.elapsedRealtime() - Ui.userChoseRadioAt;
                if (started && Ui.radioIsSource(GreeterService.this) && sinceRadio > 30_000) {
                    Log.d(TAG, "BT music started over the radio, switching to Bluetooth");
                    Ui.selectBluetooth(GreeterService.this);
                }
            } else if (waitingForPhone) {
                // Phone connected after ACC on: resume Bluetooth music.
                waitingForPhone = false;
                handler.postDelayed(() -> startBluetoothMusic(), 2500);
            }
        }
    };

    // ---------- Keep the audio source across ACC off/on ----------

    /**
     * The NWD kernel restores the foreground app on ACC on. With this launcher in front that is
     * "no source", so the radio/BT would stay silent. Remember the real source ourselves.
     */
    private void onCarOff() {
        int src = android.provider.Settings.System.getInt(getContentResolver(), "mcu_current_source", 0);
        NowPlaying np = NowPlaying.get(this);
        boolean btPlaying = np.track(NowPlaying.Source.BT).playing;
        if (btPlaying) src = SRC_BT;
        prefs().edit().putInt("last_source", src).apply();
        Log.d(TAG, "car off, last source=" + src);
    }

    private void onCarOn() {
        handler.postDelayed(this::restoreAudio, 3000);
        schedule();
    }

    private void restoreAudio() {
        if (MusicService.wasPlaying(this)) {
            // our own player was on when the car was turned off: carry on, same song and second
            MusicService.command(this, MusicService.ACTION_RESUME);
            return;
        }
        if (!Profile.isNwd(this)) {
            resumeMultimedia();
            return;
        }
        int src = prefs().getInt("last_source",
                android.provider.Settings.System.getInt(getContentResolver(), "mcu_current_source", 0));
        Log.d(TAG, "restore audio source=" + src);
        if (NowPlaying.get(this).track(NowPlaying.Source.BT).playing) {
            Ui.selectBluetooth(this); // phone already playing: keep Bluetooth
        } else if (src == SRC_RADIO) {
            Ui.selectRadio(this, radio);
            Ui.userChoseRadioAt = 0; // automatic restore: a phone that starts playing may still take over
        } else if (src == SRC_BT) {
            if (NowPlaying.get(this).btClient.isPhoneConnected()) startBluetoothMusic();
            else waitingForPhone = true; // wait for the phone to connect
        } else if (src == SRC_USB) {
            Ui.requestSource(this, Ui.SOURCE_MUSIC);
        }
    }

    private void startBluetoothMusic() {
        Ui.selectBluetooth(this);
        handler.postDelayed(() -> NowPlaying.get(this).btClient.playControl(BtClient.PLAY), 1500);
    }

    // ---------- Greeting ----------

    private void schedule() {
        long last = prefs().getLong("last_greet", 0);
        if (System.currentTimeMillis() - last < MIN_INTERVAL_MS) return;
        handler.removeCallbacks(greet);
        handler.postDelayed(greet, DELAY_MS);
        Weather.fetch(this, w -> { }); // refresh the cache before speaking
    }

    private final Runnable greet = () -> {
        if (!Profile.voiceEnabled(this)) return;
        prefs().edit().putLong("last_greet", System.currentTimeMillis()).apply();
        List<String> clips = buildMessage();
        // Owner's build ships recorded clips; shared builds speak with the device's TTS engine.
        if (getResources().getIdentifier("v_careful", "raw", getPackageName()) != 0) speak(clips);
        else speakTts(Speech.text(this, clips));
    };

    private android.speech.tts.TextToSpeech tts;

    private void speakTts(String text) {
        if (tts != null) tts.shutdown();
        tts = new android.speech.tts.TextToSpeech(this, status -> {
            if (status != android.speech.tts.TextToSpeech.SUCCESS) return;
            java.util.Locale es = new java.util.Locale("es", "US");
            if (tts.isLanguageAvailable(es) < 0) es = new java.util.Locale("es");
            tts.setLanguage(es);
            Bundle params = new Bundle();
            tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, "greet");
        });
    }

    private List<String> buildMessage() {
        List<String> clips = new ArrayList<>();
        Calendar now = Calendar.getInstance();
        int day = now.get(Calendar.DAY_OF_WEEK);
        int h = now.get(Calendar.HOUR_OF_DAY);

        // Monday: "¡Hola, papacito! Hoy es lunes." Other days: greeting by time of day.
        if (day == Calendar.MONDAY) clips.add("v_greet_monday");
        else clips.add(h < 12 ? "v_greet_morning" : h < 19 ? "v_greet_afternoon" : "v_greet_night");

        Weather w = Weather.cached(this);
        if (w != null) {
            clips.add("v_cond_" + conditionClip(w.code));
            int t = Math.max(-5, Math.min(40, w.temp));
            clips.add(t < 0 ? "v_temp_m" + (-t) : "v_temp_" + t);
        }
        if (day == Calendar.MONDAY) clips.add("v_maintenance"); // oil, radiator, brake fluid
        if (Odometer.isConfigured(this)) {
            int left = Odometer.remainingKm(this);
            if (left < 0) clips.add("v_oil_due");
            else if (left <= Odometer.WARN_KM) clips.add("v_oil_soon");
        }
        clips.add("v_seatbelt");
        if (day == Calendar.THURSDAY && Profile.picoPlacaEnabled(this)) {
            clips.add("v_picoplaca"); // Quito: 6:00-9:30, 16:00-20:00
        }
        clips.add("v_careful");
        return clips;
    }

    private static String conditionClip(int code) {
        if (code == 0) return "clear";
        if (code == 1) return "mostly";
        if (code == 2) return "partly";
        if (code == 3) return "cloudy";
        if (code == 45 || code == 48) return "fog";
        if (code >= 51 && code <= 57) return "drizzle";
        if (code >= 61 && code <= 67) return "rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "snow";
        if (code >= 80 && code <= 82) return "showers";
        if (code >= 95) return "storm";
        return "cloudy";
    }

    /** Plays the clips one after another. */
    void speak(List<String> clips) {
        queue.clear();
        for (String name : clips) {
            int id = getResources().getIdentifier(name, "raw", getPackageName());
            if (id != 0) queue.add(id);
        }
        Log.d(TAG, "speaking " + clips);
        playNext();
    }

    private void playNext() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (queue.isEmpty()) return;
        int id = queue.remove(0);
        player = new MediaPlayer();
        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setDataSource(this, android.net.Uri.parse(
                    "android.resource://" + getPackageName() + "/" + id));
            player.setOnCompletionListener(mp -> playNext());
            player.setOnErrorListener((mp, what, extra) -> {
                playNext();
                return true;
            });
            player.prepare();
            player.start();
        } catch (Exception e) {
            Log.w(TAG, "clip failed", e);
            playNext();
        }
    }

    // ---------- Multimedia resume (radios without the NWD services) ----------

    private final NowPlaying.Listener mediaWatcher = () -> {
        NowPlaying np = NowPlaying.get(GreeterService.this);
        NowPlaying.Source s = np.current();
        if (s == null) return;
        boolean playing = np.track(s).playing;
        SharedPreferences.Editor e = prefs().edit();
        if (s == NowPlaying.Source.BT) {
            e.putString("resume", playing ? "bt" : "");
        } else if (s == NowPlaying.Source.LOCAL && MusicService.hasSong(GreeterService.this)) {
            e.putString("resume", ""); // our own player remembers and resumes by itself
        } else if (playing) {
            String pkg = s == NowPlaying.Source.APP ? np.appPackage() : null;
            e.putString("resume", pkg != null ? "app:" + pkg : "local");
        } else if (s == np.mp3Source()) {
            e.putString("resume", ""); // paused by the user: stay quiet next time
        }
        e.apply();
    };

    /**
     * After a cold boot, if music from the USB player (or a music app) was playing when the car
     * was turned off, open that player, press play, and come back to the home screen.
     */
    private void resumeMultimedia() {
        String what = prefs().getString("resume", "");
        Log.d(TAG, "resume multimedia: " + what);
        if (what.equals("local")) {
            NowPlaying.openMusicPlayer(this);
        } else if (what.startsWith("app:")) {
            Intent i = getPackageManager().getLaunchIntentForPackage(what.substring(4));
            if (i == null) return;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
            } catch (Exception ignored) {
                return;
            }
        } else {
            return;
        }
        NowPlaying np = NowPlaying.get(this);
        handler.postDelayed(np::sendPlayKey, 3000);
        handler.postDelayed(() -> {
            // Some players only resume on a second press once their files are scanned.
            if (!np.track(np.mp3Source()).playing) np.sendPlayKey();
        }, 6500);
        handler.postDelayed(() -> bringHome(this), 8000);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("greeter", MODE_PRIVATE);
    }

    @Override
    public void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
        unregisterReceiver(events);
        unregisterReceiver(wakeReceiver);
        radio.unbind(this);
        sound.unbind(this);
        handler.removeCallbacksAndMessages(null);
        NowPlaying.get(this).removeListener(mediaWatcher);
        if (player != null) player.release();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Starts the service at boot even if the launcher is not shown yet. */
    private long lastWake;

    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastWake < 30_000) return; // screen-on and user-present come together
            lastWake = now;
            Log.d(TAG, "wake: " + i.getAction());
            handler.postDelayed(() -> bringHome(GreeterService.this), 1500);
            handler.postDelayed(() -> bringHome(GreeterService.this), 6000);
        }
    };

    /** Shows this launcher's home screen (allowed from the background with the overlay permission). */
    static void bringHome(Context c) {
        try {
            Intent home = new Intent(c, MainActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            c.startActivity(home);
        } catch (Exception e) {
            Log.w(TAG, "could not bring home to front", e);
        }
    }

    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            bringHome(c);
            c.startService(new Intent(c, GreeterService.class));
            c.startService(new Intent(c, VolumeOverlay.class));
            c.startService(new Intent(c, Odometer.class));
        }
    }
}
