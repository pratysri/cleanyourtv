package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The folder's panel (hisense-home fork), after tvOS: Home blurred and darkened behind, the folder's name large at
 * the top, its apps as full-size tiles five to a row. The focused tile lifts with a shadow and shows its name; the
 * others show none. OK opens an app, Back closes.
 */
final class FolderDialog {
    private static final int COLUMNS = 5, TILE_W = 146, TILE_H = 82, GAP = 30, ROW_GAP = 16;

    private FolderDialog() { }

    /**
     * Copies the screen first (PixelCopy reads the window's surface, hardware-decoded wallpaper included, which a
     * software draw of the view tree cannot), then opens the panel over a blurred version of it.
     */
    static void show(Activity activity, String title, List<AppAdapter.Entry> apps) {
        View root = activity.getWindow().getDecorView();
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) {
            show(activity, title, apps, null);
            return;
        }
        try {
            android.graphics.Bitmap shot = android.graphics.Bitmap.createBitmap(w / 4, h / 4,
                    android.graphics.Bitmap.Config.ARGB_8888);
            android.view.PixelCopy.request(activity.getWindow(), shot, result -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                show(activity, title, apps, result == android.view.PixelCopy.SUCCESS ? blur(activity, shot) : null);
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
        } catch (RuntimeException | OutOfMemoryError e) {
            show(activity, title, apps, null);
        }
    }

    private static void show(Activity activity, String title, List<AppAdapter.Entry> apps, Drawable backdrop) {
        float density = activity.getResources().getDisplayMetrics().density;
        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        Window window = dialog.getWindow();
        if (window != null) {
            // This TV has no window blur, so blur a small snapshot of Home instead: drawn at 1/16 size and
            // stretched back with filtering, under a dark veil.
            window.setBackgroundDrawable(backdrop != null ? backdrop : new ColorDrawable(0xE6000000));
            window.setWindowAnimations(android.R.style.Animation_Dialog);
        }
        Typeface medium = font(activity, R.font.outfit_medium), light = font(activity, R.font.outfit_light);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        int pad = Math.round(24 * density);
        panel.setPadding(pad, pad, pad, pad);

        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        heading.setTypeface(medium);
        heading.setGravity(Gravity.CENTER_HORIZONTAL);
        heading.setPadding(0, 0, 0, Math.round(34 * density));
        panel.addView(heading);

        LinearLayout row = null;
        View first = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = Math.round(ROW_GAP * density);
                panel.addView(row, rlp);
            }
            View tile = tile(activity, dialog, apps.get(i), density, medium, i % COLUMNS > 0);
            row.addView(tile);
            if (first == null) first = tile.findViewWithTag("card");
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel);
        FrameLayout root = new FrameLayout(activity);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        dialog.setContentView(root);
        View focus = first;
        dialog.setOnShowListener(d -> { if (focus != null) focus.requestFocus(); });
        dialog.show();
    }

    private static View tile(Activity activity, Dialog dialog, AppAdapter.Entry e, float density, Typeface medium,
                             boolean gapBefore) {
        int w = Math.round(TILE_W * density), h = Math.round(TILE_H * density);
        float radius = 12 * density;
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        cell.setClipToPadding(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (gapBefore) clp.leftMargin = Math.round(GAP * density);
        cell.setLayoutParams(clp);

        FrameLayout card = new FrameLayout(activity);
        card.setTag("card");
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF343844, 0xFF1F2128});
        bg.setCornerRadius(radius);
        card.setBackground(bg);
        ImageView art = new ImageView(activity);
        Drawable d = e.banner != null ? e.banner : e.icon;
        art.setImageDrawable(d);
        if (e.banner != null) {
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int s = Math.round(h * 0.58f);
            card.addView(art, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }
        GradientDrawable ringShape = new GradientDrawable();
        // tvOS focus: no outline, the tile lifts toward you with a soft shadow and a faint top sheen.
        ringShape.setColors(new int[]{0x1FFFFFFF, 0x00FFFFFF});
        ringShape.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        ringShape.setCornerRadius(radius);
        ringShape.setAlpha(0);
        card.setForeground(ringShape);
        card.setOnClickListener(v -> {
            dialog.dismiss();
            open(activity, e);
        });
        cell.addView(card, new LinearLayout.LayoutParams(w, h));

        TextView name = new TextView(activity);
        name.setText(e.label);
        name.setTextColor(0xD9FFFFFF);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        name.setTypeface(medium);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setPadding(0, Math.round(14 * density), 0, 0);
        name.setAlpha(0f);
        card.setOnFocusChangeListener((v, focused) -> {
            ringShape.setAlpha(focused ? 255 : 0);
            v.animate().scaleX(focused ? 1.13f : 1f).scaleY(focused ? 1.13f : 1f)
                    .translationZ(focused ? 22 * density : 0f).setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            name.animate().alpha(focused ? 1f : 0f).setDuration(160).start();
        });
        cell.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    /** A soft blur of the quarter-size screenshot: shrunk twice more with filtering, stretched back, darkened. */
    private static Drawable blur(Activity activity, android.graphics.Bitmap shot) {
        try {
            int w = shot.getWidth(), h = shot.getHeight();
            android.graphics.Bitmap a = android.graphics.Bitmap.createScaledBitmap(shot, Math.max(1, w / 4),
                    Math.max(1, h / 4), true);
            android.graphics.Bitmap b = android.graphics.Bitmap.createScaledBitmap(a, Math.max(1, w / 8),
                    Math.max(1, h / 8), true);
            android.graphics.Bitmap c = android.graphics.Bitmap.createScaledBitmap(b, Math.max(1, w / 4),
                    Math.max(1, h / 4), true);
            shot.recycle();
            android.graphics.drawable.BitmapDrawable d =
                    new android.graphics.drawable.BitmapDrawable(activity.getResources(), c);
            d.setFilterBitmap(true);
            return new android.graphics.drawable.LayerDrawable(new Drawable[]{d, new ColorDrawable(0x8C000000)});
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static void open(Activity activity, AppAdapter.Entry e) {
        ComponentName c = e.component;
        Intent i = new Intent(Intent.ACTION_MAIN).setComponent(c).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            activity.startActivity(i);
        } catch (Exception ex) {
            Intent fallback = ContinueWatching.launchIntent(activity.getPackageManager(), c.getPackageName());
            try {
                if (fallback != null) activity.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                Toast.makeText(activity, activity.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static Typeface font(Activity a, int res) {
        try {
            return a.getResources().getFont(res);
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }
}
