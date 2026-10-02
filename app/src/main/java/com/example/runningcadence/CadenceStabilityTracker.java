package com.example.runningcadence;

import java.util.ArrayDeque;
import java.util.Deque;

public final class CadenceStabilityTracker {
    public static final int MIN_CADENCE = 100;
    public static final int MAX_CADENCE = 240;
    private static final long WINDOW_MS = 5_000;
    private static final long MAX_SAMPLE_GAP_MS = 1_000;
    private static final int MAX_SPREAD = 6;

    private final Deque<Sample> samples = new ArrayDeque<>();

    public int update(int cadence, long nowMs) {
        if (cadence < MIN_CADENCE || cadence > MAX_CADENCE) {
            reset();
            return 0;
        }
        if (!samples.isEmpty()
                && (nowMs <= samples.getLast().timeMs
                || nowMs - samples.getLast().timeMs > MAX_SAMPLE_GAP_MS)) {
            reset();
        }
        samples.addLast(new Sample(cadence, nowMs));

        // Keep the sample immediately before the window boundary for irregular sampling.
        while (samples.size() > 1) {
            Sample first = samples.removeFirst();
            if (nowMs - samples.getFirst().timeMs < WINDOW_MS) {
                samples.addFirst(first);
                break;
            }
        }
        if (nowMs - samples.getFirst().timeMs < WINDOW_MS) {
            return 0;
        }

        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        int sum = 0;
        for (Sample sample : samples) {
            minimum = Math.min(minimum, sample.cadence);
            maximum = Math.max(maximum, sample.cadence);
            sum += sample.cadence;
        }
        return maximum - minimum <= MAX_SPREAD
                ? Math.round((float) sum / samples.size()) : 0;
    }

    public void reset() {
        samples.clear();
    }

    private static final class Sample {
        final int cadence;
        final long timeMs;

        Sample(int cadence, long timeMs) {
            this.cadence = cadence;
            this.timeMs = timeMs;
        }
    }
}
