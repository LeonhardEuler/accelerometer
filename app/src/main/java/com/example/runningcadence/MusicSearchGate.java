package com.example.runningcadence;

public final class MusicSearchGate {
    public static final long COOLDOWN_MS = 30_000;
    public static final int CADENCE_CHANGE = 10;

    private Genre lastGenre;
    private int lastCadence;
    private long notBeforeMs;
    private boolean requested = true;

    public boolean shouldSearch(Genre genre, int stableCadence, long nowMs) {
        return stableCadence >= CadenceStabilityTracker.MIN_CADENCE
                && stableCadence <= CadenceStabilityTracker.MAX_CADENCE
                && nowMs >= notBeforeMs
                && (requested || genre != lastGenre
                || Math.abs(stableCadence - lastCadence) >= CADENCE_CHANGE);
    }

    public void started(Genre genre, int cadence, long nowMs) {
        lastGenre = genre;
        lastCadence = cadence;
        requested = false;
        notBeforeMs = nowMs + COOLDOWN_MS;
    }

    public void requestAnother() {
        requested = true;
    }

    public void defer(long nowMs, long delayMs) {
        notBeforeMs = Math.max(notBeforeMs, nowMs + delayMs);
    }

    public long remainingDelayMs(long nowMs) {
        return Math.max(0, notBeforeMs - nowMs);
    }
}
