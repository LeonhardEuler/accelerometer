package com.example.runningcadence;

public final class StepCadenceDetector {
    private static final long CALIBRATION_NS = 600_000_000L;
    private static final long MAX_SAMPLE_GAP_NS = 500_000_000L;
    private static final double MIN_PEAK = 0.45;

    private final StepCadenceTracker cadence = new StepCadenceTracker();

    private boolean initialized;
    private boolean armed;
    private boolean trackingPeak;
    private long firstSampleTime;
    private long lastSampleTime;
    private long peakTime;
    private double gravityMagnitude;
    private double filteredAcceleration;
    private double envelope;
    private double peak;

    public boolean addSample(long timestampNs, float x, float y, float z) {
        double magnitude = Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        if (!initialized || timestampNs - lastSampleTime > MAX_SAMPLE_GAP_NS) {
            initialized = true;
            firstSampleTime = timestampNs;
            lastSampleTime = timestampNs;
            gravityMagnitude = magnitude;
            filteredAcceleration = 0;
            envelope = 0;
            trackingPeak = false;
            armed = false;
            cadence.clearRhythm();
            return false;
        }
        if (timestampNs <= lastSampleTime) {
            return false;
        }

        double dt = (timestampNs - lastSampleTime) / 1_000_000_000.0;
        lastSampleTime = timestampNs;
        gravityMagnitude += dt / (0.75 + dt) * (magnitude - gravityMagnitude);
        // Preserve the sign: rectifying the signal before smoothing can prevent re-arming.
        double acceleration = magnitude - gravityMagnitude;
        filteredAcceleration += dt / (0.04 + dt) * (acceleration - filteredAcceleration);
        envelope += dt / (0.6 + dt) * (Math.abs(filteredAcceleration) - envelope);

        if (timestampNs - firstSampleTime < CALIBRATION_NS) {
            return false;
        }

        if (filteredAcceleration < -0.1) {
            armed = true;
        }
        if (!trackingPeak && armed
                && filteredAcceleration >= Math.max(MIN_PEAK, envelope * 0.5)) {
            trackingPeak = true;
            armed = false;
            peak = filteredAcceleration;
            peakTime = timestampNs;
        }
        if (trackingPeak) {
            if (filteredAcceleration > peak) {
                peak = filteredAcceleration;
                peakTime = timestampNs;
            }
            if (filteredAcceleration <= peak * 0.6) {
                trackingPeak = false;
                return cadence.recordStep(peakTime, timestampNs);
            }
        }
        return false;
    }

    public int getStepsPerMinute(long timestampNs) {
        return cadence.getStepsPerMinute(timestampNs);
    }

    public double getFilteredAcceleration() {
        return filteredAcceleration;
    }

    public int getTotalSteps() {
        return cadence.getTotalSteps();
    }

    public void reset() {
        initialized = false;
        armed = false;
        trackingPeak = false;
        firstSampleTime = 0;
        lastSampleTime = 0;
        peakTime = 0;
        gravityMagnitude = 0;
        filteredAcceleration = 0;
        envelope = 0;
        peak = 0;
        cadence.reset();
    }
}
