package com.example.runningcadence;

public final class Song {
    public final String title;
    public final String artist;
    public final String spotifyId;
    public final double bpm;
    public final int popularity;

    public Song(String title, String artist, String spotifyId, double bpm, int popularity) {
        if (title == null || title.trim().isEmpty() || artist == null
                || spotifyId == null || !spotifyId.matches("[A-Za-z0-9]{22}")
                || Double.isNaN(bpm) || Double.isInfinite(bpm) || bpm <= 0
                || popularity < 0 || popularity > 100) {
            throw new IllegalArgumentException(
                    "A song requires a title, Spotify track ID, valid BPM and popularity from 0 to 100.");
        }
        this.title = title;
        this.artist = artist;
        this.spotifyId = spotifyId;
        this.bpm = bpm;
        this.popularity = popularity;
    }

    public String spotifyUri() {
        return "spotify:track:" + spotifyId;
    }

    public String spotifyUrl() {
        return "https://open.spotify.com/track/" + spotifyId;
    }
}
