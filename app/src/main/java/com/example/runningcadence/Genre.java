package com.example.runningcadence;

public enum Genre {
    DANCE_EDM("0IVMcU1JH2K3hN1N1SEBz3"),
    UPTEMPO_HARDCORE("3bzay4OfqLDujCzt2SnYQe"),
    DRUM_AND_BASS("6LW3Z1GqbL78TIjfDyg4zp"),
    HOUSE("6ho0GyrWZN3mhi9zVRW7xi"),
    TECHNO("5NZdurYcFYOg3s2YyzeVgE"),
    TRANCE("0bikBdtMtDpNeTdz6VZvfv"),
    HARDSTYLE("5ZHdiROs72t0QpMHggDSNW"),
    UPBEAT_POP("0VjIjW4GlUZAMYd2vXMi3b"),
    POP_PUNK_ROCK("3l9CW99AHtExIRV4hW2N5m");

    public final String seedTrackId;

    Genre(String seedTrackId) {
        this.seedTrackId = seedTrackId;
    }
}
