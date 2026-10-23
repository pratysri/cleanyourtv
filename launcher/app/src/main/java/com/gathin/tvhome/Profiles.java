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
        prefs(ctx).edit().putInt(CURRENT, i).commit();
        return names[i];
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
