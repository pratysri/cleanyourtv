package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Search (hisense-home fork): one box for installed apps and for movies and shows, with the apps on this TV that
 * stream each title. Titles and availability come from TMDB, whose streaming data is JustWatch's; the read token is
 * baked in at build time from a private file (my-tv/tmdb.properties). Picking a title opens the app that has it, at
 * its own search when the app accepts one.
 */
public final class SearchActivity extends Activity {
    /** TMDB watch-provider id to the TV package that plays it (US). Only installed packages are shown. */
    private static final Map<Integer, String> PROVIDERS = new HashMap<>();

    static {
        PROVIDERS.put(8, "com.netflix.ninja");                    // Netflix
        PROVIDERS.put(1796, "com.netflix.ninja");                 // Netflix basic with ads
        PROVIDERS.put(9, "com.amazon.amazonvideo.livingroom");    // Amazon Prime Video
        PROVIDERS.put(119, "com.amazon.amazonvideo.livingroom");  // Amazon Prime Video
        PROVIDERS.put(2100, "com.amazon.amazonvideo.livingroom"); // Prime Video with ads
        PROVIDERS.put(337, "com.disney.disneyplus");              // Disney+
        PROVIDERS.put(15, "com.hulu.livingroomplus");             // Hulu
        PROVIDERS.put(1899, "com.wbd.stream");                    // Max
        PROVIDERS.put(384, "com.wbd.stream");                     // HBO Max (older id)
        PROVIDERS.put(386, "com.peacocktv.peacockandroid");       // Peacock Premium
        PROVIDERS.put(387, "com.peacocktv.peacockandroid");       // Peacock Premium Plus
        PROVIDERS.put(350, "com.apple.atve.androidtv.appletv");   // Apple TV+
        PROVIDERS.put(2, "com.apple.atve.androidtv.appletv");     // Apple TV (store)
        PROVIDERS.put(73, "com.tubitv");                          // Tubi
        PROVIDERS.put(300, "tv.pluto.android");                   // Pluto TV
        PROVIDERS.put(192, "com.google.android.youtube.tv");      // YouTube
        PROVIDERS.put(188, "com.google.android.youtube.tv");      // YouTube Premium
        PROVIDERS.put(122, "in.startv.hotstar");                  // Hotstar
        PROVIDERS.put(232, "com.graymatrix.did");                 // ZEE5
        PROVIDERS.put(237, "com.sonyliv");                        // SonyLIV
    }

    /** Apps whose own search a title can be handed to as a link, when ACTION_SEARCH is not taken. */
    private static final Map<String, String> SEARCH_LINKS = new HashMap<>();

    static {
        SEARCH_LINKS.put("com.netflix.ninja", "https://www.netflix.com/search?q=");
        SEARCH_LINKS.put("com.google.android.youtube.tv", "https://www.youtube.com/results?search_query=");
    }

    private static final LruCache<String, Bitmap> POSTERS = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newFixedThreadPool(3);
    private final AtomicInteger generation = new AtomicInteger();
    private float density;
    private Typeface regular, medium, light;
    private EditText box;
    private LinearLayout results;
    private String token;
    private final Runnable search = this::runSearch;

