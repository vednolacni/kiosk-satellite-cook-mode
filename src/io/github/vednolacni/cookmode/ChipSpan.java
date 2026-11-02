// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.style.ReplacementSpan;

/**
 * Draws a run of text as a rounded chip with an icon, in place inside a paragraph, the way the cooking view shows
 * "4 to 5 minutes" as a timer a cook can tap. A ClickableSpan on the same range handles the tap.
 */
final class ChipSpan extends ReplacementSpan {
    private final int background;
    private final int foreground;
    /** An icon outline on a 24 by 24 grid, or null. */
    private final Path icon;
    private final float padding;
    private final float radius;
    private final RectF rect = new RectF();

    ChipSpan(int background, int foreground, Path icon, float padding) {
        this.background = background;
        this.foreground = foreground;
        this.icon = icon;
        this.padding = padding;
        this.radius = padding * 2;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
        if (metrics != null) {
            Paint.FontMetricsInt own = paint.getFontMetricsInt();
            metrics.ascent = own.ascent;
            metrics.descent = own.descent;
            metrics.top = own.top;
            metrics.bottom = own.bottom;
        }
        return Math.round(width(paint, text, start, end));
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float height = metrics.descent - metrics.ascent;
        rect.set(x, y + metrics.ascent - padding / 2, x + width(paint, text, start, end), y + metrics.descent + padding / 2);
        int color = paint.getColor();
        Paint.Style style = paint.getStyle();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(background);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setColor(foreground);
        float textX = x + padding;
        if (icon != null) {
            float size = height * 0.8f;
            canvas.save();
            canvas.translate(textX, y + (metrics.ascent + metrics.descent) / 2 - size / 2);
            canvas.scale(size / 24f, size / 24f);
            canvas.drawPath(icon, paint);
            canvas.restore();
            textX += size + padding / 2;
        }
        canvas.drawText(text, start, end, textX, y, paint);
        paint.setColor(color);
        paint.setStyle(style);
    }

    private float width(Paint paint, CharSequence text, int start, int end) {
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float iconWidth = icon == null ? 0 : (metrics.descent - metrics.ascent) * 0.8f + padding / 2;
        return padding + iconWidth + paint.measureText(text, start, end) + padding;
    }
}
