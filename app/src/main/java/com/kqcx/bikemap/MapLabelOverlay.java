package com.kqcx.bikemap;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Typeface;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws small place-name chips for coordinates that are already known to be in
 * GCJ-02, so they stay aligned with the Amap tiles.
 */
public final class MapLabelOverlay extends Overlay {
    public static final class Label {
        public final GeoPoint point;
        public final String text;
        public final boolean primary;

        public Label(GeoPoint point, String text, boolean primary) {
            this.point = point;
            this.text = text;
            this.primary = primary;
        }
    }

    private final List<Label> labels;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public MapLabelOverlay(Context context, List<Label> labels) {
        this.labels = labels;
        density = context.getResources().getDisplayMetrics().density;

        fillPaint.setColor(Color.argb(236, 255, 255, 255));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1f, density));
        strokePaint.setColor(Color.argb(56, 38, 50, 56));
        textPaint.setColor(Color.rgb(38, 50, 56));
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    @Override
    public void draw(Canvas canvas, MapView mapView, boolean shadow) {
        if (shadow || labels.isEmpty()) {
            return;
        }

        int canvasWidth = canvas.getWidth();
        int canvasHeight = canvas.getHeight();
        Point projected = new Point();
        List<RectF> occupied = new ArrayList<>();
        for (Label label : labels) {
            mapView.getProjection().toPixels(label.point, projected);

            float textSize = (label.primary ? 12f : 10.5f) * density;
            textPaint.setTextSize(textSize);
            float textWidth = textPaint.measureText(label.text);
            float padH = (label.primary ? 7f : 5f) * density;
            float padV = (label.primary ? 4f : 3f) * density;
            float halfWidth = textWidth / 2f + padH;
            float halfHeight = textSize / 2f + padV;

            float edgePadding = 4f * density;
            // Labels belong to their map coordinates. Once the anchor leaves
            // the viewport, skip it instead of clamping it to the screen edge,
            // which used to pile distant labels against the sides.
            if (projected.x + halfWidth < edgePadding
                    || projected.x - halfWidth > canvasWidth - edgePadding) {
                continue;
            }
            float[] verticalOffsets = label.primary
                    ? new float[]{-40f, 40f, -58f, 58f, -76f, 76f}
                    : new float[]{-34f, 34f, -52f, 52f, -70f, 70f};
            RectF rect = null;
            float centerX = 0;
            float centerY = 0;
            for (float offset : verticalOffsets) {
                centerX = projected.x;
                centerY = projected.y + offset * density;
                if (centerX - halfWidth < edgePadding
                        || centerX + halfWidth > canvasWidth - edgePadding
                        || centerY - halfHeight < edgePadding
                        || centerY + halfHeight > canvasHeight - edgePadding) {
                    continue;
                }
                RectF candidate = new RectF(
                        centerX - halfWidth,
                        centerY - halfHeight,
                        centerX + halfWidth,
                        centerY + halfHeight
                );
                RectF collision = new RectF(candidate);
                collision.inset(-2f * density, -2f * density);
                boolean overlaps = false;
                for (RectF occupiedRect : occupied) {
                    if (RectF.intersects(collision, occupiedRect)) {
                        overlaps = true;
                        break;
                    }
                }
                if (!overlaps) {
                    rect = candidate;
                    occupied.add(collision);
                    break;
                }
            }
            if (rect == null) {
                continue;
            }
            float radius = halfHeight;
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
            canvas.drawRoundRect(rect, radius, radius, strokePaint);

            float baseline = centerY
                    - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(
                    label.text,
                    centerX - textWidth / 2f,
                    baseline,
                    textPaint
            );
        }
    }
}
