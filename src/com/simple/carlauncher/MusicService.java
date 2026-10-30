package com.simple.carlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The launcher's own music player. Plays files in the background (closing the player screen
 * or pressing Back does not stop it), answers the steering-wheel media buttons through a media
 * session, and remembers the queue, song and position so it can carry on at the next power-on.
 */
public class MusicService extends Service implements MediaPlayer.OnCompletionListener,
        MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = "MusicService";
    static final String ACTION_PLAY_QUEUE = "play_queue", ACTION_TOGGLE = "toggle", ACTION_PLAY = "play",
            ACTION_PAUSE = "pause", ACTION_NEXT = "next", ACTION_PREV = "prev", ACTION_RESUME = "resume",
            ACTION_SEEK = "seek", ACTION_SHUFFLE = "shuffle", ACTION_REPEAT = "repeat";
    static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;
    private static final int NOTIFY_ID = 7;

    interface Listener {
        void onMusicChanged();
    }

    private static final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private static MusicService instance;

    // ---------- State visible to the screens ----------

    static final class State {
        String title = "", artist = "", type = "", path = "";
        Bitmap art;
        boolean playing, hasSong, shuffle;
        int repeat = REPEAT_ALL, position, duration, index, count;
        int total; // play time of the whole folder (0 while it is being measured)
    }

    private final State state = new State();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private boolean prepared, wantPlay, pausedByFocus;
    private int pendingSeek;
    private List<String> queue = new ArrayList<>();   // play order
    private List<String> original = new ArrayList<>(); // folder order (to undo shuffle)
    private MediaSession session;
    private AudioManager audio;

    // ---------- Commands from anywhere ----------

    static void command(Context c, String action) {
        c.startService(new Intent(c, MusicService.class).setAction(action));
    }

    static void playQueue(Context c, List<File> files, int index) {
        String[] paths = new String[files.size()];
        for (int i = 0; i < paths.length; i++) paths[i] = files.get(i).getPath();
        c.startService(new Intent(c, MusicService.class).setAction(ACTION_PLAY_QUEUE)
                .putExtra("paths", paths).putExtra("index", index));
    }

    static void seek(Context c, int ms) {
        c.startService(new Intent(c, MusicService.class).setAction(ACTION_SEEK).putExtra("ms", ms));
    }

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Current state, or the saved one if the service is not running. */
    static State state(Context c) {
        if (instance != null) {
            instance.state.position = instance.currentPosition();
            return instance.state;
        }
        State s = new State();
        SharedPreferences p = prefs(c);
        String path = p.getString("path", "");
        if (!path.isEmpty()) {
            File f = new File(path);
            s.hasSong = true;
            s.path = path;
            s.title = p.getString("title", MusicLibrary.baseName(f));
            s.artist = p.getString("artist", "");
            s.type = MusicLibrary.type(f);
            s.position = p.getInt("position", 0);
            s.duration = p.getInt("duration", 0);
            s.total = p.getInt("total", 0);
        }
        s.shuffle = p.getBoolean("shuffle", false);
        s.repeat = p.getInt("repeat", REPEAT_ALL);
        return s;
    }

    /** Whether music was playing when the car was turned off. */
    static boolean wasPlaying(Context c) {
        return prefs(c).getBoolean("was_playing", false) && !prefs(c).getString("path", "").isEmpty();
    }

    /** Whether this player has a song loaded (then the Multimedia card controls it). */
    static boolean hasSong(Context c) {
        return instance != null ? instance.state.hasSong : !prefs(c).getString("path", "").isEmpty();
    }

    /** Audio session of the current song (for the equalizer), or 0. */
    static int audioSession() {
        return instance != null && instance.player != null ? instance.player.getAudioSessionId() : 0;
    }

    static boolean isPlaying() {
        return instance != null && instance.state.playing;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("player", Context.MODE_PRIVATE);
    }

    // ---------- Service ----------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        session = new MediaSession(this, "CleanLauncher");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); }
            @Override public void onSkipToNext() { next(true); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long pos) { seekTo((int) pos); }
        });
        Intent open = new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        session.setSessionActivity(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT));
        session.setActive(true);
        loadSaved();
        handler.post(saver);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String a = i == null ? null : i.getAction();
        if (a == null) return START_NOT_STICKY;
        switch (a) {
            case ACTION_PLAY_QUEUE:
                String[] paths = i.getStringArrayExtra("paths");
                if (paths != null && paths.length > 0) setQueue(Arrays.asList(paths), i.getIntExtra("index", 0));
                break;
            case ACTION_TOGGLE: if (state.playing) pause(); else play(); break;
            case ACTION_PLAY: play(); break;
            case ACTION_PAUSE: pause(); break;
            case ACTION_NEXT: next(true); break;
            case ACTION_PREV: prev(); break;
            case ACTION_SEEK: seekTo(i.getIntExtra("ms", 0)); break;
            case ACTION_SHUFFLE: setShuffle(!state.shuffle); break;
            case ACTION_REPEAT: state.repeat = (state.repeat + 1) % 3; save(); changed(); break;
            case ACTION_RESUME: resumeAfterBoot(); break;
            default: break;
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        save();
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            closeEffects();
            player.release();
        }
        player = null;
        session.release();
        audio.abandonAudioFocus(this);
        instance = null;
        super.onDestroy();
    }

    // ---------- Queue ----------

    private void loadSaved() {
        SharedPreferences p = prefs(this);
        String q = p.getString("queue", "");
        if (!q.isEmpty()) original = new ArrayList<>(Arrays.asList(q.split("\n")));
        String order = p.getString("order", "");
        queue = order.isEmpty() ? new ArrayList<>(original) : new ArrayList<>(Arrays.asList(order.split("\n")));
        state.shuffle = p.getBoolean("shuffle", false);
        state.repeat = p.getInt("repeat", REPEAT_ALL);
        state.index = Math.max(0, Math.min(p.getInt("index", 0), Math.max(0, queue.size() - 1)));
        pendingSeek = p.getInt("position", 0);
        state.count = queue.size();
        state.total = p.getInt("total", 0);
        if (!queue.isEmpty()) load(state.index, false);
        if (state.total == 0 && !original.isEmpty()) measureTotal();
    }

    private int totalGeneration;

    /** Adds up the length of every song in the folder, in the background. */
    private void measureTotal() {
        final int gen = ++totalGeneration;
        final List<String> files = new ArrayList<>(original);
        state.total = 0;
        new Thread(() -> {
            long sum = 0;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            for (String f : files) {
                if (gen != totalGeneration) break; // another folder was chosen meanwhile
                try {
                    r.setDataSource(f);
                    String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (d != null) sum += Long.parseLong(d.trim());
                } catch (Exception ignored) { }
            }
            try { r.release(); } catch (Exception ignored) { }
            final int total = (int) Math.min(Integer.MAX_VALUE, sum);
            handler.post(() -> {
                if (gen != totalGeneration) return;
                state.total = total;
                prefs(this).edit().putInt("total", total).apply();
                changed();
            });
        }, "folder-length").start();
    }

    private void setQueue(List<String> paths, int start) {
        original = new ArrayList<>(paths);
        String first = paths.get(Math.max(0, Math.min(start, paths.size() - 1)));
        queue = new ArrayList<>(paths);
        if (state.shuffle) {
            Collections.shuffle(queue, new Random());
            queue.remove(first);
            queue.add(0, first); // the song the user tapped plays first
        }
        state.count = queue.size();
        pendingSeek = 0;
        load(queue.indexOf(first), true);
        measureTotal();
    }

    private void setShuffle(boolean on) {
        state.shuffle = on;
        String cur = state.index < queue.size() ? queue.get(state.index) : null;
        queue = new ArrayList<>(original);
        if (on && cur != null) {
            Collections.shuffle(queue, new Random());
            queue.remove(cur);
            queue.add(0, cur);
        }
        state.index = cur == null ? 0 : Math.max(0, queue.indexOf(cur));
        save();
        changed();
    }

    // ---------- Playback ----------

    /** Loads queue[index]; starts playing when {@code play} is true. Skips unreadable files. */
    private void load(int index, boolean play) {
        if (queue.isEmpty()) return;
        for (int tries = 0; tries < queue.size(); tries++) {
            int i = (index + tries) % queue.size();
            File f = new File(queue.get(i));
            if (!f.canRead()) continue; // drive removed or file gone
            state.index = i;
            wantPlay = play;
            prepare(f);
            return;
        }
        Log.w(TAG, "no readable songs in the queue");
        state.playing = false;
        changed();
    }

    private void prepare(File f) {
        if (player != null) {
            closeEffects();
            player.release();
        }
        prepared = false;
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        player.setWakeMode(getApplicationContext(), android.os.PowerManager.PARTIAL_WAKE_LOCK);
        player.setOnPreparedListener(this);
        player.setOnCompletionListener(this);
        player.setOnErrorListener(this);
        readTags(f);
        // tell equalizer apps (system / factory DSP) about our audio so they can apply to it
        sendBroadcast(new Intent(android.media.audiofx.AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, player.getAudioSessionId())
                .putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, getPackageName())
                .putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE,
                        android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC));
        try {
            player.setDataSource(f.getPath());
            player.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "cannot open " + f, e);
            handler.post(() -> next(false));
        }
        changed();
    }

    private void readTags(File f) {
        state.path = f.getPath();
        state.hasSong = true;
        state.type = MusicLibrary.type(f);
        state.title = MusicLibrary.baseName(f);
        state.artist = "";
        state.art = null;
        state.duration = 0;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            String t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (a == null) a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            String br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            String dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (!TextUtils.isEmpty(t)) state.title = t.trim();
            if (!TextUtils.isEmpty(a)) state.artist = a.trim();
            if (!TextUtils.isEmpty(br)) {
                int kbps = Math.round(Integer.parseInt(br.trim()) / 1000f);
                if (kbps > 0) state.type = state.type + " · " + kbps + " kbps";
            }
            if (!TextUtils.isEmpty(dur)) state.duration = Integer.parseInt(dur.trim());
            byte[] pic = r.getEmbeddedPicture();
            if (pic != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                state.art = BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
            }
        } catch (Exception ignored) {
            // no or broken tags: keep the file name
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        prepared = true;
        state.duration = mp.getDuration();
        if (pendingSeek > 0 && pendingSeek < state.duration - 2000) mp.seekTo(pendingSeek);
        pendingSeek = 0;
        if (wantPlay) play();
        else changed();
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        if (state.repeat == REPEAT_ONE) {
            mp.seekTo(0);
            mp.start();
            return;
        }
        next(false);
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.w(TAG, "player error " + what + "/" + extra);
        handler.post(() -> next(false));
        return true;
    }

    void play() {
        if (queue.isEmpty()) return;
        if (player == null || !prepared) {
            wantPlay = true;
            if (player == null) load(state.index, true);
            return;
        }
        int r = audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) Log.w(TAG, "audio focus not granted, playing anyway");
        if (Profile.isNwd(this)) Ui.requestSource(this, Ui.SOURCE_MUSIC); // NWD: route Android audio to the speakers
        player.start();
        pausedByFocus = false;
        state.playing = true;
        startForeground(NOTIFY_ID, notification());
        save();
        changed();
    }

    void pause() {
        wantPlay = false;
        if (player != null && prepared && player.isPlaying()) player.pause();
        state.playing = false;
        stopForeground(false);
        save();
        changed();
    }

    /** Next song; {@code user} = pressed by the user (wraps even with repeat off). */
    void next(boolean user) {
        if (queue.isEmpty()) return;
        int n = state.index + 1;
        if (n >= queue.size()) {
            if (state.repeat == REPEAT_OFF && !user) {
                pause();
                seekTo(0);
                return;
            }
            n = 0;
        }
        pendingSeek = 0;
        load(n, user || state.playing || wantPlay);
    }

    void prev() {
        if (queue.isEmpty()) return;
        if (currentPosition() > 3000) { // like a CD player: first press goes to the start
            seekTo(0);
            return;
        }
        pendingSeek = 0;
        load((state.index - 1 + queue.size()) % queue.size(), state.playing || wantPlay);
    }

    void seekTo(int ms) {
        if (player != null && prepared) player.seekTo(Math.max(0, ms));
        else pendingSeek = ms;
        state.position = ms;
        changed();
    }

    private void closeEffects() {
        sendBroadcast(new Intent(android.media.audiofx.AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, player.getAudioSessionId())
                .putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, getPackageName()));
    }

    private int currentPosition() {
        try {
            return player != null && prepared ? player.getCurrentPosition() : pendingSeek;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    private void resumeAfterBoot() {
        if (queue.isEmpty()) return;
        // the drive may still be mounting: retry a few times
        final int[] tries = {0};
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (state.playing) return;
                File f = new File(queue.get(state.index));
                if (f.canRead()) {
                    wantPlay = true;
                    if (prepared) play();
                    else load(state.index, true);
                } else if (++tries[0] < 10) {
                    handler.postDelayed(this, 3000);
                }
            }
        });
    }

    @Override
    public void onAudioFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                pause(); // another player (radio, Bluetooth, an app) took over
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (state.playing) {
                    pause();
                    pausedByFocus = true; // a call or a voice prompt: resume afterwards
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (player != null && prepared) player.setVolume(0.3f, 0.3f);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (player != null && prepared) player.setVolume(1f, 1f);
                if (pausedByFocus) play();
                break;
            default:
                break;
        }
    }

    // ---------- Saving and telling the screens ----------

    private final Runnable saver = new Runnable() {
        @Override
        public void run() {
            if (state.playing) {
                state.position = currentPosition();
                prefs(MusicService.this).edit().putInt("position", state.position).apply();
                changed(); // moves the progress on the label
            }
            handler.postDelayed(this, 1000);
        }
    };

    private void save() {
        prefs(this).edit()
                .putString("queue", TextUtils.join("\n", original))
                .putString("order", TextUtils.join("\n", queue))
                .putInt("index", state.index)
                .putInt("position", currentPosition())
                .putInt("duration", state.duration)
                .putString("path", state.path)
                .putString("title", state.title)
                .putString("artist", state.artist)
                .putBoolean("shuffle", state.shuffle)
                .putInt("repeat", state.repeat)
                .putBoolean("was_playing", state.playing)
                .apply();
    }

    private void changed() {
        state.position = currentPosition();
        state.count = queue.size();
        updateSession();
        for (Listener l : listeners) l.onMusicChanged();
        NowPlaying.get(this).ownPlayerChanged(state);
    }

    private void updateSession() {
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.duration)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, state.art)
                .build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(state.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        state.position, state.playing ? 1f : 0f)
                .build());
        if (state.playing) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIFY_ID, notification());
        }
    }

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("music", "Música", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "music");
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return b.setSmallIcon(R.drawable.ic_music)
                .setContentTitle(state.title)
                .setContentText(state.artist.isEmpty() ? state.type : state.artist)
                .setLargeIcon(state.art)
                .setContentIntent(PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT))
                .setOngoing(state.playing)
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()))
                .build();
    }
}
