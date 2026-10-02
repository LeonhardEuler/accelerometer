package com.example.runningcadence;

public enum Genre {
    POP("0VjIjW4GlUZAMYd2vXMi3b"),
    ROCK("5UWwZ5lm5PKu6eKsHAGxOk"),
    HIP_HOP("1jS7v1W7iS5ND9IYqfOxWo"),
    ELECTRONIC("0IVMcU1JH2K3hN1N1SEBz3"),
    R_AND_B("1Qrg8KqiBpW07V7PNxwwwL"),
    COUNTRY("1mMLMZYXkMueg65jRRWG1l"),
    METAL("2MuWTIM3b0YEAskbeeFE1i"),
    INDIE("5FVd6KXrgO9B3JPmC8OPst");

    public final String seedTrackId;

    Genre(String seedTrackId) {
        this.seedTrackId = seedTrackId;
    }
}