    private static final class Title {
        String name, year, kind, poster;
        int id;
        final List<String> apps = new ArrayList<>();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        regular = font(R.font.outfit_regular);
        medium = font(R.font.outfit_medium);
        light = font(R.font.outfit_light);
        token = getString(R.string.tmdb_read_token).trim();

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF141824, 0xFF0B0D12, 0xFF07080B}));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(64), dp(44), dp(64), dp(40));
        page.setClipChildren(false);
        page.setClipToPadding(false);
        scroll.addView(page);
        root.addView(scroll);
        setContentView(root);

        box = new EditText(this);
        box.setHint("Search movies, shows and apps");
        box.setHintTextColor(0x80FFFFFF);
        box.setTextColor(Color.WHITE);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        box.setTypeface(light);
        box.setSingleLine(true);
        box.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.setPadding(dp(26), dp(16), dp(26), dp(16));
        GradientDrawable field = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x26FFFFFF, 0x12FFFFFF});
        field.setCornerRadius(28 * density);
        field.setStroke(Math.max(1, dp(1)), 0x4DFFFFFF);
        box.setBackground(field);
        box.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                main.removeCallbacks(search);
                main.postDelayed(search, 450);
            }
        });
        box.setOnEditorActionListener((v, action, ev) -> {
            main.removeCallbacks(search);
            runSearch();
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(box.getWindowToken(), 0);
            View first = results.findFocus() == null ? firstFocusable(results) : null;
            if (first != null) first.requestFocus();
            return true;
        });
        page.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setClipChildren(false);
        page.addView(results);

        TextView credit = text(token.isEmpty()
                ? "Movies and shows need a TMDB token in my-tv/tmdb.properties. Apps are searched either way."
                : "Titles from TMDB. Where to watch from JustWatch.", 13, regular, 0x73FFFFFF);
        credit.setPadding(0, dp(28), 0, 0);
        page.addView(credit);

        box.requestFocus();
        main.postDelayed(() -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(box, InputMethodManager.SHOW_IMPLICIT);
        }, 300);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        net.shutdownNow();
    }

    // ---- searching ---------------------------------------------------------------------------------

    private void runSearch() {
        String q = box.getText().toString().trim();
        int gen = generation.incrementAndGet();
        results.removeAllViews();
        if (q.isEmpty()) return;
        List<ResolveInfo> apps = matchingApps(q);
        if (!apps.isEmpty()) {
            results.addView(heading("Apps"));
            LinearLayout row = shelf();
            for (ResolveInfo ri : apps) row.addView(appTile(ri));
        }
        if (token.isEmpty()) return;
        net.execute(() -> {
            List<Title> titles = tmdbSearch(q);
            if (gen != generation.get()) return;
            main.post(() -> {
                if (gen != generation.get() || isDestroyed()) return;
                if (titles.isEmpty()) {
                    results.addView(heading("No movies or shows found for “" + q + "”"));
                    return;
                }
                results.addView(heading("Movies & shows"));
                LinearLayout row = shelf();
                List<View> cards = new ArrayList<>();
                for (Title t : titles) {
                    View card = posterCard(t);
                    row.addView(card);
                    cards.add(card);
                }
                // Where to watch, per title, as it arrives.
                for (int i = 0; i < titles.size(); i++) {
                    Title t = titles.get(i);
                    View card = cards.get(i);
                    net.execute(() -> {
                        List<String> where = providers(t);
                        if (gen != generation.get()) return;
                        main.post(() -> {
                            t.apps.clear();
                            t.apps.addAll(where);
                            fillBadges(card, t);
                        });
                    });
                }
            });
        });
    }

    private List<ResolveInfo> matchingApps(String q) {
        PackageManager pm = getPackageManager();
        String needle = q.toLowerCase(Locale.ROOT);
        List<ResolveInfo> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) continue;
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
            if (label.contains(needle)) out.add(ri);
            if (out.size() >= 8) break;
        }
        return out;
    }

    private List<Title> tmdbSearch(String q) {
        List<Title> out = new ArrayList<>();
        try {
            JSONObject json = get("https://api.themoviedb.org/3/search/multi?include_adult=false&language=en-US&page=1&query="
                    + URLEncoder.encode(q, "UTF-8"));
            if (json == null) return out;
            JSONArray arr = json.optJSONArray("results");
            for (int i = 0; arr != null && i < arr.length() && out.size() < 14; i++) {
                JSONObject r = arr.getJSONObject(i);
                String kind = r.optString("media_type");
                if (!"movie".equals(kind) && !"tv".equals(kind)) continue;
                Title t = new Title();
                t.id = r.optInt("id");
                t.kind = kind;
                t.name = r.optString("movie".equals(kind) ? "title" : "name");
                String date = r.optString("movie".equals(kind) ? "release_date" : "first_air_date", "");
                t.year = date.length() >= 4 ? date.substring(0, 4) : "";
                String p = r.optString("poster_path", "");
                t.poster = p.isEmpty() || "null".equals(p) ? null : "https://image.tmdb.org/t/p/w342" + p;
                out.add(t);
            }
        } catch (Exception ignored) {
            // Offline or a bad token: apps still show.
        }
        return out;
    }

    /** Installed apps that stream (or show free with ads) this title in the US, in provider order. */
    private List<String> providers(Title t) {
        LinkedHashMap<String, Boolean> pkgs = new LinkedHashMap<>();
        try {
            JSONObject json = get("https://api.themoviedb.org/3/" + t.kind + "/" + t.id + "/watch/providers");
            JSONObject us = json == null ? null : json.optJSONObject("results");
            us = us == null ? null : us.optJSONObject("US");
            if (us != null) {
                for (String group : new String[]{"flatrate", "free", "ads"}) {
                    JSONArray arr = us.optJSONArray(group);
                    for (int i = 0; arr != null && i < arr.length(); i++) {
                        String pkg = PROVIDERS.get(arr.getJSONObject(i).optInt("provider_id"));
                        if (pkg != null && installed(pkg)) pkgs.put(pkg, true);
                    }
                }
            }
        } catch (Exception ignored) {
            // No badges then.
        }
        return new ArrayList<>(pkgs.keySet());
    }

    private JSONObject get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 2 * 1024 * 1024) out.write(buf, 0, n);
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally {
            c.disconnect();
        }
    }

    // ---- opening -----------------------------------------------------------------------------------

    private void pick(Title t) {
        if (t.apps.isEmpty()) {
            Toast.makeText(this, t.name + " isn’t streaming on your apps", Toast.LENGTH_SHORT).show();
            return;
        }
        if (t.apps.size() == 1) {
            openIn(t.apps.get(0), t.name);
            return;
        }
        String[] names = new String[t.apps.size()];
        for (int i = 0; i < names.length; i++) names[i] = label(t.apps.get(i));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Watch " + t.name + " on")
                .setItems(names, (d, which) -> openIn(t.apps.get(which), t.name))
                .show();
    }

    /** The app's own search with the title when it takes one, else a search link it handles, else the app. */
    private void openIn(String pkg, String query) {
        PackageManager pm = getPackageManager();
        if (query.isEmpty()) {
            Intent app = ContinueWatching.launchIntent(pm, pkg);
            if (app != null) startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent[] tries = {
                new Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query),
                SEARCH_LINKS.containsKey(pkg) ? new Intent(Intent.ACTION_VIEW,
                        Uri.parse(SEARCH_LINKS.get(pkg) + Uri.encode(query))).setPackage(pkg) : null,
                ContinueWatching.launchIntent(pm, pkg),
        };
        for (Intent i : tries) {
            if (i == null || i.resolveActivity(pm) == null) continue;
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException ignored) {
                // Try the next way in.
            }
        }
        Toast.makeText(this, "Couldn’t open " + label(pkg), Toast.LENGTH_SHORT).show();
    }

    // ---- views -------------------------------------------------------------------------------------

    private TextView heading(String s) {
        TextView t = text(s, 20, medium, Color.WHITE);
        t.setPadding(0, dp(30), 0, dp(4));
        return t;
    }

    private LinearLayout shelf() {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.setPadding(0, dp(12), 0, dp(12));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setPadding(dp(4), 0, dp(40), 0);
        h.addView(row);
        results.addView(h);
        return row;
    }

    private View appTile(ResolveInfo ri) {
        PackageManager pm = getPackageManager();
        FrameLayout card = card(dp(208), dp(117));
        ImageView img = new ImageView(this);
        Drawable banner = ri.activityInfo.loadBanner(pm);
        if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
        img.setImageDrawable(banner != null ? banner : ri.loadIcon(pm));
        img.setScaleType(banner != null ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        String pkg = ri.activityInfo.packageName;
        card.setOnClickListener(v -> openIn(pkg, ""));
        card.setContentDescription(String.valueOf(ri.loadLabel(pm)));
        return withGap(card);
    }

    private View posterCard(Title t) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        FrameLayout card = card(dp(150), dp(225));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (t.poster == null) {
            TextView name = text(t.name, 16, medium, Color.WHITE);
            name.setGravity(Gravity.CENTER);
            name.setPadding(dp(10), 0, dp(10), 0);
            card.addView(name, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        } else {
            loadPoster(t.poster, img);
        }
        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badges.setTag("badges");
        badges.setPadding(dp(6), 0, dp(6), dp(6));
        card.addView(badges, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));
        card.setOnClickListener(v -> pick(t));
        card.setContentDescription(t.name);
        col.addView(card);
        TextView label = text(t.name + (t.year.isEmpty() ? "" : "  ·  " + t.year), 14, regular, 0xD9FFFFFF);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setPadding(0, dp(10), 0, 0);
        col.addView(label, new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView where = text(" ", 12, regular, 0x99FFFFFF);
        where.setTag("where");
        where.setSingleLine(true);
        where.setEllipsize(android.text.TextUtils.TruncateAt.END);
        where.setPadding(0, dp(4), 0, 0);
        col.addView(where, new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT));
        return withGap(col);
    }

    /** The apps that have it: small app icons on the poster and their names under it. */
    private void fillBadges(View cell, Title t) {
        LinearLayout badges = cell.findViewWithTag("badges");
        TextView where = cell.findViewWithTag("where");
        if (badges == null || where == null) return;
        badges.removeAllViews();
        PackageManager pm = getPackageManager();
        StringBuilder names = new StringBuilder();
        for (String pkg : t.apps) {
            ImageView icon = new ImageView(this);
            try {
                icon.setImageDrawable(pm.getApplicationIcon(pkg));
            } catch (PackageManager.NameNotFoundException ignored) {
                continue;
            }
            GradientDrawable chip = new GradientDrawable();
            chip.setCornerRadius(8 * density);
            chip.setColor(0xCC000000);
            icon.setBackground(chip);
            icon.setPadding(dp(3), dp(3), dp(3), dp(3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
            lp.rightMargin = dp(4);
            badges.addView(icon, lp);
            if (names.length() > 0) names.append(", ");
            names.append(label(pkg));
        }
        where.setText(t.apps.isEmpty() ? "Not on your apps" : names.toString());
    }

    private FrameLayout card(int w, int h) {
        FrameLayout card = new FrameLayout(this);
        card.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        card.setFocusable(true);
        card.setClickable(true);
        float r = 12 * density;
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(r);
        bg.setColor(0xFF22252E);
        card.setBackground(bg);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(r);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(3), Color.WHITE);
        ring.setAlpha(0);
        card.setForeground(ring);
        card.setOnFocusChangeListener((v, f) -> {
            ring.setAlpha(f ? 255 : 0);
            v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).translationZ(f ? 14 * density : 0f)
                    .setDuration(140).start();
        });
        return card;
    }

    private View withGap(View v) {
        LinearLayout.LayoutParams lp = v.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) v.getLayoutParams()
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(26);
        v.setLayoutParams(lp);
        return v;
    }

    private void loadPoster(String url, ImageView into) {
        Bitmap cached = POSTERS.get(url);
        if (cached != null) {
            into.setImageBitmap(cached);
            return;
        }
        into.setTag(url);
        net.execute(() -> {
            Bitmap bmp = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(10000);
                if (c.getResponseCode() == 200) {
                    try (InputStream in = c.getInputStream()) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inPreferredConfig = Bitmap.Config.RGB_565;
                        bmp = BitmapFactory.decodeStream(in, null, o);
                    }
                }
            } catch (Exception ignored) {
                // Name-only card then.
            } finally {
                if (c != null) c.disconnect();
            }
            if (bmp == null) return;
            POSTERS.put(url, bmp);
            Bitmap done = bmp;
            main.post(() -> {
                if (url.equals(into.getTag())) into.setImageBitmap(done);
            });
        });
    }

    private View firstFocusable(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.isFocusable()) return c;
            if (c instanceof ViewGroup) {
                View f = firstFocusable((ViewGroup) c);
                if (f != null) return f;
            }
        }
        return null;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        // Down from the box goes to the results (the keyboard may have taken Down otherwise).
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN && box.hasFocus()) {
            View first = firstFocusable(results);
            if (first != null) {
                first.requestFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getApplicationInfo(pkg, 0);
            return getPackageManager().getApplicationEnabledSetting(pkg)
                    != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String label(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
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
}
