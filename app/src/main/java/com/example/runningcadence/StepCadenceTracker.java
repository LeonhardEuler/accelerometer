package com.example.runningcadence;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

public final class StepCadenceTracker {
    private static final long MIN_STEP_INTERVAL_NS = 220_000_000L;
    private static final long MAX_STEP_INTERVAL_NS = 1_200_000_000L;
    private static final long STOP_TIMEOUT_NS = 2_000_000_000L;
    private static final int MAX_HISTORY_STEPS = 9;
    private static final int MIN_HISTORY_STEPS = 5;

    private final Deque<Long> stepTimes = new ArrayDeque<>();
    private long lastReceivedTime = Long.MIN_VALUE;
    private int totalSteps;

    public boolean recordStep(long timestampNs, long receivedAtNs) {
        if (!stepTimes.isEmpty()) {
            long interval = timestampNs - stepTimes.getLast();
            if (interval < MIN_STEP_INTERVAL_NS) {
                return false;
            }
            if (interval > MAX_STEP_INTERVAL_NS) {
                stepTimes.clear();
            }
        }
        stepTimes.addLast(timestampNs);
        lastReceivedTime = receivedAtNs;
        totalSteps++;
        while (stepTimes.size() > MAX_HISTORY_STEPS) {
            stepTimes.removeFirst();
        }
        return true;
    }

    public int getStepsPerMinute(long nowNs) {
        if (lastReceivedTime == Long.MIN_VALUE
                || nowNs - lastReceivedTime > STOP_TIMEOUT_NS
                || stepTimes.size() < MIN_HISTORY_STEPS) {
            return 0;
        }
        long[] intervals = new long[stepTimes.size() - 1];
        long previous = stepTimes.getFirst();
        int index = 0;
        for (long step : stepTimes) {
            if (step != previous) {
                intervals[index++] = step - previous;
            }
            previous = step;
        }
        Arrays.sort(intervals);
        double median = (intervals[(intervals.length - 1) / 2]
                + intervals[intervals.length / 2]) / 2.0;
        double sum = 0;
        int consistentIntervals = 0;
        for (long interval : intervals) {
            if (Math.abs(interval - median) <= median * 0.25) {
                sum += interval;
                consistentIntervals++;
            }
        }
        // Reject isolated taps, but tolerate a missed impact in a regular step sequence.
        if (consistentIntervals < 3 || consistentIntervals * 2 <= intervals.length) {
            return 0;
        }
        return (int) Math.round(60_000_000_000.0 * consistentIntervals / sum);
    }

    public int getTotalSteps() {
        return totalSteps;
    }

    public void clearRhythm() {
        stepTimes.clear();
        lastReceivedTime = Long.MIN_VALUE;
    }

    public void reset() {
        clearRhythm();
        totalSteps = 0;
    }
}
