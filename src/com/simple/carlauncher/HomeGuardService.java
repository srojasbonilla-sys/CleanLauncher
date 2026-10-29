package com.simple.carlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Some head units send the Home button (and the car's power-on) straight to their factory
 * launcher, even when another app is chosen as the default home. This accessibility service
 * notices when any other home screen comes to the front and replaces it with this launcher.
 * It only looks at which app opened a window; it never reads what is on screen.
 */
public class HomeGuardService extends AccessibilityService {

    private Set<String> otherHomes = new HashSet<>();
    private long lastJump;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        otherHomes = findOtherHomes(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (!otherHomes.contains(pkg)) return;
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

    /** Every installed home screen except this one and the system's setup placeholder. */
    static Set<String> findOtherHomes(Context c) {
        Set<String> out = new HashSet<>();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(i, PackageManager.MATCH_ALL)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || pkg.equals("com.android.provision")
                    || pkg.equals("com.android.settings")) continue;
            out.add(pkg);
        }
        return out;
    }

    /** Whether the user has turned this service on in the accessibility settings. */
    static boolean isEnabled(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(c.getPackageName() + "/");
    }
}
