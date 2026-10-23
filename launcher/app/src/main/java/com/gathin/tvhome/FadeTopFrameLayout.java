package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * The grid's clipping frame (hisense-home fork): rows scrolled up under the clock dissolve over the top
 * {@link #FADE_DP} instead of ending in a hard line. Only that band is drawn through an offscreen layer; the rest of
 * the grid draws as before.
 */
public final class FadeTopFrameLayout extends FrameLayout {
    /** A thin band: rows stay solid, and only the cut edge itself melts away, so there is no line. */
    private static final float FADE_DP = 46f;
    private final Paint mask = new Paint();
    private final int fade;
    private int maskFor = -1;

    public FadeTopFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        fade = Math.round(FADE_DP * context.getResources().getDisplayMetrics().density);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h <= fade) {
            super.dispatchDraw(canvas);
            return;
        }
        // Below the band: as usual.
        int save = canvas.save();
        canvas.clipRect(0, fade, w, h);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
        // The band: drawn into a small layer, then faded from clear at the top edge to solid.
        if (maskFor != fade) {
            // Eased: clear at the very edge, then quickly back to solid, so tiles look whole until the last pixels.
            mask.setShader(new LinearGradient(0, 0, 0, fade,
                    new int[]{0x00000000, 0x80000000, 0xD9000000, 0xFF000000},
                    new float[]{0f, 0.35f, 0.65f, 1f}, Shader.TileMode.CLAMP));
            maskFor = fade;
        }
        int layer = canvas.saveLayer(0, 0, w, fade, null);
        super.dispatchDraw(canvas);
        canvas.drawRect(0, 0, w, fade, mask);
        canvas.restoreToCount(layer);
    }
}
