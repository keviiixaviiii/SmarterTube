package com.liskovsoft.smartyoutubetv2.mobile.ui.dialogs;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;

import androidx.appcompat.widget.AppCompatSeekBar;

/**
 * SeekBar for use inside a scrolling list. A plain {@link android.widget.SeekBar} snaps its value
 * to wherever a touch first lands on the track, so a vertical scroll gesture that happens to start
 * on the bar would drag it. This subclass only begins tracking when the touch starts on the thumb;
 * any other touch is passed straight through to the parent (the list) so scrolling and stray track
 * taps never change the value.
 */
public class ScrollSafeSeekBar extends AppCompatSeekBar {
    private boolean mDragging;

    public ScrollSafeSeekBar(Context context) {
        super(context);
    }

    public ScrollSafeSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ScrollSafeSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (isOnThumb(event.getX())) {
                    mDragging = true;
                    setParentInterceptDisabled(true);
                    return super.onTouchEvent(event);
                }
                return false; // not on the thumb — let the list scroll
            case MotionEvent.ACTION_MOVE:
                return mDragging && super.onTouchEvent(event);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDragging) {
                    mDragging = false;
                    setParentInterceptDisabled(false);
                    return super.onTouchEvent(event);
                }
                return false;
            default:
                return mDragging && super.onTouchEvent(event);
        }
    }

    private boolean isOnThumb(float x) {
        int available = getWidth() - getPaddingLeft() - getPaddingRight();
        float fraction = getMax() > 0 ? (float) getProgress() / getMax() : 0f;
        float thumbX = getPaddingLeft() + fraction * available;
        float thumbHalf = getThumb() != null ? getThumb().getIntrinsicWidth() / 2f : 0f;
        // At least a 24dp touch radius so the thumb is comfortably grabbable.
        float minRadius = 24f * getResources().getDisplayMetrics().density;
        return Math.abs(x - thumbX) <= Math.max(thumbHalf, minRadius);
    }

    private void setParentInterceptDisabled(boolean disabled) {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disabled);
        }
    }
}
