package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A one-line weather readout under the clock (hisense-home fork), from Open-Meteo (free, no key) for config.xml's
 * latitude/longitude: "64° · Partly cloudy · H 70° L 52°". Fetched while Home is on screen, at most every 20
 * minutes; a failed fetch keeps the last line, and with no line yet the view stays hidden.
 */
final class Weather {
    private static final long REFRESH_MS = 20 * 60 * 1000;
    private final TextView view;
    private final String lat, lon;
    private final boolean fahrenheit;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private long lastFetch = 0;
    private final Runnable tick = this::refresh;

    Weather(TextView view, String lat, String lon, boolean fahrenheit) {
        this.view = view;
        this.lat = lat;
        this.lon = lon;
        this.fahrenheit = fahrenheit;
    }

    void onResume() {
        if (System.currentTimeMillis() - lastFetch > REFRESH_MS) refresh();
        main.postDelayed(tick, REFRESH_MS);
    }

    void onPause() {
        main.removeCallbacks(tick);
    }

    void onDestroy() {
        onPause();
        bg.shutdownNow();
    }

    private void refresh() {
        main.removeCallbacks(tick);
        main.postDelayed(tick, REFRESH_MS);
        bg.execute(() -> {
            String line = fetch();
            if (line == null) return;
            lastFetch = System.currentTimeMillis();
            main.post(() -> {
                view.setText(line);
                view.setVisibility(android.view.View.VISIBLE);
            });
        });
    }

    private String fetch() {
        HttpURLConnection c = null;
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                    + "&forecast_days=1&timezone=auto" + (fahrenheit ? "&temperature_unit=fahrenheit" : "");
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, n);
            }
            JSONObject json = new JSONObject(out.toString("UTF-8"));
            JSONObject now = json.getJSONObject("current");
            JSONObject daily = json.getJSONObject("daily");
            long temp = Math.round(now.getDouble("temperature_2m"));
            long hi = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
            long lo = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));
            String sky = describe(now.getInt("weather_code"), now.optInt("is_day", 1) == 1);
            return String.format(Locale.US, "%d°  ·  %s  ·  H %d°  L %d°", temp, sky, hi, lo);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** WMO weather interpretation codes, as Open-Meteo documents them. */
    static String describe(int code, boolean day) {
        if (code == 0) return day ? "Sunny" : "Clear";
        if (code == 1) return day ? "Mostly sunny" : "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return code >= 65 ? "Heavy rain" : "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 85 && code <= 86) return "Snow showers";
        if (code >= 95) return "Thunderstorms";
        return "—";
    }
}
