package com.simple.carlauncher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks what is playing from any source and routes controls back to it:
 * apps with a media session (Spotify, YouTube Music…), the factory USB/SD player
 * (com.android.music.* broadcasts) and Bluetooth audio (com.bt.* broadcasts).
 */
final class NowPlaying {

    enum Source { APP, LOCAL, BT }

    interface Listener {
        void onNowPlayingChanged();
    }

    private static final String LOCAL_PLAYER = "com.nwd.android.music.ui/.home_horizontalActivity";

    /** State reported by one source. */
    static final class Track {
        String title = "", artist = "";
        Bitmap art;
        boolean playing;
        long updated;
    }

    private static NowPlaying instance;

    static NowPlaying get(Context c) {
        if (instance == null) instance = new NowPlaying(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final List<Listener> listeners = new ArrayList<>();
    private final Track app = new Track(), local = new Track(), bt = new Track();
    final BtClient btClient = new BtClient();

    private final MediaSessionManager sessions;
    private final ComponentName notificationListener;
    private MediaController controller;

    private NowPlaying(Context c) {
        ctx = c;
        sessions = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        notificationListener = new ComponentName(c, MediaListener.class);

        IntentFilter f = new IntentFilter();
        f.addAction("com.android.music.metachanged");
        f.addAction("com.android.music.playstatechanged");
        f.addAction("com.bt.ACTION_AVRCP_MUSIC_ID3");
        f.addAction("com.bt.ACTION_BT_MUSIC_PLAY");
        f.addAction("com.bt.ACTION_BT_MUSIC_PAUSE");
        f.addAction("com.bt.ACTION_A2DP_RELEASE");
        f.addAction("com.bt.ACTION_AVRCP_RELEASE");
        c.registerReceiver(receiver, f);

        try {
            sessions.addOnActiveSessionsChangedListener(list -> refreshSessions(), notificationListener);
        } catch (SecurityException ignored) {
            // Notification access not granted: app sessions are not visible.
        }
        refreshSessions();

        btClient.bind(c, () -> {
            bt.playing = btClient.isMusicPlaying();
            if (bt.playing) bt.updated = SystemClock.elapsedRealtime();
            btClient.queryId3();
            notifyListeners();
        });
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (a == null) return;
            i.setExtrasClassLoader(NowPlaying.class.getClassLoader());
            try {
                handle(a, i);
            } catch (RuntimeException e) {
                // Factory apps may attach extras we cannot unparcel; never crash the launcher for it.
                android.util.Log.w("NowPlaying", "Ignoring broadcast " + a, e);
            }
        }

        private void handle(String a, Intent i) {
            long now = SystemClock.elapsedRealtime();
            switch (a) {
                case "com.android.music.metachanged":
                case "com.android.music.playstatechanged":
                    if (MusicService.isPlaying()) return; // our own player owns the USB side
                    if (i.hasExtra("track")) local.title = nz(i.getStringExtra("track"));
                    if (i.hasExtra("artist")) local.artist = nz(i.getStringExtra("artist"));
                    local.playing = i.getBooleanExtra("playing", local.playing);
                    local.updated = now;
                    break;
                case "com.bt.ACTION_AVRCP_MUSIC_ID3":
                    bt.title = nz(i.getStringExtra("extra_avrcp_id3_title"));
                    bt.artist = nz(i.getStringExtra("extra_avrcp_id3_artist"));
                    if (!bt.title.isEmpty()) bt.updated = now;
                    break;
                case "com.bt.ACTION_BT_MUSIC_PLAY":
                    bt.playing = true;
                    bt.updated = now;
                    btClient.queryId3();
                    break;
                case "com.bt.ACTION_BT_MUSIC_PAUSE":
                    bt.playing = false;
                    break;
                default: // A2DP/AVRCP released: phone disconnected
                    bt.playing = false;
                    bt.title = "";
                    bt.artist = "";
                    bt.updated = 0;
                    break;
            }
            notifyListeners();
        }
    };

    // ---------- App media sessions ----------

    private final MediaController.Callback sessionCallback = new MediaController.Callback() {
        public void onMetadataChanged(MediaMetadata m) { readSession(); }
        public void onPlaybackStateChanged(PlaybackState s) { readSession(); }
        public void onSessionDestroyed() { refreshSessions(); }
    };

    private void refreshSessions() {
        List<MediaController> list = null;
        try {
            list = sessions.getActiveSessions(notificationListener);
        } catch (SecurityException ignored) { }
        MediaController pick = null;
        if (list != null) {
            for (MediaController c : list) {
                if ("com.android.server.telecom".equals(c.getPackageName())) continue;
                if (ctx.getPackageName().equals(c.getPackageName())) continue; // our own player is LOCAL
                if (pick == null) pick = c;
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
            }
        }
        if (controller != null) controller.unregisterCallback(sessionCallback);
        controller = pick;
        if (controller != null) controller.registerCallback(sessionCallback);
        readSession();
    }

    private void readSession() {
        if (controller == null) {
            app.title = "";
            app.artist = "";
            app.art = null;
            app.playing = false;
            app.updated = 0;
        } else {
            MediaMetadata m = controller.getMetadata();
            PlaybackState s = controller.getPlaybackState();
            app.title = m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
            app.artist = m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
            Bitmap art = m == null ? null : m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null && m != null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            app.art = art;
            boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            if (playing || !app.title.isEmpty()) app.updated = SystemClock.elapsedRealtime();
            app.playing = playing;
        }
        notifyListeners();
    }

    // ---------- Public API ----------

    /** The source to show: whatever is playing (most recent first), else the last one used. */
    Source current() {
        Source best = null;
        long bestTime = -1;
        for (Source s : Source.values()) {
            Track t = track(s);
            if (t.playing && t.updated > bestTime) { best = s; bestTime = t.updated; }
        }
        if (best != null) return best;
        for (Source s : Source.values()) {
            Track t = track(s);
            if (t.updated > 0 && t.updated > bestTime) { best = s; bestTime = t.updated; }
        }
        return best;
    }

    Track track(Source s) {
        return s == Source.APP ? app : s == Source.LOCAL ? local : bt;
    }

    String label(Source s) {
        if (s == Source.BT) return "Bluetooth";
        if (s == Source.LOCAL) return MusicService.hasSong(ctx) ? "Reproductor" : "USB";
        if (controller != null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                return pm.getApplicationLabel(pm.getApplicationInfo(controller.getPackageName(), 0)).toString();
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return "Música";
    }

    /**
     * The "MP3" side of the music card: the factory USB/SD player, or a music app with a media
     * session (VLC, Musicolet…) if that is what was used more recently. Never Bluetooth.
     */
    Source mp3Source() {
        if (app.updated > 0 && (app.playing || app.updated > local.updated) && !local.playing) return Source.APP;
        return Source.LOCAL;
    }

    void playPause() {
        playPause(current());
    }

    /** Called by our own player on every change; shown as the LOCAL (USB) source. */
    void ownPlayerChanged(MusicService.State s) {
        boolean changed = !s.title.equals(local.title) || !s.artist.equals(local.artist)
                || s.playing != local.playing || s.art != local.art;
        local.title = s.title;
        local.artist = s.artist;
        local.art = s.art;
        local.playing = s.playing;
        if (s.playing || changed) local.updated = SystemClock.elapsedRealtime();
        if (changed) notifyListeners(); // not every second for the progress
    }

    /** Package of the music app with the active media session, or null. */
    String appPackage() {
        return controller != null ? controller.getPackageName() : null;
    }

    /** Sends a media key to whatever player is listening (used to resume after power-on). */
    void sendPlayKey() {
        mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
    }

    /** Opens the factory USB/SD player (or the default music app). */
    static void openMusicPlayer(Context c) {
        openLocalPlayer(c);
    }

    void skip(boolean next) {
        skip(current(), next);
    }

    void open(Context c) {
        open(c, current());
    }

    /**
     * Where the phone's Bluetooth music shows up: NWD units report it directly (BT); on other
     * radios the factory Bluetooth app may publish it as a media session (APP).
     */
    Source btSource() {
        if (Profile.isNwd(ctx) || bt.updated > 0) return Source.BT;
        if (controller != null && isBluetoothApp(controller.getPackageName())) return Source.APP;
        return Source.BT;
    }

    private static boolean isBluetoothApp(String pkg) {
        String p = pkg.toLowerCase();
        return p.contains("bluetooth") || p.contains(".bt") || p.contains("btmusic") || p.contains("a2dp")
                || p.contains("phonelink") || p.contains("bt.") || p.contains("goc") || p.contains("fyt");
    }

    void playPause(Source s) {
        if (s == Source.BT && !Profile.isNwd(ctx)) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); // the factory Bluetooth app takes the media keys
            return;
        }
        if (s == Source.BT) {
            if (!Ui.btIsSource(ctx)) {
                // Radio or the USB player has the speakers (the phone may even be playing muted):
                // switch the amplifier to Bluetooth and start the phone.
                if (MusicService.isPlaying()) MusicService.command(ctx, MusicService.ACTION_YIELD);
                Ui.userChoseRadioAt = 0;
                Ui.selectBluetooth(ctx);
                btClient.playControl(BtClient.PLAY);
            } else {
                btClient.playControl(bt.playing ? BtClient.PAUSE : BtClient.PLAY);
            }
        } else if (s == Source.APP && controller != null) {
            if (app.playing) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        } else if (MusicService.hasSong(ctx)) {
            MusicService.command(ctx, MusicService.ACTION_TOGGLE);
        } else {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    }

    void skip(Source s, boolean next) {
        if (s == Source.BT && !Profile.isNwd(ctx)) {
            mediaKey(next ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            return;
        }
        if (s == Source.BT) {
            if (!Ui.btIsSource(ctx)) {
                if (MusicService.isPlaying()) MusicService.command(ctx, MusicService.ACTION_YIELD);
                Ui.selectBluetooth(ctx); // changing song on Bluetooth: make it audible too
            }
            btClient.playControl(next ? BtClient.NEXT : BtClient.PREVIOUS);
        } else if (s == Source.APP && controller != null) {
            if (next) controller.getTransportControls().skipToNext();
            else controller.getTransportControls().skipToPrevious();
        } else if (MusicService.hasSong(ctx)) {
            MusicService.command(ctx, next ? MusicService.ACTION_NEXT : MusicService.ACTION_PREV);
        } else {
            mediaKey(next ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }
    }

    /** Opens the screen that belongs to the given source. */
    void open(Context c, Source s) {
        if (s == Source.BT || (s == Source.APP && s == btSource() && controller != null
                && isBluetoothApp(controller.getPackageName()))) {
            c.startActivity(new Intent(c, BtPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (s == Source.APP && controller != null) {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(controller.getPackageName());
            if (i != null) c.startActivity(i);
        } else {
            // our own player replaces the factory USB player
            c.startActivity(new Intent(c, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /** The factory USB/SD player on NWD units; elsewhere the radio's default music app. */
    private static void openLocalPlayer(Context c) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(ComponentName.unflattenFromString(LOCAL_PLAYER));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            c.startActivity(i);
            return;
        } catch (Exception ignored) { }
        try {
            Intent i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            android.widget.Toast.makeText(c, "No hay reproductor de música", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    void addListener(Listener l) {
        listeners.add(l);
        refreshSessions();
        if (btClient.isBound()) btClient.queryId3();
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) l.onNowPlayingChanged();
    }

    private void mediaKey(int keyCode) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
