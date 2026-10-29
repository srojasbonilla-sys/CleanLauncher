package com.simple.carlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Some head units send the Home button (and the car's power-on) straight to their factory
 * menu, even when another app is chosen as the default home. This accessibility service notices
 * when that menu comes to the front and replaces it with this launcher.
 *
 * The factory menu is recognised as: any installed home screen, any app whose name looks like a
 * launcher, or an app the user picked in Configurar from the list of recently opened screens.
 * The service only sees which app opened a window; it never reads what is on screen.
 */
public class HomeGuardService extends AccessibilityService {

    private static final int RECENT_MAX = 12;
    /** Never treated as the factory menu (system UI, keyboards, this app, settings). */
    private static final Set<String> NEVER = new HashSet<>(Arrays.asList(
            "android", "com.android.systemui", "com.android.settings", "com.android.provision",
            "com.android.packageinstaller", "com.google.android.packageinstaller"));

    private Set<String> autoHomes = new HashSet<>();
    private long lastJump;
    private String lastPkg;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        autoHomes = findOtherHomes(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (pkg.equals(getPackageName()) || NEVER.contains(pkg)) {
            lastPkg = pkg;
            return;
        }
        if (!pkg.equals(lastPkg)) remember(this, pkg);
        lastPkg = pkg;
        if (!autoHomes.contains(pkg) && !chosen(this).contains(pkg)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastJump < 800) return; // one jump per press
        lastJump = now;
        Intent home = new Intent(this, MainActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(home);
        } catch (Exception ignored) { }
    }

    @Override
    public void onInterrupt() { }

    // ---------- Which apps count as the factory menu ----------

    /** Installed home screens plus apps whose name or package looks like a launcher. */
    static Set<String> findOtherHomes(Context c) {
        Set<String> out = new HashSet<>();
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : pm.queryIntentActivities(i, PackageManager.MATCH_ALL)) {
            out.add(ri.activityInfo.packageName);
        }
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            String text = (ai.packageName + " " + pm.getApplicationLabel(ai)).toLowerCase();
            if (text.contains("launcher") || text.contains("桌面")) out.add(ai.packageName);
        }
        out.remove(c.getPackageName());
        out.removeAll(NEVER);
        return out;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home_guard", Context.MODE_PRIVATE);
    }

    /** Apps the user marked as the factory menu. */
    static Set<String> chosen(Context c) {
        return new HashSet<>(prefs(c).getStringSet("chosen", new HashSet<>()));
    }

    static void setChosen(Context c, Set<String> pkgs) {
        prefs(c).edit().putStringSet("chosen", new HashSet<>(pkgs)).apply();
    }

    /** Recently opened apps, newest first (for the picker in Configurar). */
    static List<String> recent(Context c) {
        String s = prefs(c).getString("recent", "");
        List<String> out = new ArrayList<>();
        if (!s.isEmpty()) out.addAll(Arrays.asList(s.split(",")));
        return out;
    }

    private static void remember(Context c, String pkg) {
        List<String> list = recent(c);
        list.remove(pkg);
        list.add(0, pkg);
        while (list.size() > RECENT_MAX) list.remove(list.size() - 1);
        prefs(c).edit().putString("recent", TextUtils.join(",", list)).apply();
    }

    /** Whether the user has turned this service on in the accessibility settings. */
    static boolean isEnabled(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(c.getPackageName() + "/");
    }
}
