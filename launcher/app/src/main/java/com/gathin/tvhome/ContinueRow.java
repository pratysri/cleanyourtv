package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Continue watching row above the dock (hisense-home fork): wide artwork cards from ContinueWatching, each
 * opening its show inside the app. Hidden while it has nothing to show, and while focus is below the dock.
 */
final class ContinueRow {
    interface Host {
        /** A card took focus: the dock's glass and top shelf should step aside. */
        void onContinueFocused();
        /** Down from the row. */
        void focusDockFromRow();
        /** Up from the row. */
        void focusTopBarFromRow();
    }

    static final int MAX_ITEMS = 20;
    private static final int CARD_W_DP = 252, CARD_H_DP = 142, GAP_DP = 22, LABEL_ROOM_DP = 34;

    private final Activity activity;
    private final Host host;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ExecutorService art = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    final LinearLayout container;
    private View scrim;
    private final HorizontalScrollView scroller;
    private final LinearLayout cards;
    private final List<ContinueWatching.Item> items = new ArrayList<>();
    private final Map<String, String> appNames = new HashMap<>();
    private Runnable stopObserving;
    private int lastIndex = 0;
    private boolean shown = true;
    private float bottomY = -1;
    private final Runnable reload = this::refresh;

    ContinueRow(Activity activity, FrameLayout root, Host host) {
        this.activity = activity;
        this.host = host;
        this.density = activity.getResources().getDisplayMetrics().density;
        Typeface medium = font(R.font.outfit_medium), regular = font(R.font.outfit_regular);

        container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setClipChildren(false);
        container.setClipToPadding(false);
        container.setVisibility(View.GONE);

        TextView label = new TextView(activity);
        label.setText(R.string.continue_watching);
        label.setAllCaps(true);
        label.setTextColor(0xB3FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setLetterSpacing(0.14f);
        label.setTypeface(regular);
        label.setPadding(dp(66), 0, 0, dp(12));
        container.addView(label);

        scroller = new HorizontalScrollView(activity);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setSmoothScrollingEnabled(true);
        scroller.setFocusable(false);
        scroller.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        cards = new LinearLayout(activity);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setClipChildren(false);
        cards.setClipToPadding(false);
        cards.setPadding(dp(66), dp(10), dp(66), dp(10));
        scroller.addView(cards);
        container.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(CARD_H_DP + 20)));
        this.medium = medium;
        this.regular = regular;

