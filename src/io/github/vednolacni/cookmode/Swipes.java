// SPDX-License-Identifier: Apache-2.0
package io.github.vednolacni.cookmode;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Turns a swipe across its child into next and previous. A swipe left or up goes forward, the other way goes back, so a
 * floury hand can hardly get it wrong. Taps still reach the child, and a text that scrolls keeps its vertical drags.
 *
 * <p>It watches the touches on their way down rather than intercepting them: a ScrollView that took the touch stops its
 * parents from intercepting as soon as the finger moves, even when it has nothing to scroll.
 */
final class Swipes extends FrameLayout {
    /** How far a finger moves before it counts as a swipe, and how much more along one axis than the other. */
    private static final int START_DP = 24;
    private static final float DOMINANCE = 1.5f;
    /** How far a swipe must have gone by the time the finger lifts. */
    private static final int LENGTH_DP = 56;

    private final View scroller;
    private final Runnable forward;
    private final Runnable backward;
    private final int start;
    private final int length;
    private boolean on = true;
    private float downX;
    private float downY;
    private boolean swiping;
    /** A second finger came down. That is KS's gesture, or a palm. */
    private boolean several;

    /** scroller is the view inside that may want vertical drags for itself. */
    Swipes(Context context, Parts parts, View scroller, Runnable forward, Runnable backward) {
        super(context);
        this.scroller = scroller;
        this.forward = forward;
        this.backward = backward;
        start = parts.px(START_DP);
        length = parts.px(LENGTH_DP);
    }

    void on(boolean on) {
        this.on = on;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                swiping = false;
                several = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                several = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (on && !several && !swiping && isSwipe(event)) {
                    swiping = true;
                    // Whatever the finger started on must not take this for a tap.
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (swiping && !several) finish(event);
                break;
            default:
                break;
        }
        return swiping || super.dispatchTouchEvent(event);
    }

    private boolean isSwipe(MotionEvent event) {
        float x = Math.abs(event.getX() - downX);
        float y = Math.abs(event.getY() - downY);
        if (x >= start && x >= y * DOMINANCE) return true;
        return y >= start && y >= x * DOMINANCE && !scroller.canScrollVertically(1) && !scroller.canScrollVertically(-1);
    }

    private void finish(MotionEvent event) {
        float x = event.getX() - downX;
        float y = event.getY() - downY;
        float distance = Math.abs(x) >= Math.abs(y) ? x : y;
        if (distance <= -length) Parts.guard(forward);
        else if (distance >= length) Parts.guard(backward);
    }
}
