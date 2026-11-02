package com.kqcx.bikemap;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.core.content.ContextCompat;

/**
 * Decorative proximity pulse driven only by the locally estimated distance.
 */
public final class VehicleFinderRadarView extends View {
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accentColor;
    private ValueAnimator animator;
    private float phase;
    private float proximity = 0.15f;

    public VehicleFinderRadarView(Context context) {
        this(context, null);
    }

    public VehicleFinderRadarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VehicleFinderRadarView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        accentColor = ContextCompat.getColor(context, R.color.control_accent);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        centerPaint.setStyle(Paint.Style.FILL);
    }

    public void setProximity(float value) {
        float clamped = Math.max(0f, Math.min(1f, value));
        if (Math.abs(clamped - proximity) < 0.04f) {
            return;
        }
        proximity = clamped;
        if (animator != null) {
            animator.setDuration(durationMs());
        }
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            startAnimation();
        } else {
            stopAnimation();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && getVisibility() == VISIBLE) {
            startAnimation();
        } else {
            stopAnimation();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float maximumRadius = Math.min(getWidth(), getHeight()) / 2f - dp(10);
        float minimumRadius = dp(24);
        int baseAlpha = 70 + Math.round(proximity * 100f);

        for (int index = 0; index < 3; index++) {
            float ringPhase = (phase + index / 3f) % 1f;
            float radius = minimumRadius
                    + (maximumRadius - minimumRadius) * ringPhase;
            int alpha = Math.round(baseAlpha * (1f - ringPhase));
            ringPaint.setColor(withAlpha(accentColor, alpha));
            ringPaint.setStrokeWidth(dp(2.2f) * (1f - ringPhase * 0.45f));
            canvas.drawCircle(centerX, centerY, radius, ringPaint);
        }

        centerPaint.setColor(withAlpha(accentColor, 50 + Math.round(proximity * 60f)));
        canvas.drawCircle(centerX, centerY, dp(24), centerPaint);
        centerPaint.setColor(accentColor);
        canvas.drawCircle(centerX, centerY, dp(8), centerPaint);
    }

    private void startAnimation() {
        if (animator != null && animator.isRunning()) {
            return;
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            phase = 0.35f;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(durationMs());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            phase = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stopAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private long durationMs() {
        return Math.round(2_600f - proximity * 1_500f);
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
