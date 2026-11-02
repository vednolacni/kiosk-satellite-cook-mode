// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/**
 * Turns a swipe across its child into next and previous. A swipe left or up goes forward, the other way goes back, so a
 * floury hand can hardly get it wrong. Taps still reach the child, and a text that scrolls keeps its vertical drags.
 */
final class Swipes extends FrameLayout {
    /** How far a finger moves before it counts as a swipe, and how much more along one axis than the other. */
    private static final int START_DP = 24;
    private static final float DOMINANCE = 1.5f;
    /** How far a swipe must have gone by the time the finger lifts. */
    private static final int LENGTH_DP = 56;

    private final Runnable forward;
    private final Runnable backward;
    private final int start;
    private final int length;
    private float downX;
    private float downY;

    Swipes(Context context, Parts parts, Runnable forward, Runnable backward) {
        super(context);
        this.forward = forward;
        this.backward = backward;
        start = parts.px(START_DP);
        length = parts.px(LENGTH_DP);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                return false;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(event.getX() - downX);
                float dy = Math.abs(event.getY() - downY);
                return (dx >= start && dx >= dy * DOMINANCE) || (dy >= start && dy >= dx * DOMINANCE);
            default:
                return false;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                float x = event.getX() - downX;
                float y = event.getY() - downY;
                float distance = Math.abs(x) >= Math.abs(y) ? x : y;
                if (distance <= -length) Parts.guard(forward);
                else if (distance >= length) Parts.guard(backward);
                return true;
            default:
                return true;
        }
    }
}
