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
 * a blurred Home. Now, sunrise and sunset, the next twelve hours with the chance of rain, and five days. Open-Meteo,
 * no key. Back closes it.
 */
final class WeatherPanel {
    private WeatherPanel() { }

    static void show(Activity a) {
        FolderDialog.withBlurredHome(a, backdrop -> {
            Dialog dialog = new Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
            Window w = dialog.getWindow();
            if (w != null) w.setBackgroundDrawable(backdrop != null ? backdrop : new ColorDrawable(0xF0000000));
            float d = a.getResources().getDisplayMetrics().density;
            LinearLayout sheet = new LinearLayout(a);
            sheet.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(40 * d);
            sheet.setPadding(pad, Math.round(32 * d), pad, Math.round(30 * d));
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x2EFFFFFF, 0x12FFFFFF});
            g.setCornerRadius(34 * d);
            g.setStroke(Math.round(1.5f * d), 0x59FFFFFF);
            sheet.setBackground(g);
            sheet.setFocusable(true);   // something to hold focus so Back reaches the dialog
            TextView loading = text(a, "Loading the forecast for " + HomePrefs.city(a) + "…", 20, R.font.outfit_light,
                    0xD9FFFFFF);
            sheet.addView(loading);
            FrameLayout root = new FrameLayout(a);
            root.addView(sheet, new FrameLayout.LayoutParams(Math.round(860 * d), ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
            dialog.setContentView(root);
            dialog.show();
            sheet.requestFocus();
            new Thread(() -> {
                JSONObject json = fetch(a);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!dialog.isShowing()) return;
                    sheet.removeAllViews();
                    if (json == null) {
                        sheet.addView(text(a, "The forecast couldn’t load. Check the TV’s internet.", 20,
                                R.font.outfit_light, Color.WHITE));
                    } else {
                        fill(a, sheet, json, d);
                    }
                });
            }, "weather-panel").start();
        });
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

    private static void fill(Activity a, LinearLayout sheet, JSONObject json, float d) {
        try {
            JSONObject now = json.getJSONObject("current");
            JSONObject daily = json.getJSONObject("daily");
            JSONObject hourly = json.getJSONObject("hourly");
            boolean day = now.optInt("is_day", 1) == 1;
            int code = now.getInt("weather_code");

            LinearLayout top = row(a);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(text(a, symbol(code, day), 54, R.font.outfit_regular, Color.WHITE));
            LinearLayout nowCol = col(a);
            nowCol.setPadding(Math.round(20 * d), 0, 0, 0);
            nowCol.addView(text(a, Math.round(now.getDouble("temperature_2m")) + "°  "
                    + Weather.describe(code, day), 40, R.font.outfit_light, Color.WHITE));
            nowCol.addView(text(a, HomePrefs.city(a) + "  ·  feels like "
                    + Math.round(now.getDouble("apparent_temperature")) + "°  ·  humidity "
                    + now.optInt("relative_humidity_2m") + "%  ·  wind " + Math.round(now.optDouble("wind_speed_10m"))
                    + (HomePrefs.fahrenheit(a) ? " mph" : " km/h"), 16, R.font.outfit_regular, 0xB3FFFFFF));
            top.addView(nowCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout sun = col(a);
            sun.setGravity(Gravity.END);
            sun.addView(text(a, "☀  Sunrise " + clock(daily.getJSONArray("sunrise").getString(0)), 16,
                    R.font.outfit_regular, 0xD9FFFFFF));
            sun.addView(text(a, "☾  Sunset " + clock(daily.getJSONArray("sunset").getString(0)), 16,
                    R.font.outfit_regular, 0xD9FFFFFF));
            top.addView(sun);
            sheet.addView(top);

            // The next twelve hours from now.
            sheet.addView(label(a, "Next 12 hours", d));
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
            for (int i = start; i < Math.min(times.length(), start + 12); i++) {
                LinearLayout cell = col(a);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.addView(text(a, i == start ? "Now" : hour(times.getString(i)), 14, R.font.outfit_regular, 0xB3FFFFFF));
                cell.addView(text(a, symbol(hourly.getJSONArray("weather_code").getInt(i),
                        hourly.getJSONArray("is_day").optInt(i, 1) == 1), 24, R.font.outfit_regular, Color.WHITE));
                cell.addView(text(a, Math.round(hourly.getJSONArray("temperature_2m").getDouble(i)) + "°", 18,
                        R.font.outfit_medium, Color.WHITE));
                int rain = hourly.getJSONArray("precipitation_probability").optInt(i, 0);
                cell.addView(text(a, rain >= 10 ? rain + "%" : " ", 13, R.font.outfit_regular, 0xFF8EC5FF));
                hours.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            sheet.addView(hours);

            // Five days.
            sheet.addView(label(a, "Next 5 days", d));
            JSONArray days = daily.getJSONArray("time");
            for (int i = 1; i < Math.min(days.length(), 6); i++) {
                LinearLayout r = row(a);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(0, Math.round(6 * d), 0, Math.round(6 * d));
                r.addView(text(a, weekday(days.getString(i)), 18, R.font.outfit_medium, Color.WHITE),
                        new LinearLayout.LayoutParams(Math.round(150 * d), ViewGroup.LayoutParams.WRAP_CONTENT));
                int dc = daily.getJSONArray("weather_code").getInt(i);
                r.addView(text(a, symbol(dc, true) + "  " + Weather.describe(dc, true), 18, R.font.outfit_regular,
                        0xE6FFFFFF), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                int rain = daily.getJSONArray("precipitation_probability_max").optInt(i, 0);
                r.addView(text(a, rain >= 10 ? rain + "% rain" : "", 15, R.font.outfit_regular, 0xFF8EC5FF),
                        new LinearLayout.LayoutParams(Math.round(120 * d), ViewGroup.LayoutParams.WRAP_CONTENT));
                r.addView(text(a, "H " + Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i)) + "°   L "
                        + Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i)) + "°", 18,
                        R.font.outfit_regular, Color.WHITE));
                sheet.addView(r);
            }
            sheet.addView(text(a, "Weather from Open-Meteo", 12, R.font.outfit_regular, 0x66FFFFFF));
        } catch (Exception e) {
            sheet.addView(text(a, "The forecast came back in a shape this panel doesn’t read.", 18,
                    R.font.outfit_light, Color.WHITE));
        }
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
            return new SimpleDateFormat("EEEE", Locale.getDefault()).format(t);
        } catch (Exception e) {
            return iso;
        }
    }

    private static TextView label(Activity a, String s, float d) {
        TextView t = text(a, s.toUpperCase(Locale.getDefault()), 13, R.font.outfit_regular, 0x99FFFFFF);
        t.setLetterSpacing(0.14f);
        t.setPadding(0, Math.round(26 * d), 0, Math.round(10 * d));
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
