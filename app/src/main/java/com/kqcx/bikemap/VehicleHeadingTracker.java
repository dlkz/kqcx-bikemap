package com.kqcx.bikemap;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

final class VehicleHeadingTracker implements SensorEventListener {
    interface Callback {
        void onHeadingChanged();
    }

    private static final int WARMUP_SAMPLES = 3;
    private static final long WARMUP_MIN_MS = 400L;
    private static final float WARMUP_TOLERANCE_DEGREES = 12f;

    private final SensorManager sensorManager;
    private final Sensor rotationSensor;
    private final Sensor accelerometer;
    private final Sensor magnetometer;
    private final Callback callback;
    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];
    private final float[] gravity = new float[3];
    private final float[] geomagnetic = new float[3];

    private float headingDegrees;
    private float headingCandidateDegrees;
    private boolean hasHeading;
    private boolean hasGravity;
    private boolean hasGeomagnetic;
    private int candidateSamples;
    private int sensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
    private long candidateStartedElapsedMs;

    VehicleHeadingTracker(Context context, Callback callback) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager == null
                ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotationSensor == null && sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        } else {
            accelerometer = null;
            magnetometer = null;
        }
        this.callback = callback;
    }

    boolean hasSensor() {
        return rotationSensor != null
                || (accelerometer != null && magnetometer != null);
    }

    void register() {
        resetEstimate();
        hasGravity = false;
        hasGeomagnetic = false;
        sensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
        if (sensorManager == null) {
            return;
        }
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
        } else {
            if (accelerometer != null) {
                sensorManager.registerListener(
                        this,
                        accelerometer,
                        SensorManager.SENSOR_DELAY_UI
                );
            }
            if (magnetometer != null) {
                sensorManager.registerListener(
                        this,
                        magnetometer,
                        SensorManager.SENSOR_DELAY_UI
                );
            }
        }
    }

    void unregister() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        resetEstimate();
        hasGravity = false;
        hasGeomagnetic = false;
        sensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
    }

    float headingDegrees() {
        return headingDegrees;
    }

    boolean hasHeading() {
        return hasHeading;
    }

    boolean isReliable() {
        return sensorAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            sensorAccuracy = event.accuracy;
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            lowPass(event.values, gravity);
            hasGravity = true;
            if (!hasGeomagnetic
                    || !SensorManager.getRotationMatrix(
                    rotationMatrix,
                    null,
                    gravity,
                    geomagnetic
            )) {
                return;
            }
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            lowPass(event.values, geomagnetic);
            hasGeomagnetic = true;
            if (!hasGravity
                    || !SensorManager.getRotationMatrix(
                    rotationMatrix,
                    null,
                    gravity,
                    geomagnetic
            )) {
                return;
            }
        } else {
            return;
        }

        SensorManager.getOrientation(rotationMatrix, orientation);
        float reading = VehicleFinderMath.normalizeDegrees(
                (float) Math.toDegrees(orientation[0])
        );
        updateEstimate(reading, SystemClock.elapsedRealtime());
        if (hasHeading) {
            callback.onHeadingChanged();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        int type = sensor.getType();
        if (type != Sensor.TYPE_ROTATION_VECTOR
                && type != Sensor.TYPE_ACCELEROMETER
                && type != Sensor.TYPE_MAGNETIC_FIELD) {
            return;
        }
        if (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
            resetEstimate();
        }
        sensorAccuracy = accuracy;
        callback.onHeadingChanged();
    }

    private void updateEstimate(float reading, long nowElapsedMs) {
        if (!hasHeading) {
            if (candidateSamples == 0
                    || !VehicleFinderMath.headingSamplesConsistent(
                    headingCandidateDegrees,
                    reading,
                    WARMUP_TOLERANCE_DEGREES
            )) {
                headingCandidateDegrees = reading;
                candidateStartedElapsedMs = nowElapsedMs;
                candidateSamples = 1;
                return;
            }
            candidateSamples++;
            if (candidateSamples >= WARMUP_SAMPLES
                    && nowElapsedMs - candidateStartedElapsedMs >= WARMUP_MIN_MS) {
                headingDegrees = reading;
                hasHeading = true;
                candidateSamples = 0;
            }
            return;
        }
        float delta = VehicleFinderMath.normalizeSignedDegrees(reading - headingDegrees);
        headingDegrees = VehicleFinderMath.normalizeDegrees(
                headingDegrees + delta * 0.18f
        );
    }

    private void resetEstimate() {
        hasHeading = false;
        headingDegrees = 0f;
        headingCandidateDegrees = 0f;
        candidateSamples = 0;
        candidateStartedElapsedMs = 0L;
    }

    private static void lowPass(float[] input, float[] output) {
        for (int index = 0; index < output.length; index++) {
            output[index] = output[index] * 0.82f + input[index] * 0.18f;
        }
    }
}
