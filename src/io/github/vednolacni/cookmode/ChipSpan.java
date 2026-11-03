// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.widget.TextView;

/**
 * Draws a run of text as a rounded chip with an icon, in place inside a paragraph, the way the cooking view shows
 * "4 to 5 minutes" as a timer a cook can tap. A ClickableSpan would also answer a tap on the empty space after a chip
 * that ends its line, and start a timer nobody asked for, so taps are matched to the chip itself with {@link #at}.
 */
final class ChipSpan extends ReplacementSpan {
    /** What a tap on the chip does. */
    final Runnable tap;
    private final int background;
    private final int foreground;
    /** An icon outline on a 24 by 24 grid, or null. */
    private final Path icon;
    private final float padding;
    private final float radius;
    private final RectF rect = new RectF();

    ChipSpan(int background, int foreground, Path icon, float padding, Runnable tap) {
        this.background = background;
        this.foreground = foreground;
        this.icon = icon;
        this.padding = padding;
        this.radius = padding * 2;
        this.tap = tap;
    }

    /** The chip at this touch in the view, or null. margin widens each chip for a thick finger. */
    static ChipSpan at(TextView view, float touchX, float touchY, float margin) {
        Layout layout = view.getLayout();
        CharSequence shown = view.getText();
        if (layout == null || !(shown instanceof Spanned)) return null;
        Spanned text = (Spanned) shown;
        float x = touchX - view.getTotalPaddingLeft() + view.getScrollX();
        float y = touchY - view.getTotalPaddingTop() + view.getScrollY();
        for (ChipSpan chip : text.getSpans(0, text.length(), ChipSpan.class)) {
            int start = text.getSpanStart(chip);
            int line = layout.getLineForOffset(start);
            float left = layout.getPrimaryHorizontal(start);
            float right = left + chip.width(view.getPaint(), text, start, text.getSpanEnd(chip));
            if (x >= left - margin && x <= right + margin && y >= layout.getLineTop(line) && y <= layout.getLineBottom(line)) return chip;
        }
        return null;
    }

    /** The widest chip in this text at this paint. A chip does not wrap, so a line narrower than this cuts it off. */
    static float widest(Spanned text, Paint paint) {
        float most = 0;
        for (ChipSpan chip : text.getSpans(0, text.length(), ChipSpan.class)) most = Math.max(most, chip.width(paint, text, text.getSpanStart(chip), text.getSpanEnd(chip)));
        return most;
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
