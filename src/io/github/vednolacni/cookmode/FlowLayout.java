// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Lays its children in rows and wraps to the next row, for chips. The platform has no such layout. */
final class FlowLayout extends ViewGroup {
    private final int gap;

    FlowLayout(Context context, int gap) {
        super(context);
        this.gap = gap;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int available = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE : MeasureSpec.getSize(widthSpec);
        int inner = available == Integer.MAX_VALUE ? available : Math.max(0, available - getPaddingLeft() - getPaddingRight());
        int childWidth = MeasureSpec.makeMeasureSpec(inner == Integer.MAX_VALUE ? 0 : inner, inner == Integer.MAX_VALUE ? MeasureSpec.UNSPECIFIED : MeasureSpec.AT_MOST);
        int childHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        int x = 0;
        int rowHeight = 0;
        int height = 0;
        int widest = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(childWidth, childHeight);
            int width = child.getMeasuredWidth();
            if (x > 0 && x + width > inner) {
                height += rowHeight + gap;
                x = 0;
                rowHeight = 0;
            }
            x += width + gap;
            widest = Math.max(widest, x - gap);
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
        }
        height += rowHeight;
        setMeasuredDimension(resolveSize(widest + getPaddingLeft() + getPaddingRight(), widthSpec), resolveSize(height + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int inner = right - left - getPaddingLeft() - getPaddingRight();
        int x = 0;
        int y = getPaddingTop();
        int rowHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int width = child.getMeasuredWidth();
            if (x > 0 && x + width > inner) {
                y += rowHeight + gap;
                x = 0;
                rowHeight = 0;
            }
            child.layout(getPaddingLeft() + x, y, getPaddingLeft() + x + width, y + child.getMeasuredHeight());
            x += width + gap;
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
        }
    }
}
