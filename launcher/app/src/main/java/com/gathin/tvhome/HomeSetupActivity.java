package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Home settings (hisense-home fork): everything this Home can change, on the TV, with the remote. The top bar's
 * Settings opens it; its first row goes on to the TV's own settings. Choices live in HomePrefs.
 */
public final class HomeSetupActivity extends Activity {
    static final String DREAM = "com.gathin.tvhome/com.gathin.tvhome.AerialDream";
    /** The screensaver this TV came with, put back when the aerial one is turned off. */
    static final String STOCK_DREAM = "com.google.android.apps.tv.dreamx/.service.Backdrop";
    private static final int VOICE_CITY = 11;
    private static final String[] GREETINGS = {"Welcome, both names", "Welcome back, who's watching",
            "Good evening, who's watching", "No greeting"};
    private static final int[] SAVER_SECONDS = {30, 45, 60, 120};

    private float density;
    private Typeface regular, medium, light;
    private LinearLayout list;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        regular = font(R.font.outfit_regular);
        medium = font(R.font.outfit_medium);
        light = font(R.font.outfit_light);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1A2236, 0xFF0B0D12, 0xFF07080B}));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(220), dp(44), dp(220), dp(48));
        scroll.addView(list);
        setContentView(scroll);
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        build();   // values may have changed in a sub-screen
    }

    private void build() {
        View focused = getCurrentFocus();
        Object focusTag = focused == null ? null : focused.getTag();
        list.removeAllViews();
        list.addView(text("Home settings", 40, medium, Color.WHITE));
        if (SafeMode.on(this)) {
            list.addView(note("Safe mode is on after a few crashes, so some extras are off for now. "
                    + "Installing an update ends it."));
        }

        section("TV");
        row("tv", "TV settings", "Picture, sound, network, inputs", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (RuntimeException e) {
                toast("The TV's settings couldn't open");
            }
        });
        row("look", "Layout & look", "Columns, rows, wallpaper, motion",
                v -> startActivity(new Intent(this, LauncherSettingsActivity.class)));

        section("Weather");
        row("city", "City", HomePrefs.city(this), v -> askCity());
        row("units", Profiles.enabled(this) ? "Temperature for " + Profiles.currentName(this) : "Temperature", HomePrefs.fahrenheit(this) ? "°F" : "°C", v -> {
            HomePrefs.setFahrenheit(this, !HomePrefs.fahrenheit(this));
            build();
        });

        section("Home");
        row("greeting", "Greeting", GREETINGS[HomePrefs.greetingStyle(this)], v -> {
            HomePrefs.setGreetingStyle(this, (HomePrefs.greetingStyle(this) + 1) % GREETINGS.length);
            build();
        });
        row("picker", "“Who’s watching?” when the TV wakes", onOff(HomePrefs.pickerOnWake(this)), v -> {
            HomePrefs.setPickerOnWake(this, !HomePrefs.pickerOnWake(this));
            build();
        });
        row("continue", "Continue watching", onOff(HomePrefs.continueWatching(this)), v -> {
            HomePrefs.setContinueWatching(this, !HomePrefs.continueWatching(this));
            build();
        });

        if (Profiles.enabled(this)) {
            section("Docks");
            String[] names = Profiles.names(this);
            for (int i = 0; i < names.length; i++) {
                final int who = i;
                row("dock" + i, names[i] + "’s dock", dockSummary(i), v -> editDock(who));
            }
        }

        section("Apps");
        row("apps", "On Home, in More Apps or hidden", "Choose where each app goes", v -> placeApps());

        section("Search");
        row("paid", "Services you pay for", paidSummary(), v -> choosePaid());

        section("Screensaver");
        boolean aerial = DREAM.equals(currentDream());
        row("saver", "Aerial screensaver", aerial ? "On" : "Off (Google’s)", v -> toggleDream(!aerial));
        row("saverTime", "Change the photo every", seconds(HomePrefs.screensaverSeconds(this)), v -> {
            int now = HomePrefs.screensaverSeconds(this), at = 0;
            for (int i = 0; i < SAVER_SECONDS.length; i++) if (SAVER_SECONDS[i] == now) at = i;
            HomePrefs.setScreensaverSeconds(this, SAVER_SECONDS[(at + 1) % SAVER_SECONDS.length]);
            build();
        });

        View restore = focusTag == null ? null : list.findViewWithTag(focusTag);
        if (restore != null) restore.requestFocus();
        else if (list.findViewWithTag("tv") != null) list.findViewWithTag("tv").requestFocus();
    }

    // ---- rows --------------------------------------------------------------------------------------

    private void section(String name) {
        TextView t = text(name.toUpperCase(Locale.getDefault()), 13, regular, 0x99FFFFFF);
        t.setLetterSpacing(0.14f);
        t.setPadding(dp(4), dp(28), 0, dp(8));
        list.addView(t);
    }

    private void row(String tag, String title, String value, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(22), dp(14), dp(22), dp(14));
        r.setFocusable(true);
        r.setClickable(true);
        r.setTag(tag);
        GradientDrawable idle = glass(0x12FFFFFF, 0x1FFFFFFF), lit = glass(0xF2FFFFFF, 0xFFFFFFFF);
        r.setBackground(idle);
        TextView t = text(title, 19, medium, Color.WHITE);
        TextView v = text(value, 17, regular, 0xB3FFFFFF);
        v.setGravity(Gravity.END);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        r.setOnFocusChangeListener((view, f) -> {
            view.setBackground(f ? lit : idle);
            t.setTextColor(f ? 0xFF111318 : Color.WHITE);
            v.setTextColor(f ? 0xB3111318 : 0xB3FFFFFF);
        });
        r.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        list.addView(r, lp);
    }

    private GradientDrawable glass(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(18 * density);
        g.setColor(fill);
        g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    // ---- city, by voice ----------------------------------------------------------------------------

    private void askCity() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your city")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            startActivityForResult(i, VOICE_CITY);
        } catch (RuntimeException e) {
            toast("Voice input isn’t available on this TV");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE_CITY || result != RESULT_OK || data == null) return;
        ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (heard == null || heard.isEmpty()) return;
        String q = heard.get(0).trim();
        new Thread(() -> {
            List<String[]> found = geocode(q);   // {label, name, lat, lon}
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (found.isEmpty()) {
                    toast("No city called “" + q + "”");
                    return;
                }
                String[] labels = new String[found.size()];
                for (int k = 0; k < labels.length; k++) labels[k] = found.get(k)[0];
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Which " + q + "?")
                        .setItems(labels, (dlg, which) -> {
                            String[] c = found.get(which);
                            HomePrefs.setPlace(this, c[1], Double.parseDouble(c[2]), Double.parseDouble(c[3]));
                            build();
                        }).show();
            });
        }, "geocode").start();
    }

    private static List<String[]> geocode(String q) {
        List<String[]> out = new ArrayList<>();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://geocoding-api.open-meteo.com/v1/search?count=6&language=en&name="
                    + URLEncoder.encode(q, "UTF-8")).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return out;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0 && buf.size() < 256 * 1024) buf.write(b, 0, n);
            }
            JSONArray arr = new JSONObject(buf.toString("UTF-8")).optJSONArray("results");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject r = arr.getJSONObject(i);
                String name = r.optString("name");
                String where = r.optString("admin1", "");
                String country = r.optString("country", "");
                String label = name + (where.isEmpty() ? "" : ", " + where) + (country.isEmpty() ? "" : ", " + country);
                out.add(new String[]{label, name, String.valueOf(r.getDouble("latitude")),
                        String.valueOf(r.getDouble("longitude"))});
            }
        } catch (Exception ignored) {
            // Nothing found then.
        } finally {
            if (c != null) c.disconnect();
        }
        return out;
    }

    // ---- docks -------------------------------------------------------------------------------------

    private String dockSummary(int who) {
        List<String> dock = HomePrefs.dock(this, who);
        if (dock == null) dock = buildDock(who);   // the build's dock for this person (profile_docks)
        if (dock == null) dock = new ArrayList<>(Config.load(this).dockApps);
        StringBuilder s = new StringBuilder();
        for (String pkg : dock) {
            if (s.length() > 0) s.append(", ");
            s.append(label(pkg));
        }
        return s.length() == 0 ? "Not set" : s.toString();
    }

    private List<String> buildDock(int who) {
        String name = Profiles.names(this)[who];
        for (String line : getResources().getStringArray(R.array.profile_docks)) {
            int eq = line.indexOf('=');
            if (eq <= 0 || !line.substring(0, eq).trim().equalsIgnoreCase(name)) continue;
            List<String> out = new ArrayList<>();
            for (String p : line.substring(eq + 1).split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
            return out;
        }
        return null;
    }

    /** Up to six apps, in the order they are ticked. */
    private void editDock(int who) {
        List<ResolveInfo> apps = tvApps();
        List<String> current = HomePrefs.dock(this, who);
        if (current == null) current = buildDock(who);
        if (current == null) current = new ArrayList<>();
        List<String> chosen = new ArrayList<>(current);
        String[] labels = new String[apps.size()];
        boolean[] ticked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            String pkg = apps.get(i).activityInfo.packageName;
            labels[i] = label(pkg);
            ticked[i] = chosen.contains(pkg);
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Profiles.names(this)[who] + "’s dock (up to 6, in the order you tick them)")
                .setMultiChoiceItems(labels, ticked, (d, which, on) -> {
                    String pkg = apps.get(which).activityInfo.packageName;
                    if (on) {
                        if (chosen.size() >= 6) {
                            toast("The dock holds six apps");
                            ((AlertDialog) d).getListView().setItemChecked(which, false);
                            return;
                        }
                        if (!chosen.contains(pkg)) chosen.add(pkg);
                    } else {
                        chosen.remove(pkg);
                    }
                })
                .setPositiveButton("Save", (d, w) -> {
                    HomePrefs.setDock(this, who, chosen);
                    build();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- where each app goes -----------------------------------------------------------------------

    private static final String[] PLACES = {"Home", "More Apps", "Hidden"};

    private void placeApps() {
        List<ResolveInfo> apps = tvApps();
        List<String> rows = new ArrayList<>();
        for (ResolveInfo ri : apps) rows.add(appRow(ri.activityInfo.packageName));
        ListView lv = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            String pkg = apps.get(pos).activityInfo.packageName;
            HomePrefs.setPlace(this, pkg, (HomePrefs.placeOf(this, pkg) + 1) % PLACES.length);
            rows.set(pos, appRow(pkg));
            adapter.notifyDataSetChanged();
        });
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("OK moves an app: Home, More Apps, Hidden")
                .setView(lv)
                .setPositiveButton("Done", null)
                .show();
    }

    private String appRow(String pkg) {
        return label(pkg) + "   →   " + PLACES[HomePrefs.placeOf(this, pkg)];
    }

    // ---- paid services, for Search -----------------------------------------------------------------

    private List<String> services() {
        Set<String> pkgs = new LinkedHashSet<>(SearchActivity.servicePackages());
        List<String> out = new ArrayList<>();
        for (String pkg : pkgs) if (installed(pkg)) out.add(pkg);
        return out;
    }

    private String paidSummary() {
        Set<String> unpaid = HomePrefs.unpaidServices(this);
        int paid = 0, all = 0;
        for (String pkg : services()) {
            all++;
            if (!unpaid.contains(pkg)) paid++;
        }
        return paid == all ? "All of them" : paid + " of " + all;
    }

    private void choosePaid() {
        List<String> pkgs = services();
        Set<String> unpaid = HomePrefs.unpaidServices(this);
        String[] labels = new String[pkgs.size()];
        boolean[] paid = new boolean[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) {
            labels[i] = label(pkgs.get(i));
            paid[i] = !unpaid.contains(pkgs.get(i));
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Search shows titles only on the services ticked here")
                .setMultiChoiceItems(labels, paid, (d, which, on) -> HomePrefs.setPaid(this, pkgs.get(which), on))
                .setPositiveButton("Done", (d, w) -> build())
                .show();
    }

    // ---- screensaver -------------------------------------------------------------------------------

    private String currentDream() {
        return Settings.Secure.getString(getContentResolver(), "screensaver_components");
    }

    /** Switches the TV's screensaver; needs WRITE_SECURE_SETTINGS, granted with pm grant at install. */
    private void toggleDream(boolean aerial) {
        try {
            Settings.Secure.putString(getContentResolver(), "screensaver_components", aerial ? DREAM : STOCK_DREAM);
            Settings.Secure.putInt(getContentResolver(), "screensaver_enabled", 1);
            toast(aerial ? "Aerial screensaver on" : "Google’s screensaver is back");
        } catch (SecurityException e) {
            toast("This build can’t change the screensaver yet (needs one grant over debugging)");
        }
        build();
    }

    private static String seconds(int s) {
        return s < 60 ? s + " seconds" : s == 60 ? "1 minute" : (s / 60) + " minutes";
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private List<ResolveInfo> tvApps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            if (ri.activityInfo == null || getPackageName().equals(ri.activityInfo.packageName)) continue;
            if (seen.add(ri.activityInfo.packageName)) out.add(ri);
        }
        out.sort((a, b) -> label(a.activityInfo.packageName).compareToIgnoreCase(label(b.activityInfo.packageName)));
        return out;
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

    private static String onOff(boolean on) {
        return on ? "On" : "Off";
    }

    private TextView note(String s) {
        TextView t = text(s, 15, regular, 0xFFFFC145);
        t.setPadding(dp(4), dp(16), 0, 0);
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

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
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
