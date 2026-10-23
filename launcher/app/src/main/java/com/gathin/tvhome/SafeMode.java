package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Crash safety net (hisense-home fork, after Free My TV's Watchdog). Each crash of Home is recorded; three within five
 * minutes put Home in safe mode for half an hour: the plain launcher without Continue watching, weather, the profile
 * picker or the frosted glass, so a bad feature can never leave the TV without a working Home. A new version (an
 * update, often the fix) clears it at once.
 */
final class SafeMode {
    private static final String PREFS = "safe_mode";
    private static final long WINDOW_MS = 5 * 60_000L, DURATION_MS = 30 * 60_000L;
    private static final int CRASHES = 3;
    private static boolean installed;
    private static Boolean cached;

    private SafeMode() { }

    /** Records every uncaught crash before the system kills the process. Call first thing in Home. */
    static synchronized void install(Context ctx) {
        if (installed) return;
        installed = true;
        Context app = ctx.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                record(app, System.currentTimeMillis());
            } catch (Throwable ignored) {
                // Never let the safety net itself get in the way of the crash report.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static void record(Context ctx, long now) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String log = p.getString("crashes", "");
        StringBuilder kept = new StringBuilder();
        int recent = 1;
        for (String t : log.isEmpty() ? new String[0] : log.split(",")) {
            try {
                if (now - Long.parseLong(t) < WINDOW_MS) {
                    kept.append(t).append(',');
                    recent++;
                }
            } catch (NumberFormatException ignored) {
                // Dropped.
            }
        }
        kept.append(now);
        SharedPreferences.Editor e = p.edit().putString("crashes", kept.toString());
        if (recent >= CRASHES) e.putLong("until", now + DURATION_MS).putLong("version", version(ctx));
        e.commit();   // synchronous: the process is about to die
    }

    /** Safe mode is on: skip the optional features. */
    static boolean on(Context ctx) {
        if (cached != null) return cached;
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean active = System.currentTimeMillis() < p.getLong("until", 0) && p.getLong("version", -1) == version(ctx);
        cached = active;
        return active;
    }

    private static long version(Context ctx) {
        try {
            // Changes with every install, so an update (often the fix) ends safe mode.
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            return 0;
        }
    }
}
