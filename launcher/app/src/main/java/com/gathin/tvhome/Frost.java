package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Real frosted glass (hisense-home fork, after Free My TV's Backdrop): a tiny, soft copy of the wallpaper on screen
 * (96x54, shrunk twice with filtering), drawn inside a glass shape at exactly the place it covers. Stretched back up
 * with bilinear filtering it is the blur, so the dock and the folder show a frosted version of the landscape behind
 * them without any blur pass per frame. Android 12 has no backdrop blur, so this is how the glass gets one.
 */
final class Frost {
    private static Bitmap image;
    private static BitmapShader shader;
    private static int screenW = 1, screenH = 1;
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Matrix MATRIX = new Matrix();
    private static final int[] LOC = new int[2];
    private static final List<Runnable> LISTENERS = new ArrayList<>();
    static boolean enabled = true;

    private Frost() { }

    /** From the decoded wallpaper (software bitmap, decode thread): the small blurred copy. */
    static Bitmap make(Bitmap wallpaper) {
        try {
            Bitmap a = Bitmap.createScaledBitmap(wallpaper, 192, 108, true);
            Bitmap b = Bitmap.createScaledBitmap(a, 48, 27, true);
            Bitmap c = Bitmap.createScaledBitmap(b, 96, 54, true);
            a.recycle();
            b.recycle();
            return c;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** The wallpaper on screen changed (main thread). */
    static void set(Bitmap frost, int screenWidth, int screenHeight) {
        image = frost;
        shader = frost == null ? null : new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        screenW = Math.max(1, screenWidth);
        screenH = Math.max(1, screenHeight);
        for (Runnable r : new ArrayList<>(LISTENERS)) r.run();
    }

    /** The small blurred wallpaper itself, for a full-screen frosted background; null when there is none. */
    static Bitmap image() {
        return enabled ? image : null;
    }

    static void listen(Runnable r) {
        LISTENERS.add(r);
    }

    static void unlisten(Runnable r) {
        LISTENERS.remove(r);
    }

    /**
     * Fills {@code rect} (in {@code host}'s coordinates) with the frosted wallpaper behind it. False when there is no
     * wallpaper picture (a plain or sky background): the caller keeps its plain glass then.
     */
    static boolean draw(Canvas c, RectF rect, float radius, View host) {
        if (!enabled || shader == null || host == null) return false;
        host.getLocationInWindow(LOC);
        MATRIX.setScale(screenW / (float) image.getWidth(), screenH / (float) image.getHeight());
        MATRIX.postTranslate(-LOC[0], -LOC[1]);
        shader.setLocalMatrix(MATRIX);
        PAINT.setShader(shader);
        c.drawRoundRect(rect, radius, radius, PAINT);
        return true;
    }
}
