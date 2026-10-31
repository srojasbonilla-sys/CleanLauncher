package com.simple.carlauncher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;

/**
 * NWD head units force-stop every app that is not on their whitelists when the car is turned
 * off ("one-key clean"). A force-stopped app loses its accessibility service, its background
 * services and the resume of the music. This adds the launcher to both lists, like the
 * original author did on his radio, when the files can be written from the app.
 */
final class NwdWhitelist {

    private NwdWhitelist() { }

    private static final String TAG = "NwdWhitelist";
    static final String DIR = "/nwdappconfig/app/";
    private static final String ONE_CLEAR = DIR + "OneClearConfig.xml";
    private static final String BACKGROUND = DIR + "BackgroundManager.ini";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset LATIN = Charset.forName("ISO-8859-1"); // keeps the GBK comments intact

    static boolean isListed(Context c) {
        String pkg = c.getPackageName();
        String a = read(ONE_CLEAR, UTF8), b = read(BACKGROUND, LATIN);
        return a != null && a.contains("\"powernotkill." + pkg + "\"")
                && b != null && b.contains("white:" + pkg);
    }

    /** Tries to add the launcher to both lists. Returns a short report. */
    static String tryAdd(Context c) {
        if (!new File(DIR).isDirectory()) return "no es un radio NWD";
        String pkg = c.getPackageName();
        StringBuilder out = new StringBuilder();
        // OneClearConfig.xml: whitelist entry with the "do not kill at power off" prefix
        String xml = read(ONE_CLEAR, UTF8);
        if (xml == null) {
            out.append("OneClearConfig: no se pudo leer. ");
        } else if (xml.contains("\"powernotkill." + pkg + "\"")) {
            out.append("OneClearConfig: ya estaba ✓. ");
        } else {
            String entry = "\t<ProcessItem PackName=\"powernotkill." + pkg + "\">\n\t</ProcessItem>\n\n\t";
            int at = xml.indexOf("<ForceSleepKill");
            if (at < 0) at = xml.lastIndexOf("</ProcessList>");
            String updated = at < 0 ? null : xml.substring(0, at) + entry.substring(1) + xml.substring(at);
            out.append(updated != null && write(c, ONE_CLEAR, updated, UTF8)
                    ? "OneClearConfig: agregado ✓. " : "OneClearConfig: sin permiso para escribir ✗. ");
        }
        // BackgroundManager.ini: plain whitelist line
        String ini = read(BACKGROUND, LATIN);
        if (ini == null) {
            out.append("BackgroundManager: no se pudo leer.");
        } else if (ini.contains("white:" + pkg)) {
            out.append("BackgroundManager: ya estaba ✓.");
        } else {
            String updated = ini + (ini.endsWith("\n") ? "" : "\n") + "white:" + pkg + "\n";
            out.append(write(c, BACKGROUND, updated, LATIN)
                    ? "BackgroundManager: agregado ✓." : "BackgroundManager: sin permiso para escribir ✗.");
        }
        Log.d(TAG, out.toString());
        return out.toString();
    }

    private static String read(String path, Charset cs) {
        File f = new File(path);
        if (!f.canRead()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(b, 0, n, cs);
        } catch (Exception e) {
            return null;
        }
    }

    /** Keeps a copy of the original in the app's folder, then rewrites the file. */
    private static boolean write(Context c, String path, String text, Charset cs) {
        File f = new File(path);
        if (!f.canWrite()) return false;
        try {
            File backup = new File(c.getFilesDir(), f.getName() + ".bak");
            if (!backup.exists()) {
                String orig = read(path, cs);
                if (orig != null) {
                    try (FileOutputStream o = new FileOutputStream(backup)) {
                        o.write(orig.getBytes(cs));
                    }
                }
            }
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(text.getBytes(cs));
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cannot write " + path, e);
            return false;
        }
    }

    /** The commands to type inside "adb shell" from a computer when the app cannot write the files. */
    static String adbCommands(Context c) {
        String pkg = c.getPackageName();
        return "adb shell\n"
                + "echo white:" + pkg + " >> " + BACKGROUND + "\n"
                + "sed -i 's#</ProcessList>#<ProcessItem PackName=\"powernotkill." + pkg
                + "\"></ProcessItem></ProcessList>#' " + ONE_CLEAR + "\n"
                + "cmd package set-home-activity " + pkg + "/.MainActivity\n"
                + "exit";
    }
}
