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
        prefs(this).edit().putLong("connected_at", System.currentTimeMillis()).apply();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        prefs(this).edit().putLong("last_event", System.currentTimeMillis()).apply();
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
        prefs(this).edit().putString("last_replaced", pkg)
                .putLong("last_replaced_at", System.currentTimeMillis()).apply();
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

    /** Plain-language report of how the Home button is handled, for the diagnosis screen. */
    static String report(Context c) {
        PackageManager pm = c.getPackageManager();
        StringBuilder sb = new StringBuilder();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo def = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        String defPkg = def == null || def.activityInfo == null ? "?" : def.activityInfo.packageName;
        sb.append("1) Pantalla de inicio predeterminada:\n   ").append(label(pm, defPkg)).append(" (").append(defPkg).append(")")
                .append(defPkg.equals(c.getPackageName()) ? "  ✓" : "  ✗ debería ser Inicio").append("\n\n");
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        long conn = p.getLong("connected_at", 0), ev = p.getLong("last_event", 0);
        sb.append("2) Servicio \"Botón ○ y encendido\": ").append(isEnabled(c) ? "activado ✓" : "DESACTIVADO ✗").append("\n");
        sb.append("   Conectado: ").append(conn == 0 ? "nunca ✗" : ago(now - conn)).append("\n");
        sb.append("   Último aviso recibido: ").append(ev == 0 ? "nunca ✗" : ago(now - ev)).append("\n");
        String rep = p.getString("last_replaced", "");
        if (!rep.isEmpty()) {
            sb.append("   Último menú reemplazado: ").append(label(pm, rep)).append(", ")
                    .append(ago(now - p.getLong("last_replaced_at", now))).append("\n");
        }
        sb.append("\n3) Otros launchers que reemplaza:\n");
        for (String h : findOtherHomes(c)) sb.append("   • ").append(label(pm, h)).append(" (").append(h).append(")\n");
        Set<String> ch = chosen(c);
        if (!ch.isEmpty()) {
            sb.append("   Marcados a mano:\n");
            for (String h : ch) sb.append("   • ").append(label(pm, h)).append(" (").append(h).append(")\n");
        }
        if (new java.io.File(NwdWhitelist.DIR).isDirectory()) {
            sb.append("\n5) Protección contra el cierre al apagar (NWD): ")
                    .append(NwdWhitelist.isListed(c) ? "sí ✓" : "NO ✗ (el radio cierra Inicio y apaga el servicio)").append("\n");
        }
        sb.append("\n4) Últimas pantallas abiertas:\n");
        List<String> rec = recent(c);
        if (rec.isEmpty()) sb.append("   (ninguna: el servicio no está funcionando)\n");
        for (String r : rec) sb.append("   • ").append(label(pm, r)).append(" (").append(r).append(")\n");
        return sb.toString();
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return "hace " + s + " s";
        if (s < 3600) return "hace " + (s / 60) + " min";
        if (s < 86400) return "hace " + (s / 3600) + " h";
        return "hace " + (s / 86400) + " días";
    }

    /** Whether the user has turned this service on in the accessibility settings. */
    static boolean isEnabled(Context c) {
        // ask the system which services are really running (the setting text can be stale)
        android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        for (android.accessibilityservice.AccessibilityServiceInfo i :
                am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (i.getResolveInfo() != null && i.getResolveInfo().serviceInfo != null
                    && c.getPackageName().equals(i.getResolveInfo().serviceInfo.packageName)) return true;
        }
        return false;
    }
}
