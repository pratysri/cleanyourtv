package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The weather panel (hisense-home fork, after Free My TV's drawer): selecting the weather line on Home opens it over
 * a blurred Home. Now, sunrise and sunset, the next eight hours with the chance of rain, five days, and a °F/°C
 * switch. Open-Meteo, no key. Back closes it.
 */
final class WeatherPanel {
    private WeatherPanel() { }

    /** {@code unitChanged} runs after the °F/°C switch, so Home's weather line can follow. */
    static void show(Activity a, Runnable unitChanged) {
        FolderDialog.withBlurredHome(a, backdrop -> {
            Dialog dialog = new Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
            Window w = dialog.getWindow();
            if (w != null) w.setBackgroundDrawable(backdrop != null ? backdrop : new ColorDrawable(0xF0000000));
            float d = a.getResources().getDisplayMetrics().density;
            LinearLayout sheet = new LinearLayout(a);
            sheet.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(28 * d);
            sheet.setPadding(pad, Math.round(22 * d), pad, Math.round(18 * d));
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x2EFFFFFF, 0x12FFFFFF});
            g.setCornerRadius(28 * d);
            g.setStroke(Math.round(1.5f * d), 0x59FFFFFF);
            sheet.setBackground(g);
            sheet.setFocusable(true);   // something to hold focus so Back reaches the dialog
            FrameLayout root = new FrameLayout(a);
            root.addView(sheet, new FrameLayout.LayoutParams(Math.round(600 * d), ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
            dialog.setContentView(root);
            dialog.show();
            load(a, dialog, sheet, d, unitChanged);
        });
    }

    private static void load(Activity a, Dialog dialog, LinearLayout sheet, float d, Runnable unitChanged) {
        sheet.removeAllViews();
        sheet.addView(text(a, "Loading the forecast for " + HomePrefs.city(a) + "…", 17, R.font.outfit_light,
                0xD9FFFFFF));
        sheet.requestFocus();
        new Thread(() -> {
            JSONObject json = fetch(a);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!dialog.isShowing()) return;
                sheet.removeAllViews();
                if (json == null) {
                    sheet.addView(text(a, "The forecast couldn’t load. Check the TV’s internet.", 17,
                            R.font.outfit_light, Color.WHITE));
                    return;
                }
                fill(a, sheet, json, d, () -> {
                    // °F ⇄ °C: saved for Home and the screensaver too, then the panel reloads in the new unit.
                    HomePrefs.setFahrenheitNow(a, !HomePrefs.fahrenheit(a));
                    if (unitChanged != null) unitChanged.run();
                    load(a, dialog, sheet, d, unitChanged);
                });
            });
        }, "weather-panel").start();
    }

    private static JSONObject fetch(Activity a) {
        HttpURLConnection c = null;
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + HomePrefs.latitude(a)
                    + "&longitude=" + HomePrefs.longitude(a)
                    + "&current=temperature_2m,apparent_temperature,weather_code,is_day,relative_humidity_2m,wind_speed_10m"
                    + "&hourly=temperature_2m,precipitation_probability,weather_code,is_day"
                    + "&daily=temperature_2m_max,temperature_2m_min,weather_code,sunrise,sunset,precipitation_probability_max"
                    + "&forecast_days=6&timezone=auto"
                    + (HomePrefs.fahrenheit(a) ? "&temperature_unit=fahrenheit&wind_speed_unit=mph" : "");
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 512 * 1024) out.write(buf, 0, n);
            }
            return new JSONObject(out.toString("UTF-8"));
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void fill(Activity a, LinearLayout sheet, JSONObject json, float d, Runnable switchUnit) {
        try {
            JSONObject now = json.getJSONObject("current");
            JSONObject daily = json.getJSONObject("daily");
            JSONObject hourly = json.getJSONObject("hourly");
            boolean day = now.optInt("is_day", 1) == 1;
            boolean f = HomePrefs.fahrenheit(a);
            int code = now.getInt("weather_code");

            // Now, with the unit switch on the right.
            LinearLayout top = row(a);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(text(a, symbol(code, day), 34, R.font.outfit_regular, Color.WHITE));
            LinearLayout nowCol = col(a);
            nowCol.setPadding(Math.round(14 * d), 0, 0, 0);
            nowCol.addView(text(a, Math.round(now.getDouble("temperature_2m")) + "°  "
                    + Weather.describe(code, day), 28, R.font.outfit_light, Color.WHITE));
            nowCol.addView(text(a, HomePrefs.city(a) + "  ·  feels like "
                    + Math.round(now.getDouble("apparent_temperature")) + "°  ·  humidity "
                    + now.optInt("relative_humidity_2m") + "%  ·  wind " + Math.round(now.optDouble("wind_speed_10m"))
                    + (f ? " mph" : " km/h"), 13, R.font.outfit_regular, 0xB3FFFFFF));
            nowCol.addView(text(a, "Sunrise " + clock(daily.getJSONArray("sunrise").getString(0)) + "  ·  Sunset "
                    + clock(daily.getJSONArray("sunset").getString(0)), 13, R.font.outfit_regular, 0xB3FFFFFF));
            top.addView(nowCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView unit = unitSwitch(a, f, d);
            unit.setOnClickListener(v -> switchUnit.run());
            top.addView(unit);
            sheet.addView(top);

            // The next eight hours from now.
            sheet.addView(label(a, "Next hours", d));
            LinearLayout hours = row(a);
            JSONArray times = hourly.getJSONArray("time");
            String nowHour = now.optString("time", "").length() >= 13 ? now.getString("time").substring(0, 13) : "";
            int start = 0;
            for (int i = 0; i < times.length(); i++) {
                if (times.getString(i).substring(0, 13).compareTo(nowHour) >= 0) {
                    start = i;
                    break;
                }
            }
            for (int i = start; i < Math.min(times.length(), start + 8); i++) {
                int rain = hourly.getJSONArray("precipitation_probability").optInt(i, 0);
                hours.addView(cell(a, i == start ? "Now" : hour(times.getString(i)),
                        symbol(hourly.getJSONArray("weather_code").getInt(i),
                                hourly.getJSONArray("is_day").optInt(i, 1) == 1),
                        Math.round(hourly.getJSONArray("temperature_2m").getDouble(i)) + "°",
                        rain >= 10 ? rain + "%" : " "), new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            sheet.addView(hours);

            // Five days, side by side.
            sheet.addView(label(a, "Next 5 days", d));
            LinearLayout days = row(a);
            JSONArray dates = daily.getJSONArray("time");
            for (int i = 1; i < Math.min(dates.length(), 6); i++) {
                int rain = daily.getJSONArray("precipitation_probability_max").optInt(i, 0);
                days.addView(cell(a, weekday(dates.getString(i)),
                        symbol(daily.getJSONArray("weather_code").getInt(i), true),
                        Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i)) + "°  "
                                + Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i)) + "°",
                        rain >= 10 ? rain + "% rain" : " "), new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            sheet.addView(days);
            TextView credit = text(a, "Weather from Open-Meteo", 10, R.font.outfit_regular, 0x66FFFFFF);
            credit.setPadding(0, Math.round(10 * d), 0, 0);
            sheet.addView(credit);
            unit.requestFocus();
        } catch (Exception e) {
            sheet.addView(text(a, "The forecast came back in a shape this panel doesn’t read.", 16,
                    R.font.outfit_light, Color.WHITE));
        }
    }

    /** One column: a heading, a picture, the temperature and the chance of rain. */
    private static LinearLayout cell(Activity a, String head, String pic, String temp, String rain) {
        LinearLayout c = col(a);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(text(a, head, 12, R.font.outfit_regular, 0xB3FFFFFF));
        c.addView(text(a, pic, 18, R.font.outfit_regular, Color.WHITE));
        c.addView(text(a, temp, 15, R.font.outfit_medium, Color.WHITE));
        c.addView(text(a, rain, 11, R.font.outfit_regular, 0xFF8EC5FF));
        for (int i = 0; i < c.getChildCount(); i++) ((TextView) c.getChildAt(i)).setGravity(Gravity.CENTER_HORIZONTAL);
        return c;
    }

    /** The °F / °C switch: both units, the one in use bright; OK changes it. */
    private static TextView unitSwitch(Activity a, boolean fahrenheit, float d) {
        TextView t = text(a, "", 15, R.font.outfit_medium, Color.WHITE);
        t.setFocusable(true);
        t.setClickable(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Math.round(14 * d), Math.round(7 * d), Math.round(14 * d), Math.round(7 * d));
        t.setContentDescription(fahrenheit ? "Switch to Celsius" : "Switch to Fahrenheit");
        GradientDrawable idle = new GradientDrawable();
        idle.setCornerRadius(18 * d);
        idle.setColor(0x1FFFFFFF);
        idle.setStroke(Math.max(1, Math.round(d)), 0x59FFFFFF);
        GradientDrawable lit = new GradientDrawable();
        lit.setCornerRadius(18 * d);
        lit.setColor(0xF2FFFFFF);
        Runnable paint = () -> {
            boolean focused = t.isFocused();
            int on = focused ? 0xFF111318 : Color.WHITE, off = focused ? 0x80111318 : 0x73FFFFFF;
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            append(sb, "°F", fahrenheit ? on : off);
            append(sb, "   ", off);
            append(sb, "°C", fahrenheit ? off : on);
            t.setText(sb);
            t.setBackground(focused ? lit : idle);
        };
        paint.run();
        t.setOnFocusChangeListener((v, focused) -> paint.run());
        return t;
    }

    private static void append(android.text.SpannableStringBuilder sb, String s, int color) {
        int from = sb.length();
        sb.append(s);
        sb.setSpan(new android.text.style.ForegroundColorSpan(color), from, sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** A small picture for the WMO weather code. */
    static String symbol(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code <= 2) return day ? "⛅" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 67) return "🌧️";
        if (code >= 71 && code <= 77) return "❄️";
        if (code >= 80 && code <= 82) return "🌦️";
        if (code >= 85 && code <= 86) return "🌨️";
        if (code >= 95) return "⛈️";
        return "☁️";
    }

    private static String clock(String iso) {
        try {
            Date t = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(iso);
            return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(t);
        } catch (Exception e) {
            return iso.length() >= 16 ? iso.substring(11, 16) : iso;
        }
    }

    private static String hour(String iso) {
        try {
            Date t = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(iso);
            return new SimpleDateFormat("h a", Locale.getDefault()).format(t);
        } catch (Exception e) {
            return iso.length() >= 13 ? iso.substring(11, 13) : iso;
        }
    }

    private static String weekday(String iso) {
        try {
            Date t = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso);
            return new SimpleDateFormat("EEE", Locale.getDefault()).format(t);
        } catch (Exception e) {
            return iso;
        }
    }

    private static TextView label(Activity a, String s, float d) {
        TextView t = text(a, s.toUpperCase(Locale.getDefault()), 11, R.font.outfit_regular, 0x99FFFFFF);
        t.setLetterSpacing(0.14f);
        t.setPadding(0, Math.round(16 * d), 0, Math.round(6 * d));
        return t;
    }

    private static LinearLayout row(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private static LinearLayout col(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private static TextView text(Activity a, String s, float sp, int font, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        try {
            t.setTypeface(a.getResources().getFont(font));
        } catch (RuntimeException e) {
            t.setTypeface(Typeface.DEFAULT);
        }
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setPadding(0, 2, 0, 2);
        return t;
    }
}
