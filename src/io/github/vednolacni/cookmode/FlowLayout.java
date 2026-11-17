// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/** Lays its children in rows and wraps to the next row, for chips. The platform has no such layout. */
final class FlowLayout extends ViewGroup {
    private final int gap;
    /** How far past a tappable child's edge a touch still lands on it, so a chip is a larger target than it looks. */
    private final int reach;
    /** How far the touch that went down beside a child is moved onto it, and every later event of that touch with it. */
    private float shiftX;
    private float shiftY;

    FlowLayout(Context context, int gap) {
        this(context, gap, 0);
    }

    FlowLayout(Context context, int gap, int reach) {
        super(context);
        this.gap = gap;
        this.reach = reach;
        // A touch reaches a child only inside this layout, so the reach above the first row and below the last is padding.
        // Whoever lays this out takes it back from the margins around it, so the chips sit where they would without it.
        setPadding(0, reach, 0, reach);
    }

    /**
     * A touch in the gap just beside a tappable child goes to that child. Within reach of half the gap the children's
     * reaches do not overlap, and the layout keeps its size, so a wet finger finds the chip without the chips taking the
     * room the step's words need.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            shiftX = 0;
            shiftY = 0;
            View near = reach > 0 && childNear(event.getX(), event.getY(), 0) == null ? childNear(event.getX(), event.getY(), reach) : null;
            if (near != null) {
                shiftX = Math.max(near.getLeft(), Math.min(near.getRight() - 1, event.getX())) - event.getX();
                shiftY = Math.max(near.getTop(), Math.min(near.getBottom() - 1, event.getY())) - event.getY();
            }
        }
        event.offsetLocation(shiftX, shiftY);
        boolean handled = super.dispatchTouchEvent(event);
        event.offsetLocation(-shiftX, -shiftY);
        return handled;
    }

    private View childNear(float x, float y, int margin) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != VISIBLE || !child.isClickable()) continue;
            if (x >= child.getLeft() - margin && x < child.getRight() + margin && y >= child.getTop() - margin && y < child.getBottom() + margin) return child;
        }
        return null;
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
