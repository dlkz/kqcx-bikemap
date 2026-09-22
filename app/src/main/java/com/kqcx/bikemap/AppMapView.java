package com.kqcx.bikemap;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.views.MapView;

/**
 * Adds single-tap, double-tap and triple-tap handling on top of osmdroid.
 *
 * <p>osmdroid runs an animated double-tap zoom-in as soon as it sees two quick
 * taps. During a triple tap that animation starts on the second tap and is then
 * immediately reversed, which is the "zoom in then out" flicker. This view
 * resets osmdroid's own gesture detector after every clean tap and runs the
 * tap sequence itself, so the double-tap zoom only happens once the gesture is
 * definitely not a triple tap.</p>
 */
public final class AppMapView extends MapView {
    private static final long SINGLE_TAP_TIMEOUT_MS = 300L;
    private static final long MULTI_TAP_TIMEOUT_MS = 340L;

    public interface OnMapGestureListener {
        void onSingleTapConfirmed();

        void onTripleTap(double zoomLevelBeforeSequence, IGeoPoint centerBeforeSequence);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int touchSlop;

    private OnMapGestureListener gestureListener;
    private int tapCount;
    private long lastTapUpAt;
    private float downX;
    private float downY;
    private float lastTapX;
    private float lastTapY;
    private long lastTapDownAt;
    private boolean moved;
    private boolean suppressSuperForCurrentTap;
    private double sequenceStartZoom = Double.NaN;
    private IGeoPoint sequenceStartCenter;

    private final Runnable singleTapRunnable = () -> {
        int count = tapCount;
        tapCount = 0;
        if (count == 1) {
            MotionEvent tap = MotionEvent.obtain(
                    lastTapDownAt,
                    lastTapUpAt,
                    MotionEvent.ACTION_UP,
                    lastTapX,
                    lastTapY,
                    0
            );
            try {
                if (!getOverlayManager().onSingleTapConfirmed(tap, this)
                        && gestureListener != null) {
                    gestureListener.onSingleTapConfirmed();
                }
            } finally {
                tap.recycle();
            }
        }
    };

    private final Runnable doubleTapRunnable = () -> {
        boolean shouldZoom = tapCount == 2;
        tapCount = 0;
        if (shouldZoom) {
            getController().zoomInFixing((int) lastTapX, (int) lastTapY);
        }
    };

    public AppMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setOnMapGestureListener(OnMapGestureListener listener) {
        gestureListener = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        long now = SystemClock.uptimeMillis();

        if (action == MotionEvent.ACTION_DOWN) {
            boolean previousTapExpired =
                    now - lastTapUpAt > MULTI_TAP_TIMEOUT_MS;
            if (tapCount == 0 || previousTapExpired) {
                resetSequence();
            }
            suppressSuperForCurrentTap = tapCount > 0;
            lastTapDownAt = event.getDownTime();
            downX = event.getX();
            downY = event.getY();
            moved = event.getPointerCount() != 1;
            if (tapCount == 0) {
                sequenceStartZoom = getZoomLevelDouble();
                sequenceStartCenter = getMapCenter();
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (event.getPointerCount() != 1
                    || Math.abs(event.getX() - downX) > touchSlop
                    || Math.abs(event.getY() - downY) > touchSlop) {
                moved = true;
                resetSequence();
            }
        } else if (action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_CANCEL) {
            moved = true;
            resetSequence();
        }

        boolean handled;
        if (suppressSuperForCurrentTap && !moved) {
            handled = true;
        } else {
            if (suppressSuperForCurrentTap) {
                // The second tap of a sequence became a drag. Let the map handle
                // the movement, but do not restart osmdroid's double-tap state.
                cancelSuperTouch(event);
            }
            suppressSuperForCurrentTap = false;
            handled = super.dispatchTouchEvent(event);
        }

        if (action == MotionEvent.ACTION_UP) {
            if (!moved) {
                registerTap(event.getX(), event.getY(), now);
                if (!suppressSuperForCurrentTap) {
                    // Reset osmdroid's detector so it cannot start its own
                    // animated double-tap zoom. This view resolves the sequence.
                    cancelSuperTouch(event);
                }
            }
            moved = false;
            suppressSuperForCurrentTap = false;
        }
        return handled;
    }

    private void registerTap(float x, float y, long now) {
        tapCount++;
        lastTapUpAt = now;
        lastTapX = x;
        lastTapY = y;

        if (tapCount == 1) {
            handler.postDelayed(singleTapRunnable, SINGLE_TAP_TIMEOUT_MS);
        } else if (tapCount == 2) {
            handler.removeCallbacks(singleTapRunnable);
            handler.postDelayed(doubleTapRunnable, MULTI_TAP_TIMEOUT_MS);
        } else {
            handler.removeCallbacks(doubleTapRunnable);
            tapCount = 0;
            if (gestureListener != null && !Double.isNaN(sequenceStartZoom)) {
                gestureListener.onTripleTap(sequenceStartZoom, sequenceStartCenter);
            }
        }
    }

    private void cancelSuperTouch(MotionEvent source) {
        MotionEvent cancel = MotionEvent.obtain(source);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        try {
            super.dispatchTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
    }

    private void resetSequence() {
        tapCount = 0;
        handler.removeCallbacks(singleTapRunnable);
        handler.removeCallbacks(doubleTapRunnable);
    }
}
