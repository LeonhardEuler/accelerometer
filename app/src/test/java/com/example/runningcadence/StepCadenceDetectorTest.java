package com.example.runningcadence;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StepCadenceDetectorTest {
    @Test
    public void estimatesRunningCadenceFromSyntheticAcceleration() {
        StepCadenceDetector detector = new StepCadenceDetector();
        long sampleIntervalNs = 10_000_000L;
        long stepIntervalNs = 333_333_333L;
        long durationNs = 8_000_000_000L;

        for (long time = 0; time <= durationNs; time += sampleIntervalNs) {
            long phase = time % stepIntervalNs;
            float impact = phase < 50_000_000L ? 5.5f : 0f;
            detector.addSample(time, 0f, 0f, 9.81f + impact);
        }

        int cadence = detector.getStepsPerMinute(durationNs);
        assertTrue("Expected about 180 steps/minute, got " + cadence,
                cadence >= 170 && cadence <= 190);
    }
}
