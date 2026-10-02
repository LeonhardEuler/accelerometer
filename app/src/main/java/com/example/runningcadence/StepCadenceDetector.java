package com.example.runningcadence;

import java.util.ArrayDeque;
import java.util.Deque;

public final class StepCadenceDetector {
    private static final long CALIBRATION_NS = 800_000_000L;
    private static final long MIN_STEP_INTERVAL_NS = 250_000_000L;
    private static final long STOP_TIMEOUT_NS = 2_000_000_000L;
    private static final long HISTORY_NS = 10_000_000_000L;
    private static final float STEP_THRESHOLD = 1.15f;
    private static final float RESET_THRESHOLD = 0.55f;
    private static final float GRAVITY_FILTER = 0.90f;
    private static final float SMOOTHING_FILTER = 0.72f;

    private final Deque<Long> stepTimes = new ArrayDeque<>();

    private boolean initialized;
    private boolean armed = true;
    private long firstSampleTime;
    private long lastStepTime = Long.MIN_VALUE;
    private float gravityX;
    private float gravityY;
    private float gravityZ;
    private float smoothedMagnitude;

    public boolean addSample(long timestampNs, float x, float y, float z) {
        if (!initialized) {
            initialized = true;
            firstSampleTime = timestampNs;
            gravityX = x;
            gravityY = y;
            gravityZ = z;
            return false;
        }

        gravityX = GRAVITY_FILTER * gravityX + (1f - GRAVITY_FILTER) * x;
        gravityY = GRAVITY_FILTER * gravityY + (1f - GRAVITY_FILTER) * y;
        gravityZ = GRAVITY_FILTER * gravityZ + (1f - GRAVITY_FILTER) * z;

        float linearX = x - gravityX;
        float linearY = y - gravityY;
        float linearZ = z - gravityZ;
        float magnitude = (float) Math.sqrt(
                linearX * linearX + linearY * linearY + linearZ * linearZ);
        smoothedMagnitude = SMOOTHING_FILTER * smoothedMagnitude
                + (1f - SMOOTHING_FILTER) * magnitude;

        if (timestampNs - firstSampleTime < CALIBRATION_NS) {
            return false;
        }

        if (smoothedMagnitude < RESET_THRESHOLD) {
            armed = true;
        }

        boolean intervalPassed = lastStepTime == Long.MIN_VALUE
                || timestampNs - lastStepTime >= MIN_STEP_INTERVAL_NS;
        if (armed && intervalPassed && smoothedMagnitude >= STEP_THRESHOLD) {
            if (lastStepTime != Long.MIN_VALUE
                    && timestampNs - lastStepTime > STOP_TIMEOUT_NS) {
                stepTimes.clear();
            }
            armed = false;
            lastStepTime = timestampNs;
            stepTimes.addLast(timestampNs);
            removeOldSteps(timestampNs);
            return true;
        }

        removeOldSteps(timestampNs);
        return false;
    }

    public int getStepsPerMinute(long timestampNs) {
        removeOldSteps(timestampNs);
        if (lastStepTime == Long.MIN_VALUE
                || timestampNs - lastStepTime > STOP_TIMEOUT_NS
                || stepTimes.size() < 2) {
            return 0;
        }

        long first = stepTimes.getFirst();
        long last = stepTimes.getLast();
        long duration = last - first;
        if (duration <= 0) {
            return 0;
        }

        double cadence = (stepTimes.size() - 1) * 60_000_000_000.0 / duration;
        return (int) Math.round(cadence);
    }

    public int getTotalSteps() {
        return stepTimes.size();
    }

    public void reset() {
        initialized = false;
        armed = true;
        firstSampleTime = 0;
        lastStepTime = Long.MIN_VALUE;
        gravityX = 0;
        gravityY = 0;
        gravityZ = 0;
        smoothedMagnitude = 0;
        stepTimes.clear();
    }

    private void removeOldSteps(long timestampNs) {
        while (!stepTimes.isEmpty() && timestampNs - stepTimes.getFirst() > HISTORY_NS) {
            stepTimes.removeFirst();
        }
    }
}
