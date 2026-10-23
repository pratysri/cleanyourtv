package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;

/**
 * A folder tile's artwork (hisense-home fork): a frosted circle holding the first four apps' icons, 2x2, on a dark
 * card, like a folder on a phone home screen.
 */
final class FolderDrawable extends Drawable {
    private final List<Drawable> icons = new ArrayList<>();
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint count = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int total;

    FolderDrawable(List<Drawable> appIcons) {
        total = appIcons.size();
        for (int i = 0; i < Math.min(4, appIcons.size()); i++) {
            Drawable d = appIcons.get(i);
            Drawable.ConstantState state = d == null ? null : d.getConstantState();
            if (d != null) icons.add(state != null ? state.newDrawable().mutate() : d);
        }
        disc.setColor(0x33FFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0x4DFFFFFF);
        count.setColor(0xCCFFFFFF);
        count.setTextAlign(Paint.Align.RIGHT);
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        float w = b.width(), h = b.height();
        bg.setShader(new LinearGradient(0, b.top, 0, b.bottom, 0xFF343844, 0xFF1A1C22, Shader.TileMode.CLAMP));
        canvas.drawRect(b, bg);
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float r = h * 0.40f;
        canvas.drawCircle(cx, cy, r, disc);
        ring.setStrokeWidth(Math.max(1f, h * 0.012f));
        canvas.drawCircle(cx, cy, r, ring);
        float cell = r * 0.62f, gap = r * 0.10f;
        for (int i = 0; i < icons.size(); i++) {
            int col = i % 2, row = i / 2;
            float left = cx - cell - gap / 2 + col * (cell + gap);
            float top = cy - cell - gap / 2 + row * (cell + gap);
            if (icons.size() <= 2) top = cy - cell / 2;   // one row, centred
            Drawable d = icons.get(i);
            d.setBounds(Math.round(left), Math.round(top), Math.round(left + cell), Math.round(top + cell));
            d.draw(canvas);
        }
        if (total > 4) {
            count.setTextSize(h * 0.13f);
            canvas.drawText("+" + (total - 4), b.right - w * 0.05f, b.bottom - h * 0.08f, count);
        }
    }

    @Override public int getIntrinsicWidth() { return 320; }
    @Override public int getIntrinsicHeight() { return 180; }
    @Override public void setAlpha(int alpha) { bg.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
