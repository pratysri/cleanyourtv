package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Continue watching: other apps' Watch Next rows from the TV provider (docs/watch-next.md), newest first.
 *
 * Adapted from WatchNext.java and WatchHistory.java in SferaDev's "Google TV style home" fork
 * (github.com/SferaDev/android-tv, MIT). A sideloaded launcher holds READ_TV_LISTINGS only, so it sees the rows
 * each app marks searchable; an app that keeps its rows private simply does not appear.
 */
final class ContinueWatching {
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final String TAG = "TvHome.Continue";
    /** WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE 3 is the watchlist: saved for later, not a resume point. */
    private static final int TYPE_WATCHLIST = 3;
    /** At most this many shows from one app, so one busy app cannot fill the row. */
    static final int PER_APP = 4;
    /** Shows last watched longer ago than this are left out. */
    static final long MAX_AGE_MS = 45L * 24 * 60 * 60 * 1000;

    private static final String[] COLUMNS = {
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,                  // 0
            TvContract.WatchNextPrograms.COLUMN_TITLE,                         // 1
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,                 // 2
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,         // 3
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,        // 4
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,                // 5
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,                 // 6
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,                    // 7
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,               // 8
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,// 9
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, // 10
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,               // 11
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,       // 12
    };

    /** One show to resume. */
    static final class Item {
        final String pkg, title, subtitle, art, intentUri;
        final long lastWatched;
        /** 0..1, or -1 when the app gave no position. */
        final float progress;
        /** True when the art is a wide (16:9 or 3:2) image rather than a portrait poster. */
        final boolean wideArt;
        /** A "Jump back in" card for an app that shares no shows: opens the app, art is its banner. */
        boolean appCard;

        Item(String pkg, String title, String subtitle, String art, String intentUri, long lastWatched,
             float progress, boolean wideArt) {
            this.pkg = pkg;
            this.title = title;
            this.subtitle = subtitle;
            this.art = art;
            this.intentUri = intentUri;
            this.lastWatched = lastWatched;
            this.progress = progress;
            this.wideArt = wideArt;
        }
    }

    private ContinueWatching() { }

    static boolean missingPermission(Context ctx) {
        return ctx.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED;
    }

