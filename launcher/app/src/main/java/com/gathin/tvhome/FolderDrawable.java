package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;

/**
 * A folder tile's artwork (hisense-home fork), after tvOS: a frosted glass tile holding a 3x3 grid of miniature app
 * tiles, each a tiny copy of the app's own banner (or its icon on a dark chip).
 */
final class FolderDrawable extends Drawable {
    static final class Mini {
        final Drawable banner, icon;
        Mini(Drawable banner, Drawable icon) {
            this.banner = copy(banner);
            this.icon = copy(icon);
        }
        private static Drawable copy(Drawable d) {
            if (d == null) return null;
            Drawable.ConstantState s = d.getConstantState();
            return s != null ? s.newDrawable().mutate() : d;
        }
    }

    private final List<Mini> minis = new ArrayList<>();
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF r = new RectF();

    FolderDrawable(List<Mini> apps) {
        for (int i = 0; i < Math.min(9, apps.size()); i++) minis.add(apps.get(i));
        chip.setColor(0xFF2B2E36);
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        float w = b.width(), h = b.height();
        // Frosted glass: a light veil over whatever is behind the tile, brighter at the top.
        glass.setShader(new LinearGradient(0, b.top, 0, b.bottom, 0x66C8CCD6, 0x40868A96, Shader.TileMode.CLAMP));
        canvas.drawRect(b, glass);
        sheen.setShader(new LinearGradient(0, b.top, 0, b.top + h * 0.5f, 0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(b.left, b.top, b.right, b.top + h * 0.5f, sheen);

        // 3x3 minis with even margins; each keeps the 16:9 shape of a TV app tile.
        float padX = w * 0.075f, padY = h * 0.10f, gapX = w * 0.035f, gapY = h * 0.055f;
        float cellW = (w - 2 * padX - 2 * gapX) / 3f;
        float cellH = (h - 2 * padY - 2 * gapY) / 3f;
        float miniW = Math.min(cellW, cellH * 16f / 9f), miniH = miniW * 9f / 16f;
        float gridW = 3 * miniW + 2 * gapX, gridH = 3 * miniH + 2 * gapY;
        float x0 = b.exactCenterX() - gridW / 2f, y0 = b.exactCenterY() - gridH / 2f;
        float radius = miniH * 0.16f;
        for (int i = 0; i < minis.size(); i++) {
            int col = i % 3, row = i / 3;
            float left = x0 + col * (miniW + gapX), top = y0 + row * (miniH + gapY);
            r.set(left, top, left + miniW, top + miniH);
            clip.reset();
            clip.addRoundRect(r, radius, radius, Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(clip);
            Mini m = minis.get(i);
            if (m.banner != null) {
                m.banner.setBounds(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
                m.banner.draw(canvas);
            } else {
                canvas.drawRect(r, chip);
                if (m.icon != null) {
                    float s = miniH * 0.72f;
                    float cx = r.centerX(), cy = r.centerY();
                    m.icon.setBounds(Math.round(cx - s / 2), Math.round(cy - s / 2), Math.round(cx + s / 2),
                            Math.round(cy + s / 2));
                    m.icon.draw(canvas);
                }
            }
            canvas.restoreToCount(save);
        }
    }

    @Override public int getIntrinsicWidth() { return 320; }
    @Override public int getIntrinsicHeight() { return 180; }
    @Override public void setAlpha(int alpha) { glass.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { }
    /** Translucent, so the tile's dark card is drawn under the glass. */
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