        // Above the sky, wallpaper and ambient glow (so the glow does not tint the art), below the top bar and grid.
        View hero = root.findViewById(R.id.hero);
        int at = hero == null ? 1 : root.indexOfChild(hero) + 1;
        // Compact mode's fade: clear over the top half, dark under the row and dock.
        scrim = new View(activity);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x00000000, 0x8C000000, 0xE6000000}));
        scrim.setAlpha(0f);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(scrim, at++, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(container, at, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
    }

    private final Typeface medium, regular;

    private Typeface font(int res) {
        try {
            return activity.getResources().getFont(res);
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    boolean hasItems() {
        return !items.isEmpty();
    }

    /** Full height of the row, label included, for placing it above the dock. */
    int heightPx() {
        return dp(LABEL_ROOM_DP + CARD_H_DP + 20);
    }

    // ---- lifecycle ---------------------------------------------------------------------------------

    void onResume() {
        if (stopObserving == null) {
            stopObserving = ContinueWatching.observe(activity, () -> {
                main.removeCallbacks(reload);
                main.postDelayed(reload, 800);   // apps write several rows in a burst
            });
        }
        refresh();
    }

    void onPause() {
        main.removeCallbacks(reload);
        if (stopObserving != null) stopObserving.run();
        stopObserving = null;
    }

    void onDestroy() {
        onPause();
        loader.shutdownNow();
        art.shutdownNow();
    }

    void refresh() {
        loader.execute(() -> {
            List<ContinueWatching.Item> fresh = ContinueWatching.load(activity, MAX_ITEMS);
            main.post(() -> {
                if (activity.isDestroyed()) return;
                if (sameAs(fresh)) return;
                boolean hadFocus = container.hasFocus();
                items.clear();
                items.addAll(fresh);
                rebuild();
                if (hadFocus) focus();
            });
        });
    }

    private boolean sameAs(List<ContinueWatching.Item> fresh) {
        if (fresh.size() != items.size()) return false;
        for (int i = 0; i < fresh.size(); i++) {
            ContinueWatching.Item a = fresh.get(i), b = items.get(i);
            if (!a.pkg.equals(b.pkg) || !a.title.equals(b.title) || a.lastWatched != b.lastWatched
                    || a.progress != b.progress) return false;
        }
        return true;
    }

    // ---- placement and focus -----------------------------------------------------------------------

    /** Parks the row so its bottom edge sits at y (root coordinates), clear of the clock above. */
    void placeBottomAt(float y, float minTop) {
        bottomY = y;
        int top = Math.round(Math.max(minTop, y - heightPx()));
        // The resting place is the top margin; compact mode slides from it with translationY.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
        if (lp.topMargin != top) {
            lp.topMargin = top;
            container.setLayoutParams(lp);
        }
        normalTop = top;
        updateVisibility();
    }

    // ---- Apple TV style compact mode ---------------------------------------------------------------

    private int normalTop = 0;
    private boolean compact = false;
    private android.animation.ObjectAnimator slide, fade;

    boolean isCompact() {
        return compact;
    }

    /**
     * Compact: the row slides down so its label starts at compactTop (the screen's middle), over a soft dark
     * fade, leaving the wallpaper alone in the top half. Off: back to its resting place above the dock.
     */
    void setCompact(boolean on, float compactTop) {
        if (compact == on) return;
        compact = on;
        if (slide != null) slide.cancel();
        if (fade != null) fade.cancel();
        float to = on ? compactTop - normalTop : 0f;
        slide = android.animation.ObjectAnimator.ofFloat(container, View.TRANSLATION_Y, to);
        slide.setDuration(on ? 420 : 320);
        slide.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
        slide.start();
        fade = android.animation.ObjectAnimator.ofFloat(scrim, View.ALPHA, on ? 1f : 0f);
        fade.setDuration(on ? 420 : 260);
        fade.start();
    }

    /** Shown while focus is on the dock, the row or the top bar; hidden while the grid below is scrolled up. */
    void setShown(boolean show) {
        if (shown == show) return;
        shown = show;
        container.animate().cancel();
        if (show) {
            updateVisibility();
            container.animate().alpha(1f).setDuration(200).start();
        } else {
            container.animate().alpha(0f).setDuration(150)
                    .withEndAction(this::updateVisibility).start();
        }
    }

    private void updateVisibility() {
        boolean visible = hasItems() && bottomY >= 0 && (shown || container.getAlpha() > 0f);
        container.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible && shown) container.setAlpha(1f);
    }

    boolean focus() {
        if (!hasItems() || container.getVisibility() != View.VISIBLE) return false;
        int i = Math.max(0, Math.min(lastIndex, cards.getChildCount() - 1));
        View card = cards.getChildAt(i);
        return card != null && card.requestFocus();
    }

    boolean hasFocus() {
        return container.hasFocus();
    }

    // ---- cards -------------------------------------------------------------------------------------

    private void rebuild() {
        cards.removeAllViews();
        lastIndex = Math.min(lastIndex, Math.max(0, items.size() - 1));
        for (int i = 0; i < items.size(); i++) cards.addView(card(items.get(i), i));
        updateVisibility();
    }

    private View card(ContinueWatching.Item item, int index) {
        float radius = 14 * density;
        FrameLayout card = new FrameLayout(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CARD_W_DP), dp(CARD_H_DP));
        if (index > 0) lp.leftMargin = dp(GAP_DP);
        card.setLayoutParams(lp);
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C1C22);
        bg.setCornerRadius(radius);
        card.setBackground(bg);
        card.setContentDescription(item.title);

        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (item.appCard) {
            // The app's own TV banner, which already carries its logo.
            image.setImageDrawable(banner(item.pkg));
        } else {
            loadArt(item.art, image);
        }

        View scrim = new View(activity);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x99000000, 0xE6000000}));
        card.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84),
                Gravity.BOTTOM));

        TextView chip = new TextView(activity);
        chip.setVisibility(item.appCard ? View.GONE : View.VISIBLE);
        chip.setText(appName(item.pkg).toUpperCase(Locale.getDefault()));
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        chip.setLetterSpacing(0.12f);
        chip.setTypeface(medium);
        chip.setSingleLine(true);
        chip.setPadding(dp(7), dp(3), dp(7), dp(3));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(0xA6000000);
        chipBg.setCornerRadius(6 * density);
        chip.setBackground(chipBg);
        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        chipLp.setMargins(dp(10), dp(10), 0, 0);
        card.addView(chip, chipLp);

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(14), 0, dp(14), dp(item.progress >= 0 ? 16 : 12));
        TextView title = new TextView(activity);
        title.setText(item.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(medium);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (!item.appCard) text.addView(title);   // an app card's banner already shows its name
        if (item.subtitle != null) {
            TextView sub = new TextView(activity);
            sub.setText(item.subtitle);
            sub.setTextColor(0xBFFFFFFF);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setTypeface(regular);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(sub);
        }
        card.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        if (item.progress >= 0) {
            FrameLayout track = new FrameLayout(activity);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setColor(0x4DFFFFFF);
            trackBg.setCornerRadius(2 * density);
            track.setBackground(trackBg);
            View fill = new View(activity);
            GradientDrawable fillBg = new GradientDrawable();
            fillBg.setColor(Color.WHITE);
            fillBg.setCornerRadius(2 * density);
            fill.setBackground(fillBg);
            int trackW = dp(CARD_W_DP - 28);
            track.addView(fill, new FrameLayout.LayoutParams(Math.max(dp(4), Math.round(trackW * item.progress)),
                    ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(trackW, dp(3), Gravity.BOTTOM);
            tlp.setMargins(dp(14), 0, 0, dp(9));
            card.addView(track, tlp);
        }

        GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(3), Color.WHITE);
        ring.setCornerRadius(radius);
        card.setForeground(ring);
        ring.setAlpha(0);

        card.setOnFocusChangeListener((v, focused) -> {
            ring.setAlpha(focused ? 255 : 0);
            v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f)
                    .translationZ(focused ? 12 * density : 0f).setDuration(150).start();
            if (focused) {
                // Google TV style: the focused card slides to the row's start instead of riding the right edge.
                scroller.smoothScrollTo(Math.max(0, v.getLeft() - dp(66)), 0);
                lastIndex = index;
                host.onContinueFocused();
            }
        });
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    host.focusTopBarFromRow();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    host.focusDockFromRow();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return index == 0;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return index == items.size() - 1;
                default:
                    return false;
            }
        });
        card.setOnClickListener(v -> open(item));
        return card;
    }

    private void open(ContinueWatching.Item item) {
        Intent i = ContinueWatching.open(activity, item);
        if (i == null) return;
        try {
            activity.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Intent app = ContinueWatching.launchIntent(activity.getPackageManager(), item.pkg);
            if (app != null) {
                try {
                    activity.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                    // Nothing else to try.
                }
            }
        }
    }

    private android.graphics.drawable.Drawable banner(String pkg) {
        PackageManager pm = activity.getPackageManager();
        try {
            Intent launch = ContinueWatching.launchIntent(pm, pkg);
            android.graphics.drawable.Drawable d = launch == null ? null : pm.getActivityBanner(launch);
            if (d == null) d = pm.getApplicationBanner(pkg);
            return d != null ? d : pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private String appName(String pkg) {
        String name = appNames.get(pkg);
        if (name != null) return name;
        PackageManager pm = activity.getPackageManager();
        try {
            name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            name = pkg;
        }
        appNames.put(pkg, name);
        return name;
    }

    // ---- artwork -----------------------------------------------------------------------------------

    private void loadArt(String uri, ImageView into) {
        if (uri == null) return;
        Bitmap cached = CACHE.get(uri);
        if (cached != null) {
            into.setImageBitmap(cached);
            return;
        }
        into.setTag(uri);
        int targetW = dp(CARD_W_DP) * 2;   // room for the focus zoom
        art.execute(() -> {
            Bitmap bmp = fetch(activity, uri, targetW);
            if (bmp == null) return;
            CACHE.put(uri, bmp);
            main.post(() -> {
                if (uri.equals(into.getTag())) {
                    into.setAlpha(0f);
                    into.setImageBitmap(bmp);
                    into.animate().alpha(1f).setDuration(200).start();
                }
            });
        });
    }

    /** Downloads (https, or http upgraded to https) or reads a content:// image, decoded near targetW wide. */
    private static Bitmap fetch(Context ctx, String uri, int targetW) {
        try {
            byte[] data;
            if (uri.startsWith("content:") || uri.startsWith("android.resource:") || uri.startsWith("file:")) {
                try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(uri))) {
                    data = in == null ? null : readAll(in);
                }
            } else {
                String url = uri.startsWith("http:") ? "https:" + uri.substring(5) : uri;
                data = download(url);
            }
            if (data == null) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= targetW) opts.inSampleSize *= 2;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static byte[] download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > 6 * 1024 * 1024) return null;   // no runaway images
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
