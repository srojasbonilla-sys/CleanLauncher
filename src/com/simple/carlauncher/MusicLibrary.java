package com.simple.carlauncher;

import android.content.Context;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds music on every storage the head unit has mounted (USB sticks, SD cards, internal
 * memory) by walking the folders directly: on these units USB sticks are often not indexed by
 * the media database. Songs are grouped by the folder they are in.
 */
final class MusicLibrary {

    private MusicLibrary() { }

    static final String[] EXTENSIONS = {".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus", ".wma", ".amr"};
    private static final int MAX_DEPTH = 7;
    private static final int MAX_FILES = 20000;

    static final class Folder {
        final File dir;
        final String name;     // folder name ("Raíz" for the top of a drive)
        final String volume;   // "USB", "Tarjeta SD", "Memoria interna"…
        final List<File> songs = new ArrayList<>();

        Folder(File dir, String name, String volume) {
            this.dir = dir;
            this.name = name;
            this.volume = volume;
        }
    }

    private static final class Root {
        final File dir;
        final String label;

        Root(File dir, String label) {
            this.dir = dir;
            this.label = label;
        }
    }

    /** Scans all storages. Slow on big drives: call it off the main thread. */
    static List<Folder> scan(Context c) {
        Map<String, Folder> folders = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        int[] count = {0};
        for (Root r : roots(c)) {
            walk(r.dir, r, 0, folders, seen, count);
        }
        List<Folder> out = new ArrayList<>();
        for (Folder f : folders.values()) {
            if (f.songs.isEmpty()) continue;
            Collections.sort(f.songs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            out.add(f);
        }
        Collections.sort(out, (a, b) -> {
            int v = a.volume.compareTo(b.volume);
            return v != 0 ? v : a.dir.getPath().compareToIgnoreCase(b.dir.getPath());
        });
        return out;
    }

    private static void walk(File dir, Root root, int depth, Map<String, Folder> folders, Set<String> seen, int[] count) {
        if (depth > MAX_DEPTH || count[0] > MAX_FILES) return;
        String canon;
        try {
            canon = dir.getCanonicalPath();
        } catch (Exception e) {
            return;
        }
        if (!seen.add(canon)) return; // same folder reached through another path
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth == 0 && (n.equals("Android") || n.equals("LOST.DIR"))) continue;
                walk(f, root, depth + 1, folders, seen, count);
            } else if (isAudio(n)) {
                count[0]++;
                Folder folder = folders.get(canon);
                if (folder == null) {
                    String name = depth == 0 ? "Raíz" : dir.getName();
                    folder = new Folder(dir, name, root.label);
                    folders.put(canon, folder);
                }
                folder.songs.add(f);
            }
        }
    }

    static boolean isAudio(String name) {
        String n = name.toLowerCase(Locale.US);
        for (String e : EXTENSIONS) if (n.endsWith(e)) return true;
        return false;
    }

    /** "MP3", "FLAC"… from the file name. */
    static String type(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "AUDIO" : n.substring(dot + 1).toUpperCase(Locale.US);
    }

    /** File name without the extension, as a fallback title. */
    static String baseName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    // ---------- Storage roots ----------

    private static List<Root> roots(Context c) {
        List<Root> out = new ArrayList<>();
        Set<String> added = new HashSet<>();
        // Named volumes from the system (Android 7+): gives "USB" / "Tarjeta SD" labels.
        try {
            StorageManager sm = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
            for (StorageVolume v : sm.getStorageVolumes()) {
                File dir = volumeDir(v);
                if (dir == null) continue;
                String desc = v.getDescription(c);
                String label = v.isPrimary() ? "Memoria interna" : labelFor(dir.getPath(), desc);
                add(out, added, dir, label);
            }
        } catch (Exception ignored) { }
        add(out, added, Environment.getExternalStorageDirectory(), "Memoria interna");
        // Mount points used by Chinese head units that the system may not list.
        for (String base : new String[]{"/storage", "/mnt/media_rw", "/mnt"}) {
            File[] kids = new File(base).listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                String n = k.getName().toLowerCase(Locale.US);
                if (n.equals("emulated") || n.equals("self") || n.equals("runtime") || n.equals("secure")
                        || n.equals("asec") || n.equals("obb") || n.equals("user") || n.equals("media_rw")
                        || n.equals("expand") || n.equals("appfuse")) continue;
                if (base.equals("/mnt") && !(n.contains("usb") || n.contains("udisk") || n.contains("sd")
                        || n.contains("ext") || n.contains("disk"))) continue;
                add(out, added, k, labelFor(k.getPath(), null));
            }
        }
        return out;
    }

    private static void add(List<Root> out, Set<String> added, File dir, String label) {
        if (dir == null || !dir.isDirectory() || !dir.canRead()) return;
        try {
            if (!added.add(dir.getCanonicalPath())) return;
        } catch (Exception e) {
            return;
        }
        out.add(new Root(dir, label));
    }

    private static String labelFor(String path, String desc) {
        String p = (path + " " + (desc == null ? "" : desc)).toLowerCase(Locale.US);
        if (p.contains("usb") || p.contains("udisk")) return "USB";
        if (p.contains("sd")) return "Tarjeta SD";
        return desc != null && !desc.isEmpty() ? desc : "Memoria";
    }

    /** StorageVolume.getPath() is hidden before Android 11; reach it by reflection. */
    private static File volumeDir(StorageVolume v) {
        try {
            Object p = StorageVolume.class.getMethod("getPath").invoke(v);
            return p == null ? null : new File(p.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
