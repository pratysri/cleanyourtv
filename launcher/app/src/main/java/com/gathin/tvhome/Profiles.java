package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Household profiles (hisense-home fork): config.xml's {@code profiles} names the people who share the TV, and
 * each keeps their own tile order and dock (Order's file). The first profile keeps the original order.txt, so an
 * order saved before profiles existed stays with it. An empty list turns profiles off.
 */
final class Profiles {
    private static final String PREFS = "profiles";
    private static final String CURRENT = "current";

    private Profiles() { }

    static String[] names(Context ctx) {
        return ctx.getResources().getStringArray(R.array.profiles);
    }

    static boolean enabled(Context ctx) {
        return names(ctx).length > 1;
    }

    static int current(Context ctx) {
        int n = names(ctx).length;
        int i = prefs(ctx).getInt(CURRENT, 0);
        return n == 0 ? 0 : Math.max(0, Math.min(i, n - 1));
    }

    static String currentName(Context ctx) {
        String[] names = names(ctx);
        return names.length == 0 ? "" : names[current(ctx)];
    }

    /** Switches to the next person and returns their name. */
    static String next(Context ctx) {
        String[] names = names(ctx);
        if (names.length == 0) return "";
        int i = (current(ctx) + 1) % names.length;
        set(ctx, i);
        return names[i];
    }

    /** Makes person i the active profile, and logs when, for Continue watching. */
    static void set(Context ctx, int i) {
        ensureLog(ctx);
        prefs(ctx).edit().putInt(CURRENT, i).commit();
        appendLog(ctx, System.currentTimeMillis(), i);
    }

    /** The accent colour of person i, for the picker and the profile pill. */
    static int color(int i) {
        int[] palette = {0xFF3D7BFF, 0xFFFF7A45, 0xFF2ECC9A, 0xFFB46CFF, 0xFFFFC145};
        return palette[Math.floorMod(i, palette.length)];
    }

    /** The current person's own dock from profile_docks ("Name=pkg,pkg,..."), or null to keep dock_apps. */
    static java.util.List<String> dockApps(Context ctx) {
        if (!enabled(ctx)) return null;
        String who = currentName(ctx);
        for (String line : ctx.getResources().getStringArray(R.array.profile_docks)) {
            int eq = line.indexOf('=');
            if (eq <= 0 || !line.substring(0, eq).trim().equalsIgnoreCase(who)) continue;
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String pkg : line.substring(eq + 1).split(",")) if (!pkg.trim().isEmpty()) out.add(pkg.trim());
            return out;
        }
        return null;
    }

    // ---- who was watching when: personal Continue watching --------------------------------------------

    private static final String LOG = "log";
    private static final int LOG_MAX = 300;

    /** Starts the log the first time profiles are used, so rows from before stay shared. */
    static void ensureLog(Context ctx) {
        if (prefs(ctx).getString(LOG, "").isEmpty()) appendLog(ctx, System.currentTimeMillis(), current(ctx));
    }

    private static void appendLog(Context ctx, long when, int who) {
        String log = prefs(ctx).getString(LOG, "");
        String[] parts = log.isEmpty() ? new String[0] : log.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, parts.length - LOG_MAX + 1); i < parts.length; i++) sb.append(parts[i]).append(',');
        sb.append(when).append(':').append(who);
        prefs(ctx).edit().putString(LOG, sb.toString()).apply();
    }

    /** Who was active at {@code when}, or -1 when that is before profiles were in use (or unknown). */
    static int activeAt(Context ctx, long when) {
        if (when <= 0) return -1;
        String log = prefs(ctx).getString(LOG, "");
        int who = -1;
        for (String part : log.isEmpty() ? new String[0] : log.split(",")) {
            int c = part.indexOf(':');
            if (c <= 0) continue;
            try {
                if (Long.parseLong(part.substring(0, c)) > when) break;
                who = Integer.parseInt(part.substring(c + 1));
            } catch (NumberFormatException ignored) {
                // A damaged entry is skipped.
            }
        }
        return who;
    }

    private static final String OWNERS = "continue_owners";

    /** Whose Continue watching a show belongs to: a "Not mine" choice for this viewing, else who was active then. */
    static int owner(Context ctx, String pkg, String title, long lastWatched) {
        String saved = ctx.getSharedPreferences(OWNERS, Context.MODE_PRIVATE).getString(showKey(pkg, title), null);
        if (saved != null) {
            int c = saved.indexOf(':');
            try {
                // A manual choice holds until the show is watched again.
                if (c > 0 && Long.parseLong(saved.substring(0, c)) == lastWatched) {
                    return Integer.parseInt(saved.substring(c + 1));
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the log.
            }
        }
        return activeAt(ctx, lastWatched);
    }

    /** Hands a show to person {@code who} until it is next watched. */
    static void setOwner(Context ctx, String pkg, String title, long lastWatched, int who) {
        ctx.getSharedPreferences(OWNERS, Context.MODE_PRIVATE).edit()
                .putString(showKey(pkg, title), lastWatched + ":" + who).apply();
    }

    /** Shown in the current person's row: theirs, or shared (watched before profiles, or owner unknown). */
    static boolean visible(Context ctx, String pkg, String title, long lastWatched) {
        if (!enabled(ctx)) return true;
        int owner = owner(ctx, pkg, title, lastWatched);
        return owner < 0 || owner == current(ctx);
    }

    private static String showKey(String pkg, String title) {
        return pkg + "|" + title.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** The tile order file for the current profile: order.txt for the first, order-<n>.txt for the others. */
    static String orderFileName(Context ctx, String base) {
        int i = enabled(ctx) ? current(ctx) : 0;
        if (i == 0) return base;
        int dot = base.lastIndexOf('.');
        return dot < 0 ? base + "-" + i : base.substring(0, dot) + "-" + i + base.substring(dot);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
