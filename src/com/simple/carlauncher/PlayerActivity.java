package com.simple.carlauncher;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen music player: a cassette with the song on its label, the transport controls and
 * a browser of the folders with music on the USB stick, SD card and internal memory. Music keeps
 * playing in {@link MusicService} after leaving this screen, with Back or Home.
 */
public class PlayerActivity extends Activity implements MusicService.Listener {

    private static List<MusicLibrary.Folder> cache; // survives closing the screen; "Actualizar" rescans

    private final Handler handler = new Handler(Looper.getMainLooper());
    private CassetteView cassette;
    private SeekBar seek;
    private TextView timeNow, timeTotal, listTitle, listInfo;
    private ImageView shuffleBtn, repeatBtn;
    private DeckKey keyPlay, keyPause, keyEject;
    private LinearLayout right;
    private View folderBack;
    private ListView list;
    private final Adapter adapter = new Adapter();
    private List<MusicLibrary.Folder> folders = new ArrayList<>();
    private MusicLibrary.Folder openFolder;
    private boolean dragging, scanning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(14));
        root.setBackgroundColor(Color.BLACK);

        // ---- Top bar ----
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(Ui.iconButton(this, R.drawable.ic_back, 48, false, this::finish),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = Ui.text(this, "Reproductor", 24, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setPadding(dp(16), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("hh:mm");
        clock.setTextSize(24);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(clock);
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(52)));

        LinearLayout body = new LinearLayout(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1f);
        blp.topMargin = dp(8);
        root.addView(body, blp);

        // ---- Left: cassette, progress, controls ----
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        cassette = new CassetteView(this);
        cassette.setOnClickListener(v -> MusicService.command(this, MusicService.ACTION_TOGGLE));
        left.addView(cassette, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        timeNow = Ui.text(this, "0:00", 13, 0xB3FFFFFF);
        seekRow.addView(timeNow, new LinearLayout.LayoutParams(dp(54), -2));
        seek = new SeekBar(this);
        seek.setProgressTintList(ColorStateList.valueOf(0xFF00E5FF));
        seek.setThumbTintList(ColorStateList.valueOf(0xFF00E5FF));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x40FFFFFF));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) timeNow.setText(CassetteView.fmt(v));
            }
            public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                MusicService.seek(PlayerActivity.this, s.getProgress());
            }
        });
        seekRow.addView(seek, new LinearLayout.LayoutParams(0, -2, 1f));
        timeTotal = Ui.text(this, "0:00", 13, 0xB3FFFFFF);
        seekRow.addView(timeTotal, new LinearLayout.LayoutParams(dp(54), -2));
        left.addView(seekRow, new LinearLayout.LayoutParams(-1, dp(40)));

        // Cassette-deck piano keys: REW, PLAY, PAUSE, FF, EJECT (shows the song list),
        // with shuffle / repeat at the ends and a round equalizer knob.
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        shuffleBtn = Ui.iconButton(this, R.drawable.ic_shuffle, 44, false,
                () -> MusicService.command(this, MusicService.ACTION_SHUFFLE));
        controls.addView(shuffleBtn, sq(44, 0));
        LinearLayout keys = new LinearLayout(this);
        keys.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable deck = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF2A2A30, 0xFF141418});
        deck.setCornerRadius(dp(12));
        deck.setStroke(1, 0x26FFFFFF);
        keys.setBackground(deck);
        DeckKey rew = key(keys, DeckKey.REW, "REW");
        rew.onTap(() -> MusicService.command(this, MusicService.ACTION_PREV));
        rew.onHold(() -> skipBy(-10000)); // hold: rewind 10 s at a time
        keyPlay = key(keys, DeckKey.PLAY, "PLAY");
        keyPlay.setAccent(0xFFE53935);
        keyPlay.onTap(() -> MusicService.command(this, MusicService.ACTION_PLAY));
        keyPause = key(keys, DeckKey.PAUSE, "PAUSE");
        keyPause.setAccent(0xFFFFB300);
        keyPause.onTap(() -> MusicService.command(this, MusicService.ACTION_PAUSE));
        DeckKey ff = key(keys, DeckKey.FF, "FF");
        ff.onTap(() -> MusicService.command(this, MusicService.ACTION_NEXT));
        ff.onHold(() -> skipBy(10000)); // hold: fast-forward 10 s at a time
        keyEject = key(keys, DeckKey.EJECT, "EJECT");
        keyEject.setAccent(0xFF00E5FF);
        keyEject.onTap(() -> showList(right.getVisibility() != View.VISIBLE));
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(0, -1, 1f);
        klp.leftMargin = dp(8);
        klp.rightMargin = dp(8);
        controls.addView(keys, klp);
        repeatBtn = Ui.iconButton(this, R.drawable.ic_repeat, 44, false,
                () -> MusicService.command(this, MusicService.ACTION_REPEAT));
        controls.addView(repeatBtn, sq(44, 0));
        controls.addView(eqKnob(), sq(60, 12));
        left.addView(controls, new LinearLayout.LayoutParams(-1, dp(92)));
        body.addView(left, new LinearLayout.LayoutParams(0, -1, 1.35f));

        // ---- Right: folders / songs ----
        right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF111116, 0xFF0A0A0D});
        bg.setCornerRadius(dp(18));
        bg.setStroke(1, 0x1AFFFFFF);
        right.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        folderBack = Ui.iconButton(this, R.drawable.ic_back, 40, false, this::showFolders);
        head.addView(folderBack, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, 0, 0);
        listTitle = Ui.text(this, "Carpetas", 17, Color.WHITE);
        listTitle.setTypeface(Typeface.DEFAULT_BOLD);
        listTitle.setGravity(Gravity.START);
        titles.addView(listTitle);
        listInfo = Ui.text(this, "", 12, 0x99FFFFFF);
        listInfo.setGravity(Gravity.START);
        titles.addView(listInfo);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(Ui.iconButton(this, R.drawable.ic_refresh, 40, false, () -> scan(true)),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        right.addView(head, new LinearLayout.LayoutParams(-1, dp(52)));

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> onItem(pos));
        right.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 1f);
        rlp.leftMargin = dp(14);
        body.addView(right, rlp);

        setContentView(root);
        // The list starts hidden while a song is loaded (EJECT shows it); open when there is none.
        showList(!MusicService.hasSong(this));
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            scan(false);
        } else {
            listInfo.setText("Permite el acceso a tus archivos");
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) scan(false);
        else listInfo.setText("Sin permiso para leer la música");
    }

    @Override
    protected void onResume() {
        super.onResume();
        MusicService.addListener(this);
        onMusicChanged();
    }

    @Override
    protected void onPause() {
        super.onPause();
        MusicService.removeListener(this);
    }

    // ---------- Library ----------

    private void scan(boolean force) {
        if (scanning) return;
        if (cache != null && !force) {
            folders = cache;
            openCurrentOrFolders();
            return;
        }
        scanning = true;
        listTitle.setText("Carpetas");
        listInfo.setText("Buscando música…");
        folderBack.setVisibility(View.INVISIBLE);
        new Thread(() -> {
            List<MusicLibrary.Folder> found = MusicLibrary.scan(getApplicationContext());
            handler.post(() -> {
                scanning = false;
                cache = found;
                folders = found;
                if (!isDestroyed()) openCurrentOrFolders();
            });
        }, "music-scan").start();
    }

    /** Opens the folder of the song that is playing, else the folder list. */
    private void openCurrentOrFolders() {
        String cur = MusicService.state(this).path;
        if (!cur.isEmpty()) {
            File parent = new File(cur).getParentFile();
            for (MusicLibrary.Folder f : folders) {
                if (f.dir.equals(parent)) {
                    showFolder(f);
                    return;
                }
            }
        }
        showFolders();
    }

    private void showFolders() {
        openFolder = null;
        folderBack.setVisibility(View.INVISIBLE);
        listTitle.setText("Carpetas");
        int songs = 0;
        for (MusicLibrary.Folder f : folders) songs += f.songs.size();
        listInfo.setText(folders.isEmpty()
                ? "No se encontró música. Conecta la memoria USB y toca ⟳"
                : folders.size() + " carpetas · " + songs + " canciones");
        adapter.notifyDataSetChanged();
        list.setSelection(0);
    }

    private void showFolder(MusicLibrary.Folder f) {
        openFolder = f;
        folderBack.setVisibility(View.VISIBLE);
        listTitle.setText(f.name);
        listInfo.setText(f.volume + " · " + f.songs.size() + " canciones");
        adapter.notifyDataSetChanged();
        String cur = MusicService.state(this).path;
        for (int i = 0; i < f.songs.size(); i++) {
            if (f.songs.get(i).getPath().equals(cur)) {
                list.setSelection(Math.max(0, i - 2));
                return;
            }
        }
        list.setSelection(0);
    }

    private void onItem(int pos) {
        if (openFolder == null) {
            showFolder(folders.get(pos));
        } else {
            MusicService.playQueue(this, openFolder.songs, pos);
            handler.postDelayed(() -> showList(false), 600); // back to the full cassette
        }
    }

    // ---------- Player state ----------

    @Override
    public void onMusicChanged() {
        MusicService.State s = MusicService.state(this);
        cassette.set(s);
        keyPlay.setLatched(s.playing);
        keyPause.setLatched(!s.playing && s.hasSong && s.position > 0);
        keyEject.setLatched(right.getVisibility() == View.VISIBLE);
        shuffleBtn.setAlpha(s.shuffle ? 1f : 0.4f);
        shuffleBtn.setColorFilter(s.shuffle ? 0xFF00E5FF : Color.WHITE);
        repeatBtn.setImageResource(s.repeat == MusicService.REPEAT_ONE ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        repeatBtn.setAlpha(s.repeat == MusicService.REPEAT_OFF ? 0.4f : 1f);
        repeatBtn.setColorFilter(s.repeat == MusicService.REPEAT_OFF ? Color.WHITE : 0xFF00E5FF);
        seek.setMax(Math.max(1, s.duration));
        if (!dragging) {
            seek.setProgress(s.position);
            timeNow.setText(CassetteView.fmt(s.position));
        }
        timeTotal.setText(CassetteView.fmt(s.duration));
        if (openFolder != null) adapter.notifyDataSetChanged(); // highlight the playing song
    }

    // ---------- List ----------

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return openFolder == null ? folders.size() : openFolder.songs.size();
        }

        @Override
        public Object getItem(int i) {
            return null;
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Row row = convert instanceof Row ? (Row) convert : new Row();
            if (openFolder == null) {
                MusicLibrary.Folder f = folders.get(i);
                row.bind(R.drawable.ic_folder, f.name, f.volume + " · " + f.songs.size() + " canciones", false);
            } else {
                File song = openFolder.songs.get(i);
                boolean current = song.getPath().equals(MusicService.state(PlayerActivity.this).path);
                row.bind(current ? R.drawable.ic_play : R.drawable.ic_music, MusicLibrary.baseName(song),
                        MusicLibrary.type(song), current);
            }
            return row;
        }
    }

    private final class Row extends LinearLayout {
        final ImageView icon = new ImageView(PlayerActivity.this);
        final TextView name = Ui.text(PlayerActivity.this, "", 16, Color.WHITE);
        final TextView sub = Ui.text(PlayerActivity.this, "", 12, 0x80FFFFFF);
        final GradientDrawable bg = new GradientDrawable();

        Row() {
            super(PlayerActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(12), dp(10), dp(12), dp(10));
            bg.setCornerRadius(dp(12));
            setBackground(bg);
            addView(icon, new LayoutParams(dp(24), dp(24)));
            LinearLayout texts = new LinearLayout(PlayerActivity.this);
            texts.setOrientation(VERTICAL);
            texts.setPadding(dp(14), 0, 0, 0);
            name.setGravity(Gravity.START);
            sub.setGravity(Gravity.START);
            texts.addView(name);
            texts.addView(sub);
            addView(texts, new LayoutParams(0, -2, 1f));
        }

        void bind(int iconRes, String n, String s, boolean current) {
            icon.setImageResource(iconRes);
            icon.setColorFilter(current ? 0xFF00E5FF : 0xB3FFFFFF);
            name.setText(n);
            name.setTextColor(current ? 0xFF00E5FF : Color.WHITE);
            sub.setText(s);
            bg.setColor(current ? 0x2200E5FF : 0x00000000);
        }
    }

    private DeckKey key(LinearLayout row, int kind, String label) {
        DeckKey k = new DeckKey(this, kind, label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        if (row.getChildCount() > 0) lp.leftMargin = dp(5);
        row.addView(k, lp);
        return k;
    }

    private void showList(boolean show) {
        right.setVisibility(show ? View.VISIBLE : View.GONE);
        if (keyEject != null) keyEject.setLatched(show);
    }

    private void skipBy(int ms) {
        MusicService.State s = MusicService.state(this);
        int to = Math.max(0, Math.min(s.position + ms, Math.max(0, s.duration - 1000)));
        MusicService.seek(this, to);
    }

    /** Round chrome knob that opens the equalizer. */
    private View eqKnob() {
        ImageView k = new ImageView(this);
        k.setImageResource(R.drawable.ic_eq);
        k.setColorFilter(0xFF1C1C22);
        k.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(40));
        g.setGradientCenter(0.35f, 0.3f);
        g.setColors(new int[]{0xFFFFFFFF, 0xFFC8C8D0, 0xFF6E6E78});
        g.setStroke(dp(2), 0xFF2A2A30);
        k.setBackground(g);
        Ui.pressable(k, 0.9f);
        k.setOnClickListener(v -> openEqualizer());
        return k;
    }

    /** NWD units: the launcher's own equalizer. Others: the system / factory equalizer for our player. */
    private void openEqualizer() {
        if (Profile.isNwd(this)) {
            startActivity(new android.content.Intent(this, EqActivity.class));
            return;
        }
        android.content.Intent eq = new android.content.Intent(
                android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                .putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, getPackageName())
                .putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE,
                        android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC);
        int session = MusicService.audioSession();
        if (session != 0) eq.putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, session);
        try {
            startActivityForResult(eq, 2);
            return;
        } catch (Exception ignored) { }
        android.content.pm.PackageManager pm = getPackageManager();
        android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String text = (ri.loadLabel(pm) + " " + ri.activityInfo.packageName).toLowerCase();
            if (text.contains("ecualiz") || text.contains("equaliz") || text.contains("dsp")
                    || text.contains("sonido") || text.contains("audio")) {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
        }
        android.widget.Toast.makeText(this, "No se encontró un ecualizador", android.widget.Toast.LENGTH_SHORT).show();
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
