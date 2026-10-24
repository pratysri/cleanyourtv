package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Choices made on the TV in Home settings (hisense-home fork). Each falls back to the build's config.xml, so a TV
 * where nothing was changed behaves exactly as built. Saving bumps Config's settings revision, which makes Home
 * rebuild when it comes back.
 */
final class HomePrefs {
    static final String NAME = "home_prefs";

    private HomePrefs() { }

    private static SharedPreferences p(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** Marks Home for a rebuild when it next resumes (the same revision Launcher settings uses). */
    static void changed(Context ctx) {
        SharedPreferences c = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        c.edit().putLong(Config.SETTINGS_REVISION_KEY, c.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    // ---- weather and the sky -----------------------------------------------------------------------

    static String latitude(Context ctx) {
        return p(ctx).getString("latitude", ctx.getString(R.string.latitude));
    }

    static String longitude(Context ctx) {
        return p(ctx).getString("longitude", ctx.getString(R.string.longitude));
    }

    static String city(Context ctx) {
        return p(ctx).getString("city", "Sunnyvale");
    }

    static void setPlace(Context ctx, String city, double lat, double lon) {
        p(ctx).edit().putString("city", city)
                .putString("latitude", String.format(java.util.Locale.US, "%.4f", lat))
                .putString("longitude", String.format(java.util.Locale.US, "%.4f", lon)).commit();
        changed(ctx);
    }

    /**
     * °F or °C for whoever is watching: each profile keeps its own (profile_units in config.xml, then what they
     * chose on the TV), and without profiles one setting for the TV.
     */
    static boolean fahrenheit(Context ctx) {
        String key = unitKey(ctx);
        if (p(ctx).contains(key)) return p(ctx).getBoolean(key, true);
        if (Profiles.enabled(ctx)) {
            String who = Profiles.currentName(ctx);
            for (String entry : ctx.getResources().getStringArray(R.array.profile_units)) {
                int eq = entry.indexOf('=');
                if (eq > 0 && entry.substring(0, eq).trim().equalsIgnoreCase(who)) {
                    return entry.substring(eq + 1).trim().equalsIgnoreCase("F");
                }
            }
        }
        return p(ctx).getBoolean("fahrenheit", ctx.getResources().getBoolean(R.bool.weather_fahrenheit));
    }

    private static String unitKey(Context ctx) {
        return Profiles.enabled(ctx) ? "fahrenheit_" + Profiles.currentName(ctx) : "fahrenheit";
    }

    static void setFahrenheit(Context ctx, boolean on) {
        setFahrenheitNow(ctx, on);
        changed(ctx);
    }

    /** For the weather panel: Home swaps its weather line itself, so no rebuild is asked for. */
    static void setFahrenheitNow(Context ctx, boolean on) {
        p(ctx).edit().putBoolean(unitKey(ctx), on).commit();
    }

    // ---- Home --------------------------------------------------------------------------------------

    /** 0: the build's fixed greeting; 1: "Welcome back, <who>"; 2: good morning/evening; 3: none. */
    static int greetingStyle(Context ctx) {
        return p(ctx).getInt("greeting_style", 0);
    }

    static void setGreetingStyle(Context ctx, int style) {
        p(ctx).edit().putInt("greeting_style", style).commit();
        changed(ctx);
    }

    static boolean pickerOnWake(Context ctx) {
        return p(ctx).getBoolean("picker_on_wake", true);
    }

    static void setPickerOnWake(Context ctx, boolean on) {
        p(ctx).edit().putBoolean("picker_on_wake", on).commit();
    }

    static boolean continueWatching(Context ctx) {
        return p(ctx).getBoolean("continue_watching", ctx.getResources().getBoolean(R.bool.continue_watching));
    }

    static void setContinueWatching(Context ctx, boolean on) {
        p(ctx).edit().putBoolean("continue_watching", on).commit();
        changed(ctx);
    }

    // ---- apps: Home, More Apps or hidden -----------------------------------------------------------

    static final int ON_HOME = 0, IN_FOLDER = 1, HIDDEN = 2;

    static Set<String> folderApps(Context ctx) {
        Set<String> saved = p(ctx).getStringSet("folder_apps", null);
        if (saved != null) return new LinkedHashSet<>(saved);
        return new LinkedHashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.folder_apps)));
    }

    static Set<String> hiddenApps(Context ctx) {
        return new LinkedHashSet<>(p(ctx).getStringSet("hidden_apps", Collections.emptySet()));
    }

    static int placeOf(Context ctx, String pkg) {
        if (hiddenApps(ctx).contains(pkg)) return HIDDEN;
        return folderApps(ctx).contains(pkg) ? IN_FOLDER : ON_HOME;
    }

    static void setPlace(Context ctx, String pkg, int place) {
        Set<String> folder = folderApps(ctx), hidden = hiddenApps(ctx);
        folder.remove(pkg);
        hidden.remove(pkg);
        if (place == IN_FOLDER) folder.add(pkg);
        if (place == HIDDEN) hidden.add(pkg);
        p(ctx).edit().putStringSet("folder_apps", folder).putStringSet("hidden_apps", hidden).commit();
        changed(ctx);
    }

    // ---- each person's dock ------------------------------------------------------------------------

    /** The person's dock chosen on the TV, or null for the build's (profile_docks, then dock_apps). */
    static List<String> dock(Context ctx, int person) {
        String s = p(ctx).getString("dock_" + person, null);
        if (s == null) return null;
        List<String> out = new ArrayList<>();
        for (String pkg : s.split(",")) if (!pkg.trim().isEmpty()) out.add(pkg.trim());
        return out;
    }

    static void setDock(Context ctx, int person, List<String> pkgs) {
        p(ctx).edit().putString("dock_" + person, String.join(",", pkgs)).commit();
        changed(ctx);
    }

    // ---- Search: services nobody here pays for -----------------------------------------------------

    static Set<String> unpaidServices(Context ctx) {
        return new LinkedHashSet<>(p(ctx).getStringSet("unpaid_services", Collections.emptySet()));
    }

    static void setPaid(Context ctx, String pkg, boolean paid) {
        Set<String> unpaid = unpaidServices(ctx);
        if (paid) unpaid.remove(pkg);
        else unpaid.add(pkg);
        p(ctx).edit().putStringSet("unpaid_services", unpaid).commit();
    }

    // ---- screensaver -------------------------------------------------------------------------------

    static int screensaverSeconds(Context ctx) {
        return p(ctx).getInt("screensaver_seconds", 45);
    }

    static void setScreensaverSeconds(Context ctx, int s) {
        p(ctx).edit().putInt("screensaver_seconds", s).commit();
    }
}
