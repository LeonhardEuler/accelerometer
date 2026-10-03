package com.example.runningcadence;

import java.util.ArrayDeque;
import java.util.Deque;

public final class CadenceSmoother {
    private static final long WINDOW_MS = 30_000;
    private final Deque<Sample> samples = new ArrayDeque<>();
    private long sum;

    public int update(int cadence, long nowMs) {
        if (cadence < 0) {
            throw new IllegalArgumentException("Cadence cannot be negative.");
        }
        if (cadence == 0) {
            reset();
            return 0;
        }
        if (!samples.isEmpty() && nowMs <= samples.getLast().timeMs) {
            reset();
        }
        samples.addLast(new Sample(cadence, nowMs));
        sum += cadence;
        while (nowMs - samples.getFirst().timeMs >= WINDOW_MS) {
            sum -= samples.removeFirst().cadence;
        }
        return Math.round((float) sum / samples.size());
    }

    public void reset() {
        samples.clear();
        sum = 0;
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