    /** Calls onChange on the main thread whenever any app edits Watch Next; run the returned Runnable to stop. */
    static Runnable observe(Context ctx, Runnable onChange) {
        ContentResolver resolver = ctx.getApplicationContext().getContentResolver();
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                onChange.run();
            }
        };
        try {
            resolver.registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, observer);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not observe Watch Next: " + e);
            return () -> { };
        }
        return () -> resolver.unregisterContentObserver(observer);
    }

    /** Blocking (reads the TV provider); call off the main thread. Empty without the permission. */
    static List<Item> load(Context ctx, int limit) {
        List<Item> rows = new ArrayList<>();
        if (missingPermission(ctx)) return rows;
        PackageManager pm = ctx.getPackageManager();
        Map<String, Boolean> launchable = new HashMap<>();
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        // No selection: without ACCESS_ALL_EPG_DATA the provider refuses one (docs/watch-next.md).
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, COLUMNS,
                null, null, null)) {
            while (c != null && c.moveToNext()) {
                String pkg = c.getString(0);
                String title = c.getString(1);
                if (pkg == null || isBlank(title) || c.getInt(8) == TYPE_WATCHLIST) continue;
                long engaged = c.getLong(9);
                if (engaged > 0 && engaged < cutoff) continue;
                Boolean ok = launchable.get(pkg);
                if (ok == null) {
                    ok = launchIntent(pm, pkg) != null;
                    launchable.put(pkg, ok);
                }
                if (!ok) continue;
                String poster = c.getString(5), thumb = c.getString(6);
                // Prefer the wide thumbnail for a landscape card; fall back to the poster.
                String art = !isBlank(thumb) ? thumb : poster;
                boolean wide = !isBlank(thumb) || c.getInt(12) <= 2;   // ASPECT_RATIO 16:9=0, 3:2=1, 4:3=2
                rows.add(new Item(pkg, title.trim(), subtitle(c.getString(2), c.getString(3), c.getString(4)),
                        isBlank(art) ? null : art, isBlank(c.getString(7)) ? null : c.getString(7), engaged,
                        progress(c.getLong(10), c.getLong(11)), wide));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read Watch Next: " + e);
        }
        List<Item> shows = merge(rows, limit);
        // Apps that keep their Watch Next rows private (YouTube, Peacock) get one card that reopens the app.
        Set<String> withShows = new HashSet<>();
        for (Item i : shows) withShows.add(i.pkg);
        List<Item> all = new ArrayList<>(shows);
        all.addAll(appCards(ctx, withShows));
        all.sort((a, b) -> Long.compare(b.lastWatched, a.lastWatched));
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    /** Apps opened within this many days get a "Jump back in" card. */
    static final long APP_CARD_DAYS = 14;

    /** One card per config.xml resume_apps package used lately that has no show in the row; empty without usage access. */
    static List<Item> appCards(Context ctx, Set<String> withShows) {
        List<Item> out = new ArrayList<>();
        String[] pkgs = ctx.getResources().getStringArray(R.array.resume_apps);
        if (pkgs.length == 0) return out;
        android.app.usage.UsageStatsManager usm = ctx.getSystemService(android.app.usage.UsageStatsManager.class);
        if (usm == null) return out;
        long now = System.currentTimeMillis();
        Map<String, android.app.usage.UsageStats> stats;
        try {
            stats = usm.queryAndAggregateUsageStats(now - APP_CARD_DAYS * 24 * 60 * 60 * 1000, now);
        } catch (RuntimeException e) {
            return out;
        }
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : pkgs) {
            android.app.usage.UsageStats u = stats == null ? null : stats.get(pkg);
            if (withShows.contains(pkg) || u == null || u.getLastTimeUsed() <= 0
                    || u.getTotalTimeInForeground() < 60_000 || launchIntent(pm, pkg) == null) continue;
            String name;
            try {
                name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            CharSequence ago = android.text.format.DateUtils.getRelativeTimeSpanString(u.getLastTimeUsed(), now,
                    android.text.format.DateUtils.MINUTE_IN_MILLIS);
            Item item = new Item(pkg, name, ctx.getString(R.string.jump_back_in, ago), null, null,
                    u.getLastTimeUsed(), -1f, true);
            item.appCard = true;
            out.add(item);
        }
        return out;
    }

    /** Newest first, one entry per show (its latest episode), at most PER_APP per app, at most limit in all. */
    static List<Item> merge(List<Item> rows, int limit) {
        List<Item> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> Long.compare(b.lastWatched, a.lastWatched));
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Integer> perApp = new HashMap<>();
        for (Item item : sorted) {
            if (out.size() >= limit) break;
            if (!seen.add(item.pkg + "\0" + item.title.toLowerCase(Locale.ROOT))) continue;
            int n = perApp.containsKey(item.pkg) ? perApp.get(item.pkg) : 0;
            if (n >= PER_APP) continue;
            perApp.put(item.pkg, n + 1);
            out.add(item);
        }
        return out;
    }

    /** "S1 E2 · Episode title", or whichever parts exist; null when none. */
    static String subtitle(String episodeTitle, String season, String episode) {
        StringBuilder sb = new StringBuilder();
        if (!isBlank(season)) sb.append('S').append(season.trim());
        if (!isBlank(episode)) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('E').append(episode.trim());
        }
        if (!isBlank(episodeTitle)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(episodeTitle.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static float progress(long position, long duration) {
        if (duration <= 0 || position <= 0) return -1f;
        return Math.min(1f, (float) position / duration);
    }

    /** Opens the show inside its app when the row carries a deep link, else the app itself; null if neither. */
    static Intent open(Context ctx, Item item) {
        if (item.intentUri != null) {
            try {
                Intent i = item.intentUri.startsWith("intent:")
                        ? Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME)
                        : new Intent(Intent.ACTION_VIEW, Uri.parse(item.intentUri));
                if (i.getPackage() == null && i.getComponent() == null) i.setPackage(item.pkg);
                String target = i.getComponent() != null ? i.getComponent().getPackageName() : i.getPackage();
                // A row may only open its own app.
                if (item.pkg.equals(target)) {
                    i.setSelector(null);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (i.resolveActivity(ctx.getPackageManager()) != null) return i;
                }
            } catch (URISyntaxException | RuntimeException ignored) {
                // Fall through to the app's own start screen.
            }
        }
        Intent launch = launchIntent(ctx.getPackageManager(), item.pkg);
        return launch == null ? null : launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
