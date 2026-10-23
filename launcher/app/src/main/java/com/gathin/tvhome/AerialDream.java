package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The aerial screensaver (hisense-home fork, after Free My TV's): the theme's landscape photographs full screen, each
 * drifting slowly in and crossfading to the next, with its place name, and a glass panel with the time, the date and
 * the weather. The system still decides when it starts and when the TV sleeps. Any key ends it.
 */
public final class AerialDream extends DreamService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final List<File> photos = new ArrayList<>();
    private ImageView[] layers;
    private int front = 0, next = 0;
    private TextView place;
    private Weather weather;
    private long holdMs;
    private final Runnable advance = this::advance;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        float d = getResources().getDisplayMetrics().density;
        holdMs = HomePrefs.screensaverSeconds(this) * 1000L;
        Typeface light = font(R.font.outfit_light), regular = font(R.font.outfit_regular);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        layers = new ImageView[]{new ImageView(this), new ImageView(this)};
        for (ImageView l : layers) {
            l.setScaleType(ImageView.ScaleType.CENTER_CROP);
            l.setAlpha(0f);
            root.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x8C000000, 0x00000000, 0x00000000}));
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // The glass clock, bottom left.
        LinearLayout glass = new LinearLayout(this);
        glass.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(26 * d);
        glass.setPadding(pad, Math.round(18 * d), pad, Math.round(20 * d));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x40FFFFFF, 0x1AFFFFFF});
        g.setCornerRadius(30 * d);
        g.setStroke(Math.round(1.5f * d), 0x66FFFFFF);
        glass.setBackground(g);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        style(time, 84, light, Color.WHITE);
        glass.addView(time);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE d MMMM");
        date.setFormat24Hour("EEEE d MMMM");
        style(date, 18, regular, 0xE6FFFFFF);
        date.setLetterSpacing(0.06f);
        glass.addView(date);
        TextView weatherLine = new TextView(this);
        style(weatherLine, 18, regular, 0xD9FFFFFF);
        weatherLine.setPadding(0, Math.round(8 * d), 0, 0);
        weatherLine.setVisibility(View.GONE);
        glass.addView(weatherLine);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        glp.setMargins(Math.round(56 * d), 0, 0, Math.round(48 * d));
        root.addView(glass, glp);

        place = new TextView(this);
        style(place, 20, regular, 0xD9FFFFFF);
        place.setLetterSpacing(0.04f);
        place.setShadowLayer(8 * d, 0, 0, 0x99000000);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        plp.setMargins(0, 0, Math.round(56 * d), Math.round(52 * d));
        root.addView(place, plp);
        setContentView(root);

        if (!HomePrefs.latitude(this).isEmpty()) {
            weather = new Weather(weatherLine, HomePrefs.latitude(this), HomePrefs.longitude(this),
                    HomePrefs.fahrenheit(this));
        }
        loadPhotos();
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (weather != null) weather.onResume();
        if (photos.isEmpty()) return;
        next = 0;
        showNext();
    }

    @Override public void onDreamingStopped() {
        super.onDreamingStopped();
        main.removeCallbacks(advance);
        if (weather != null) weather.onPause();
    }

    @Override public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        main.removeCallbacks(advance);
        if (weather != null) weather.onDestroy();
        decoder.shutdownNow();
    }

    private void loadPhotos() {
        try {
            photos.addAll(Config.load(this).wallpapers);
        } catch (RuntimeException ignored) {
            // No theme: a black screen with the clock.
        }
        Collections.shuffle(photos);
    }

    private void advance() {
        showNext();
    }

    private void showNext() {
        if (photos.isEmpty()) return;
        File file = photos.get(next % photos.size());
        next++;
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        decoder.execute(() -> {
            Bitmap bmp = decode(file, w, h);
            main.post(() -> {
                if (bmp != null) crossfade(bmp, title(file.getName()));
                main.postDelayed(advance, holdMs);
            });
        });
    }

    /** The next photo fades in over the last one while it drifts slowly in, Ken Burns style. */
    private void crossfade(Bitmap bmp, String name) {
        ImageView in = layers[1 - front], out = layers[front];
        front = 1 - front;
        in.setImageBitmap(bmp);
        in.setScaleX(1.0f);
        in.setScaleY(1.0f);
        in.animate().cancel();
        in.animate().alpha(1f).setDuration(2200).start();
        in.animate().scaleX(1.08f).scaleY(1.08f).setDuration(holdMs + 2500).start();
        out.animate().alpha(0f).setDuration(2200).withEndAction(() -> out.setImageDrawable(null)).start();
        place.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            place.setText(name);
            place.animate().alpha(1f).setDuration(900).start();
        }).start();
    }

    private static Bitmap decode(File f, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** "07-greenland-evening.jpg" becomes "Greenland Evening". */
    static String title(String file) {
        String s = file.replaceFirst("\\.[A-Za-z]+$", "").replaceFirst("^\\d+[-_ ]*", "").replace('-', ' ').replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String w : s.trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(w.substring(0, 1).toUpperCase(Locale.getDefault())).append(w.substring(1));
        }
        return out.toString();
    }

    private void style(TextView t, float sp, Typeface tf, int color) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
    }

    private Typeface font(int res) {
        try {
            return getResources().getFont(res);
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }
}
