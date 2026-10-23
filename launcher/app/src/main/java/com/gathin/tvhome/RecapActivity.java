package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV Recap (hisense-home fork): watch time per person for today, the week, the month and the year, from WatchStats.
 * Its own tile on Home, so it opens like an app. "Play your year" runs a full-screen story of the year's highlights.
 */
public final class RecapActivity extends Activity {
    private static final int TODAY = 0, WEEK = 1, MONTH = 2, YEAR = 3;
    private static final String[] PERIODS = {"Today", "This week", "This month", "This year"};
    private static final String[] PARTS = {"in the morning", "in the afternoon", "in the evening", "late at night"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private float density;
    private Typeface light, regular, medium;
    private int profile;   // WatchStats.EVERYONE or a person
    private int period = WEEK;
    private final List<TextView> personPills = new ArrayList<>(), periodPills = new ArrayList<>();
    private LinearLayout content;
    private FrameLayout root;
    private View story;
    private Runnable storyStep;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        light = font(R.font.outfit_light);
        regular = font(R.font.outfit_regular);
        medium = font(R.font.outfit_medium);
        profile = Profiles.enabled(this) ? Profiles.current(this) : WatchStats.EVERYONE;

        root = new FrameLayout(this);
        int accent = profile >= 0 ? Profiles.color(profile) : 0xFF5B6CFF;
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{blend(accent, 0xFF0B0D12, 0.78f), 0xFF0B0D12, 0xFF07080B}));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        LinearLayout page = column();
        page.setPadding(dp(64), dp(40), dp(64), dp(48));
        page.setClipChildren(false);
        page.setClipToPadding(false);
        scroll.addView(page);
        root.addView(scroll);
        setContentView(root);

        // Header: title, people, periods.
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("TV Recap", 40, medium, Color.WHITE), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (Profiles.enabled(this)) {
            String[] names = Profiles.names(this);
            for (int i = -1; i < names.length; i++) {
                final int who = i < 0 ? WatchStats.EVERYONE : i;
                TextView pill = pill(i < 0 ? "Everyone" : names[i]);
                pill.setOnClickListener(v -> {
                    profile = who;
                    refresh();
                });
                pill.setTag(who);
                personPills.add(pill);
                header.addView(pill, pillParams());
            }
        }
        page.addView(header);

        LinearLayout tabs = row();
        tabs.setPadding(0, dp(18), 0, dp(10));
        for (int i = 0; i < PERIODS.length; i++) {
            final int p = i;
            TextView pill = pill(PERIODS[i]);
            pill.setOnClickListener(v -> {
                period = p;
                refresh();
            });
            pill.setTag(i);
            periodPills.add(pill);
            tabs.addView(pill, pillParams());
        }
        TextView play = pill("▶  Play your year");
        play.setOnClickListener(v -> playYear());
        LinearLayout.LayoutParams playLp = pillParams();
        playLp.leftMargin = dp(36);
        tabs.addView(play, playLp);
        page.addView(tabs);

        content = column();
        content.setClipChildren(false);
        page.addView(content);
        refresh();
        periodPills.get(period).requestFocus();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        bg.shutdownNow();
        if (storyStep != null) main.removeCallbacks(storyStep);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (story != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            int k = event.getKeyCode();
            if (k == KeyEvent.KEYCODE_BACK) {
                closeStory();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (storyStep != null) {
                    main.removeCallbacks(storyStep);
                    storyStep.run();
                }
                return true;
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- data --------------------------------------------------------------------------------------

    /** [from, to) of the period, the comparison period before it, and its chart buckets with labels. */
    private static final class Window {
        long from, to, prevFrom, prevTo;
        long[] starts;
        String[] labels;
        String noun;
    }

    private static Window window(int period, long now) {
        Window w = new Window();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        List<Long> starts = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (period == TODAY) {
            w.from = c.getTimeInMillis();
            w.to = w.from + 24L * 3600_000;
            w.prevFrom = w.from - 24L * 3600_000;
            w.prevTo = w.from;
            for (int h = 0; h <= 24; h++) {
                starts.add(w.from + h * 3600_000L);
                if (h < 24) labels.add(h % 6 == 0 ? (h == 0 ? "12a" : h < 12 ? h + "a" : h == 12 ? "12p" : (h - 12) + "p") : "");
            }
            w.noun = "today";
        } else if (period == WEEK) {
            c.add(Calendar.DAY_OF_MONTH, -6);
            w.from = c.getTimeInMillis();
            SimpleDateFormat day = new SimpleDateFormat("EEE", Locale.getDefault());
            for (int d = 0; d <= 7; d++) {
                starts.add(c.getTimeInMillis());
                if (d < 7) labels.add(day.format(c.getTime()));
                c.add(Calendar.DAY_OF_MONTH, 1);
            }
            w.to = starts.get(7);
            w.prevTo = w.from;
            w.prevFrom = w.from - 7L * 24 * 3600_000;
            w.noun = "this week";
        } else if (period == MONTH) {
            c.set(Calendar.DAY_OF_MONTH, 1);
            w.from = c.getTimeInMillis();
            int days = c.getActualMaximum(Calendar.DAY_OF_MONTH);
            for (int d = 0; d <= days; d++) {
                starts.add(c.getTimeInMillis());
                if (d < days) labels.add((d + 1) % 5 == 1 ? String.valueOf(d + 1) : "");
                c.add(Calendar.DAY_OF_MONTH, 1);
            }
            w.to = starts.get(days);
            Calendar p = Calendar.getInstance();
            p.setTimeInMillis(w.from);
            p.add(Calendar.MONTH, -1);
            w.prevFrom = p.getTimeInMillis();
            w.prevTo = w.from;
            w.noun = "this month";
        } else {
            c.set(Calendar.DAY_OF_YEAR, 1);
            w.from = c.getTimeInMillis();
            SimpleDateFormat month = new SimpleDateFormat("MMM", Locale.getDefault());
            for (int m = 0; m <= 12; m++) {
                starts.add(c.getTimeInMillis());
                if (m < 12) labels.add(month.format(c.getTime()));
                c.add(Calendar.MONTH, 1);
            }
            w.to = starts.get(12);
            Calendar p = Calendar.getInstance();
            p.setTimeInMillis(w.from);
            p.add(Calendar.YEAR, -1);
            w.prevFrom = p.getTimeInMillis();
            w.prevTo = w.from;
            w.noun = "this year";
        }
        w.starts = new long[starts.size()];
        for (int i = 0; i < starts.size(); i++) w.starts[i] = starts.get(i);
        w.labels = labels.toArray(new String[0]);
        return w;
    }

    private static final class Report {
        Window w;
        List<WatchStats.Session> sessions;
        long total, previous;
        long[] buckets;
        List<Map.Entry<String, Long>> apps;
        int part;
        WatchStats.Session longest;
        int bestBucket;
        int activeBuckets;
    }

    private Report report(int period, int who) {
        long now = System.currentTimeMillis();
        Report r = new Report();
        r.w = window(period, now);
        long to = Math.min(r.w.to, now);
        r.sessions = WatchStats.between(this, r.w.from, to, who);
        r.total = WatchStats.total(r.sessions);
        r.previous = WatchStats.total(WatchStats.between(this, r.w.prevFrom, r.w.prevTo, who));
        r.buckets = WatchStats.buckets(r.sessions, r.w.starts);
        r.apps = WatchStats.perApp(r.sessions);
        r.part = WatchStats.partOfDay(r.sessions);
        r.longest = WatchStats.longest(r.sessions);
        r.bestBucket = -1;
        for (int i = 0; i < r.buckets.length; i++) {
            if (r.buckets[i] > 0) r.activeBuckets++;
            if (r.buckets[i] > 0 && (r.bestBucket < 0 || r.buckets[i] > r.buckets[r.bestBucket])) r.bestBucket = i;
        }
        return r;
    }

    private void refresh() {
        for (TextView p : personPills) select(p, (int) p.getTag() == profile);
        for (TextView p : periodPills) select(p, (int) p.getTag() == period);
        final int per = period, who = profile;
        bg.execute(() -> {
            WatchStats.ingest(this);
            Report r = report(per, who);
            main.post(() -> {
                if (!isDestroyed() && per == period && who == profile) render(r);
            });
        });
    }

    // ---- page --------------------------------------------------------------------------------------

    private void render(Report r) {
        content.removeAllViews();
        String whoName = profile >= 0 ? Profiles.names(this)[profile] : "Everyone";

        LinearLayout hero = column();
        hero.setPadding(0, dp(18), 0, dp(6));
        hero.addView(text(WatchStats.format(r.total), 76, light, Color.WHITE));
        String caption = (profile >= 0 ? whoName + " watched " : "Watched ") + r.w.noun;
        if (r.previous > 0 || r.total > 0) {
            long diff = r.total - r.previous;
            String prev = period == TODAY ? "yesterday" : period == WEEK ? "the week before"
                    : period == MONTH ? "last month" : "last year";
            caption += diff == 0 ? "  ·  same as " + prev
                    : "  ·  " + (diff > 0 ? "↑ " : "↓ ") + WatchStats.format(Math.abs(diff)) + " vs " + prev;
        }
        hero.addView(text(caption, 18, regular, 0xB3FFFFFF));
        content.addView(hero);

        BarChart chart = new BarChart(this, r.buckets, r.w.labels,
                profile >= 0 ? Profiles.color(profile) : 0xFF8FA2FF, r.bestBucket);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170));
        clp.topMargin = dp(18);
        content.addView(glass(chart, dp(18)), clp);

        LinearLayout split = row();
        split.setPadding(0, dp(22), 0, 0);
        LinearLayout apps = column();
        apps.addView(text("Top apps", 20, medium, Color.WHITE));
        if (r.apps.isEmpty()) {
            apps.addView(note(profile >= 0
                    ? "Nothing yet for " + whoName + ". Time counts for whoever is picked in “Who’s watching?”."
                    : "Nothing watched " + r.w.noun + " yet."));
        }
        for (int i = 0; i < Math.min(5, r.apps.size()); i++) {
            apps.addView(appRow(r.apps.get(i).getKey(), r.apps.get(i).getValue(), r.apps.get(0).getValue()));
        }
        split.addView(glass(apps, dp(20)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));

        LinearLayout facts = column();
        facts.addView(text("Highlights", 20, medium, Color.WHITE));
        if (r.bestBucket >= 0 && period != TODAY) {
            String when = period == YEAR ? new SimpleDateFormat("MMMM", Locale.getDefault()).format(r.w.starts[r.bestBucket])
                    : new SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(r.w.starts[r.bestBucket]);
            facts.addView(fact("Biggest " + (period == YEAR ? "month" : "day"), when + "  ·  "
                    + WatchStats.format(r.buckets[r.bestBucket])));
        }
        if (r.longest != null) {
            facts.addView(fact("Longest stretch", label(r.longest.pkg) + "  ·  "
                    + WatchStats.format(r.longest.length())));
        }
        if (r.part >= 0) facts.addView(fact("Usually watching", PARTS[r.part]));
        if (period == WEEK || period == MONTH) {
            facts.addView(fact("Days with TV", r.activeBuckets + " of " + r.buckets.length));
        }
        if (facts.getChildCount() == 1) facts.addView(note("Highlights appear once there is some watching to look at."));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        flp.leftMargin = dp(22);
        split.addView(glass(facts, dp(20)), flp);
        content.addView(split);

        if (profile >= 0) {
            content.addView(note("Viewing from before profiles were set up counts toward Everyone only."));
        }
    }

    private View appRow(String pkg, long ms, long max) {
        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, 0);
        ImageView art = new ImageView(this);
        art.setImageDrawable(banner(pkg));
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setClipToOutline(true);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(8 * density);
        shape.setColor(0xFF20232B);
        art.setBackground(shape);
        row.addView(art, new LinearLayout.LayoutParams(dp(80), dp(45)));
        LinearLayout mid = column();
        mid.setPadding(dp(16), 0, dp(12), 0);
        mid.addView(text(label(pkg), 17, medium, Color.WHITE));
        View bar = new View(this);
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(3 * density);
        fill.setColor(profile >= 0 ? Profiles.color(profile) : 0xFF8FA2FF);
        bar.setBackground(fill);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                Math.max(dp(6), Math.round(dp(260) * (float) ms / Math.max(1, max))), dp(6));
        blp.topMargin = dp(8);
        mid.addView(bar, blp);
        row.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text(WatchStats.format(ms), 17, regular, 0xD9FFFFFF));
        return row;
    }

    private View fact(String title, String value) {
        LinearLayout f = column();
        f.setPadding(0, dp(14), 0, 0);
        TextView t = text(title.toUpperCase(Locale.getDefault()), 12, regular, 0x99FFFFFF);
        t.setLetterSpacing(0.14f);
        f.addView(t);
        f.addView(text(value, 19, medium, Color.WHITE));
        return f;
    }

    // ---- your year, as a story ---------------------------------------------------------------------

    private void playYear() {
        bg.execute(() -> {
            WatchStats.ingest(this);
            Report r = report(YEAR, profile);
            main.post(() -> {
                if (!isDestroyed()) showStory(r);
            });
        });
    }

    private void showStory(Report r) {
        String who = profile >= 0 ? Profiles.names(this)[profile] : "You both";
        List<String[]> slides = new ArrayList<>();
        int year = Calendar.getInstance().get(Calendar.YEAR);
        slides.add(new String[]{String.valueOf(year), profile >= 0 ? who + "\u2019s year on TV" : "Your year on TV"});
        slides.add(new String[]{WatchStats.format(r.total), "of watching so far this year"});
        if (!r.apps.isEmpty()) {
            slides.add(new String[]{label(r.apps.get(0).getKey()), "was the favourite: "
                    + WatchStats.format(r.apps.get(0).getValue())});
        }
        if (r.bestBucket >= 0) {
            slides.add(new String[]{new SimpleDateFormat("MMMM", Locale.getDefault()).format(r.w.starts[r.bestBucket]),
                    "was the biggest month: " + WatchStats.format(r.buckets[r.bestBucket])});
        }
        if (r.longest != null) {
            slides.add(new String[]{WatchStats.format(r.longest.length()), "the longest stretch, on "
                    + label(r.longest.pkg)});
        }
        if (r.part >= 0) slides.add(new String[]{"Mostly", PARTS[r.part]});
        if (r.apps.size() > 1) {
            StringBuilder top = new StringBuilder();
            for (int i = 0; i < Math.min(5, r.apps.size()); i++) {
                top.append(i + 1).append(".  ").append(label(r.apps.get(i).getKey())).append('\n');
            }
            slides.add(new String[]{"Top apps", top.toString().trim()});
        }

        int accent = profile >= 0 ? Profiles.color(profile) : 0xFF6B7BFF;
        FrameLayout layer = new FrameLayout(this);
        layer.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{blend(accent, 0xFF000000, 0.25f), blend(accent, 0xFF000000, 0.7f), 0xFF050507}));
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER);
        TextView big = text("", 88, medium, Color.WHITE);
        big.setGravity(Gravity.CENTER);
        TextView small = text("", 28, light, 0xE6FFFFFF);
        small.setGravity(Gravity.CENTER);
        small.setPadding(0, dp(18), 0, 0);
        box.addView(big);
        box.addView(small);
        layer.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        TextView hint = text("OK for next  ·  Back to close", 14, regular, 0x80FFFFFF);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = dp(36);
        layer.addView(hint, hlp);
        root.addView(layer);
        story = layer;
        layer.setAlpha(0f);
        layer.animate().alpha(1f).setDuration(300).start();

        int[] at = {0};
        storyStep = () -> {
            if (story == null) return;
            if (at[0] >= slides.size()) {
                closeStory();
                return;
            }
            String[] s = slides.get(at[0]++);
            box.setAlpha(0f);
            box.setTranslationY(dp(24));
            big.setText(s[0]);
            small.setText(s[1]);
            box.animate().alpha(1f).translationY(0f).setDuration(450).start();
            main.postDelayed(storyStep, 4500);
        };
        storyStep.run();
    }

    private void closeStory() {
        if (story == null) return;
        if (storyStep != null) main.removeCallbacks(storyStep);
        View s = story;
        story = null;
        s.animate().alpha(0f).setDuration(200).withEndAction(() -> root.removeView(s)).start();
    }

    // ---- small parts -------------------------------------------------------------------------------

    /** A rounded bar chart; the best bucket is drawn in full colour, the rest softer. */
    private static final class BarChart extends View {
        private final long[] values;
        private final String[] labels;
        private final int color, best;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        BarChart(Context ctx, long[] values, String[] labels, int color, int best) {
            super(ctx);
            this.values = values;
            this.labels = labels;
            this.color = color;
            this.best = best;
            float d = ctx.getResources().getDisplayMetrics().density;
            label.setColor(0x99FFFFFF);
            label.setTextSize(12 * d);
            label.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight() - 22 * d;
            long max = 1;
            for (long v : values) max = Math.max(max, v);
            int n = values.length;
            float slot = w / n, barW = Math.min(slot * 0.62f, 34 * d);
            for (int i = 0; i < n; i++) {
                float cx = slot * i + slot / 2f;
                float bh = values[i] == 0 ? 3 * d : Math.max(6 * d, (h - 8 * d) * values[i] / (float) max);
                r.set(cx - barW / 2f, h - bh, cx + barW / 2f, h);
                bar.setColor(i == best ? color : values[i] == 0 ? 0x26FFFFFF : (color & 0x00FFFFFF) | 0x8C000000);
                c.drawRoundRect(r, barW / 3f, barW / 3f, bar);
                if (i < labels.length && !labels[i].isEmpty()) c.drawText(labels[i], cx, getHeight() - 4 * d, label);
            }
        }
    }

    private View glass(View child, int pad) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x1FFFFFFF, 0x0DFFFFFF});
        g.setCornerRadius(22 * density);
        g.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        f.setBackground(g);
        f.setPadding(pad, pad, pad, pad);
        f.addView(child, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return f;
    }

    private TextView pill(String s) {
        TextView t = text(s, 16, medium, Color.WHITE);
        t.setPadding(dp(20), dp(9), dp(20), dp(9));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnFocusChangeListener((v, f) -> {
            select((TextView) v, v.getTag() != null && isSelected(v));
            v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(120).start();
        });
        select(t, false);
        return t;
    }

    private boolean isSelected(View v) {
        Object tag = v.getTag();
        if (personPills.contains(v)) return (int) tag == profile;
        if (periodPills.contains(v)) return (int) tag == period;
        return false;
    }

    /** Selected: solid white; focused: light glass; otherwise: faint glass. */
    private void select(TextView t, boolean selected) {
        boolean focused = t.isFocused();
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(22 * density);
        g.setColor(selected ? 0xF2FFFFFF : focused ? 0x4DFFFFFF : 0x14FFFFFF);
        g.setStroke(Math.max(1, dp(1)), focused ? 0xCCFFFFFF : 0x26FFFFFF);
        t.setBackground(g);
        t.setTextColor(selected ? 0xFF111318 : Color.WHITE);
    }

    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(10);
        return lp;
    }

    private TextView note(String s) {
        TextView t = text(s, 14, regular, 0x99FFFFFF);
        t.setPadding(0, dp(14), 0, 0);
        return t;
    }

    private TextView text(String s, float sp, Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private String label(String pkg) {
        if ("com.vt.tv.tvcenter".equals(pkg)) return "Live TV";
        PackageManager pm = getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private Drawable banner(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            Intent launch = ContinueWatching.launchIntent(pm, pkg);
            Drawable d = launch == null ? null : pm.getActivityBanner(launch);
            if (d == null) d = pm.getApplicationBanner(pkg);
            return d != null ? d : pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private Typeface font(int res) {
        try {
            return getResources().getFont(res);
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) * (1 - t) + Color.red(b) * t);
        int g = Math.round(Color.green(a) * (1 - t) + Color.green(b) * t);
        int bl = Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t);
        return Color.rgb(r, g, bl);
    }
}
