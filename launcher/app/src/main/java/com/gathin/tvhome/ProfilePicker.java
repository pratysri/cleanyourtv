package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * "Who's watching?" (hisense-home fork), after Apple TV's profile picker: Home blurred behind, one coloured
 * circle with an initial per person, the current one focused first. OK picks; Back keeps whoever was active.
 */
final class ProfilePicker {
    private static final int CIRCLE_DP = 132, GAP_DP = 64;
    private static Dialog showing;

    private ProfilePicker() { }

    static boolean isShowing() {
        return showing != null && showing.isShowing();
    }

    static void show(Activity activity, IntConsumer onPick) {
        if (isShowing()) return;
        FolderDialog.withBlurredHome(activity, backdrop -> build(activity, backdrop, onPick));
    }

    private static void build(Activity activity, Drawable backdrop, IntConsumer onPick) {
        if (isShowing()) return;
        float density = activity.getResources().getDisplayMetrics().density;
        String[] names = Profiles.names(activity);
        int current = Profiles.current(activity);
        Typeface medium = font(activity, R.font.outfit_medium), light = font(activity, R.font.outfit_light);

        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(backdrop != null ? backdrop : new ColorDrawable(0xF0000000));
            window.setWindowAnimations(android.R.style.Animation_Dialog);
        }

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);

        TextView heading = new TextView(activity);
        heading.setText(R.string.whos_watching);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        heading.setTypeface(medium);
        heading.setPadding(0, 0, 0, Math.round(56 * density));
        column.addView(heading);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        column.addView(row);

        View[] focusFirst = new View[1];
        for (int i = 0; i < names.length; i++) {
            final int who = i;
            LinearLayout cell = new LinearLayout(activity);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setClipChildren(false);
            cell.setClipToPadding(false);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) clp.leftMargin = Math.round(GAP_DP * density);
            row.addView(cell, clp);

            int size = Math.round(CIRCLE_DP * density);
            TextView circle = new TextView(activity);
            String name = names[i].trim();
            circle.setText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());
            circle.setTextColor(Color.WHITE);
            circle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
            circle.setTypeface(light);
            circle.setGravity(Gravity.CENTER);
            circle.setIncludeFontPadding(false);
            int c = Profiles.color(i);
            GradientDrawable disc = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{lighten(c), c});
            disc.setShape(GradientDrawable.OVAL);
            circle.setBackground(disc);
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(Color.TRANSPARENT);
            ring.setStroke(Math.round(4 * density), Color.WHITE);
            ring.setAlpha(0);
            circle.setForeground(ring);
            circle.setFocusable(true);
            circle.setClickable(true);
            circle.setContentDescription(name);
            cell.addView(circle, new LinearLayout.LayoutParams(size, size));

            TextView label = new TextView(activity);
            label.setText(name);
            label.setTextColor(0xB3FFFFFF);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            label.setTypeface(medium);
            label.setPadding(0, Math.round(22 * density), 0, 0);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.addView(label);

            circle.setOnFocusChangeListener((v, focused) -> {
                ring.setAlpha(focused ? 255 : 0);
                v.animate().scaleX(focused ? 1.12f : 1f).scaleY(focused ? 1.12f : 1f)
                        .translationZ(focused ? 18 * density : 0f).setDuration(180).start();
                label.setTextColor(focused ? Color.WHITE : 0xB3FFFFFF);
            });
            circle.setOnClickListener(v -> {
                dialog.dismiss();
                onPick.accept(who);
            });
            if (i == current) focusFirst[0] = circle;
        }

        FrameLayout root = new FrameLayout(activity);
        root.setClipChildren(false);   // the focused circle grows past its column
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        dialog.setContentView(root);
        dialog.setOnShowListener(d -> { if (focusFirst[0] != null) focusFirst[0].requestFocus(); });
        dialog.setOnDismissListener(d -> showing = null);
        showing = dialog;
        dialog.show();
    }

    private static int lighten(int c) {
        int r = Math.min(255, Color.red(c) + 60), g = Math.min(255, Color.green(c) + 60),
                b = Math.min(255, Color.blue(c) + 60);
        return Color.rgb(r, g, b);
    }

    private static Typeface font(Activity a, int res) {
        try {
            return a.getResources().getFont(res);
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }
}
