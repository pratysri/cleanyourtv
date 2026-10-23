package com.gathin.tvhome;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Watch time (hisense-home fork): which app was on screen, when, and for whom.
 *
 * Android already logs every app coming to and leaving the foreground (UsageStatsManager, usage access granted with
 * appops at install). Each ingest turns the log since the last one into sessions, tags each with the person who was
 * active when it started (Profiles' switch log; -1 before profiles existed), and keeps them in watch.db on the TV.
 * Nothing leaves the TV.
 */
final class WatchStats {
    /** Not watching: Home itself, settings, the screensaver and system screens. */
    private static final Set<String> IGNORED = new HashSet<>(java.util.Arrays.asList(
            "com.gathin.tvhome", "com.android.tv.settings", "com.google.android.apps.tv.launcherx",
            "com.google.android.apps.tv.dreamx", "com.android.systemui", "com.google.android.tvlauncher",
            "com.android.vending", "com.google.android.gms", "com.vt.tv.input",
            "com.google.android.inputmethod.latin", "com.google.android.katniss"));
    /** Sessions shorter than this are channel surfing, not watching. */
    private static final long MIN_SESSION_MS = 60_000;
    /** Two stretches in one app closer than this are one session (an ad break, a menu). */
    private static final long MERGE_GAP_MS = 2 * 60_000;
    /** Each ingest re-reads this much before its last point, so a session open at the last ingest is completed. */
    private static final long OVERLAP_MS = 6 * 60 * 60_000L;
    /** On first run, the history Android still holds. */
    private static final long BACKFILL_MS = 30L * 24 * 60 * 60_000;

    static final class Session {
        final String pkg;
        final long start, end;
        final int profile;

        Session(String pkg, long start, long end, int profile) {
            this.pkg = pkg;
            this.start = start;
            this.end = end;
            this.profile = profile;
        }

        long length() {
            return end - start;
        }
    }

    private static final class Db extends SQLiteOpenHelper {
        Db(Context ctx) {
            super(ctx, "watch.db", null, 1);
        }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE sessions (pkg TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL,"
                    + " profile INTEGER NOT NULL, PRIMARY KEY (pkg, start))");
            db.execSQL("CREATE INDEX sessions_start ON sessions (start)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int from, int to) { }
    }

    private static Db db;

    private static synchronized SQLiteDatabase db(Context ctx) {
        if (db == null) db = new Db(ctx.getApplicationContext());
        return db.getWritableDatabase();
    }

    private WatchStats() { }

    // ---- ingest ------------------------------------------------------------------------------------

    /** Blocking; call off the main thread. Brings watch.db up to now. */
    static synchronized void ingest(Context ctx) {
        UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
        if (usm == null) return;
        SharedPreferences prefs = ctx.getSharedPreferences("watch_stats", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = prefs.getLong("ingested_to", 0);
        long from = last == 0 ? now - BACKFILL_MS : last - OVERLAP_MS;
        List<Session> fresh;
        try {
            fresh = sessions(ctx, usm.queryEvents(from, now), now);
        } catch (RuntimeException e) {
            return;   // no usage access
        }
        SQLiteDatabase w = db(ctx);
        w.beginTransaction();
        try {
            // The overlap is rebuilt from the log, so a session cut short by the last ingest is replaced whole.
            w.delete("sessions", "start >= ?", new String[]{String.valueOf(from)});
            for (Session s : fresh) {
                ContentValues v = new ContentValues();
                v.put("pkg", s.pkg);
                v.put("start", s.start);
                v.put("end", s.end);
                v.put("profile", s.profile);
                w.insertWithOnConflict("sessions", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
        prefs.edit().putLong("ingested_to", now).apply();
    }

    /** Foreground stretches per app from the event log, merged across short gaps, tagged with who was active. */
    static List<Session> sessions(Context ctx, UsageEvents events, long now) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, Boolean> watchable = new HashMap<>();
        List<long[]> spans = new ArrayList<>();
        List<String> spanPkgs = new ArrayList<>();
        String openPkg = null;
        long openAt = 0;
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            int type = e.getEventType();
            String pkg = e.getPackageName();
            long t = e.getTimeStamp();
            boolean resumed = type == UsageEvents.Event.ACTIVITY_RESUMED;
            boolean ended = type == UsageEvents.Event.ACTIVITY_PAUSED || type == UsageEvents.Event.ACTIVITY_STOPPED;
            boolean screenOff = type == UsageEvents.Event.SCREEN_NON_INTERACTIVE
                    || type == UsageEvents.Event.DEVICE_SHUTDOWN;
            if (resumed) {
                if (openPkg != null && !openPkg.equals(pkg)) {
                    spans.add(new long[]{openAt, t});
                    spanPkgs.add(openPkg);
                    openPkg = null;
                }
                if (openPkg == null) {
                    Boolean ok = watchable.get(pkg);
                    if (ok == null) {
                        ok = !IGNORED.contains(pkg) && ContinueWatching.launchIntent(pm, pkg) != null;
                        watchable.put(pkg, ok);
                    }
                    if (ok) {
                        openPkg = pkg;
                        openAt = t;
                    }
                }
            } else if ((ended && pkg.equals(openPkg)) || (screenOff && openPkg != null)) {
                spans.add(new long[]{openAt, t});
                spanPkgs.add(openPkg);
                openPkg = null;
            }
        }
        // An app still in front now (ingest from the Recap screen while something plays behind is rare) runs to now.
        if (openPkg != null) {
            spans.add(new long[]{openAt, now});
            spanPkgs.add(openPkg);
        }
        List<Session> out = new ArrayList<>();
        String curPkg = null;
        long curStart = 0, curEnd = 0;
        for (int i = 0; i < spans.size(); i++) {
            String pkg = spanPkgs.get(i);
            long s = spans.get(i)[0], en = spans.get(i)[1];
            if (pkg.equals(curPkg) && s - curEnd <= MERGE_GAP_MS) {
                curEnd = Math.max(curEnd, en);
                continue;
            }
            if (curPkg != null && curEnd - curStart >= MIN_SESSION_MS) {
                out.add(new Session(curPkg, curStart, curEnd, Profiles.activeAt(ctx, curStart)));
            }
            curPkg = pkg;
            curStart = s;
            curEnd = en;
        }
        if (curPkg != null && curEnd - curStart >= MIN_SESSION_MS) {
            out.add(new Session(curPkg, curStart, curEnd, Profiles.activeAt(ctx, curStart)));
        }
        return out;
    }

    // ---- queries -----------------------------------------------------------------------------------

    /** Every person's (EVERYONE) or one person's sessions overlapping [from, to), clipped to it. */
    static final int EVERYONE = -2;

    static List<Session> between(Context ctx, long from, long to, int profile) {
        List<Session> out = new ArrayList<>();
        String where = "start < ? AND end > ?" + (profile == EVERYONE ? "" : " AND profile = ?");
        String[] args = profile == EVERYONE
                ? new String[]{String.valueOf(to), String.valueOf(from)}
                : new String[]{String.valueOf(to), String.valueOf(from), String.valueOf(profile)};
        try (Cursor c = db(ctx).query("sessions", new String[]{"pkg", "start", "end", "profile"}, where, args,
                null, null, "start")) {
            while (c.moveToNext()) {
                out.add(new Session(c.getString(0), Math.max(from, c.getLong(1)), Math.min(to, c.getLong(2)),
                        c.getInt(3)));
            }
        }
        return out;
    }

    static long total(List<Session> sessions) {
        long t = 0;
        for (Session s : sessions) t += s.length();
        return t;
    }

    /** Milliseconds per app, largest first. */
    static List<Map.Entry<String, Long>> perApp(List<Session> sessions) {
        Map<String, Long> m = new HashMap<>();
        for (Session s : sessions) m.merge(s.pkg, s.length(), Long::sum);
        List<Map.Entry<String, Long>> list = new ArrayList<>(m.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return list;
    }

    /** Milliseconds per bucket: buckets[i] covers [starts[i], starts[i+1]). */
    static long[] buckets(List<Session> sessions, long[] starts) {
        long[] out = new long[starts.length - 1];
        for (Session s : sessions) {
            for (int i = 0; i < out.length; i++) {
                long a = Math.max(s.start, starts[i]), b = Math.min(s.end, starts[i + 1]);
                if (b > a) out[i] += b - a;
            }
        }
        return out;
    }

    /** Morning (5-12), afternoon (12-17), evening (17-22) or night: where most of the time falls, as an index. */
    static int partOfDay(List<Session> sessions) {
        long[] parts = new long[4];
        Calendar c = Calendar.getInstance();
        for (Session s : sessions) {
            // Sampled every 5 minutes: exact enough for a headline, cheap for a year of sessions.
            for (long t = s.start; t < s.end; t += 5 * 60_000) {
                c.setTimeInMillis(t);
                int h = c.get(Calendar.HOUR_OF_DAY);
                parts[h >= 5 && h < 12 ? 0 : h >= 12 && h < 17 ? 1 : h >= 17 && h < 22 ? 2 : 3] += 1;
            }
        }
        int best = 0;
        for (int i = 1; i < 4; i++) if (parts[i] > parts[best]) best = i;
        return parts[best] == 0 ? -1 : best;
    }

    static Session longest(List<Session> sessions) {
        Session best = null;
        for (Session s : sessions) if (best == null || s.length() > best.length()) best = s;
        return best;
    }

    /** "6 h 20 m", "45 m", "0 m". */
    static String format(long ms) {
        long m = Math.round(ms / 60_000.0);
        if (m < 60) return m + " m";
        return (m / 60) + " h " + (m % 60) + " m";
    }
}
