package com.example.runningcadence;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ReccoBeatsClient {
    public static final double BPM_TOLERANCE = 2.0;
    public static final int MIN_POPULARITY = 60;
    private static final int TARGET_POPULARITY = 80;
    private static final int CANDIDATE_LIMIT = 100;
    private static final int FEATURE_BATCH_SIZE = 40;
    private static final String BASE_URL = "https://api.reccobeats.com";
    private static final Pattern SPOTIFY_TRACK = Pattern.compile(
            "https://open\\.spotify\\.com/(?:intl-[a-z-]+/)?track/([A-Za-z0-9]{22})(?:\\?[^#]*)?");

    public interface Transport {
        String get(String url) throws IOException;
    }

    private final Transport transport;

    public ReccoBeatsClient(Transport transport) {
        this.transport = transport;
    }

    public Result findCandidates(Genre genre, int cadence) throws IOException {
        if (genre == null || cadence < CadenceStabilityTracker.MIN_CADENCE
                || cadence > CadenceStabilityTracker.MAX_CADENCE) {
            throw new IllegalArgumentException("A genre and running cadence of 100-240 are required.");
        }
        checkCancelled();
        JsonArray recommendations = content(transport.get(BASE_URL
                + "/v1/track/recommendation?size=" + CANDIDATE_LIMIT
                + "&seeds=" + genre.seedTrackId + "&tempo=" + cadence
                + "&popularity=" + TARGET_POPULARITY + "&featureWeight=5"));
        if (recommendations.size() > CANDIDATE_LIMIT) {
            throw new IOException("ReccoBeats returned more recommendations than requested.");
        }

        Map<String, Candidate> candidates = new LinkedHashMap<>();
        int unavailable = 0;
        int belowPopularity = 0;
        for (JsonElement element : recommendations) {
            JsonObject track = object(element);
            Integer popularity = readPopularity(track);
            if (popularity == null) {
                unavailable++;
                continue;
            }
            if (popularity < MIN_POPULARITY) {
                belowPopularity++;
                continue;
            }
            String id = requiredText(track, "id");
            String title = requiredText(track, "trackTitle");
            String spotifyId = spotifyId(optionalText(track, "href"));
            if (spotifyId == null) {
                unavailable++;
                continue;
            }
            JsonElement artists = track.get("artists");
            if (artists == null || !artists.isJsonArray()) {
                throw new IOException("ReccoBeats returned a track without an artists array.");
            }
            List<String> names = new ArrayList<>();
            for (JsonElement artist : artists.getAsJsonArray()) {
                names.add(requiredText(object(artist), "name"));
            }
            candidates.put(id, new Candidate(title, join(names, ", "), spotifyId, popularity));
        }
        if (candidates.isEmpty()) {
            return new Result(Collections.emptyList(), recommendations.size(), unavailable, belowPopularity);
        }

        Map<String, Double> tempos = new HashMap<>();
        List<String> candidateIds = new ArrayList<>(candidates.keySet());
        for (int offset = 0; offset < candidateIds.size(); offset += FEATURE_BATCH_SIZE) {
            checkCancelled();
            List<String> batch = candidateIds.subList(offset,
                    Math.min(offset + FEATURE_BATCH_SIZE, candidateIds.size()));
            String ids = URLEncoder.encode(join(batch, ","), "UTF-8");
            JsonArray features = content(transport.get(BASE_URL + "/v1/audio-features?ids=" + ids));
            for (JsonElement element : features) {
                JsonObject feature = object(element);
                String id = requiredText(feature, "id");
                JsonElement tempo = feature.get("tempo");
                if (tempo == null || tempo.isJsonNull()) {
                    continue;
                }
                if (!tempo.isJsonPrimitive() || !tempo.getAsJsonPrimitive().isNumber()) {
                    throw new IOException("ReccoBeats returned a non-numeric tempo.");
                }
                double bpm = tempo.getAsDouble();
                if (!Double.isNaN(bpm) && !Double.isInfinite(bpm) && bpm > 0) {
                    tempos.put(id, bpm);
                }
            }
        }

        checkCancelled();
        List<Song> songs = new ArrayList<>();
        Set<String> seenSpotifyIds = new HashSet<>();
        for (Map.Entry<String, Candidate> entry : candidates.entrySet()) {
            Double bpm = tempos.get(entry.getKey());
            if (bpm == null) {
                unavailable++;
                continue;
            }
            Candidate track = entry.getValue();
            if (seenSpotifyIds.add(track.spotifyId)) {
                songs.add(new Song(track.title, track.artist, track.spotifyId, bpm, track.popularity));
            }
        }
        return new Result(Collections.unmodifiableList(songs), recommendations.size(), unavailable, belowPopularity);
    }

    public static boolean isEligible(Song song, int cadence) {
        return song != null && song.popularity >= MIN_POPULARITY
                && Math.abs(song.bpm - cadence) <= BPM_TOLERANCE;
    }

    private static Integer readPopularity(JsonObject track) throws IOException {
        JsonElement value = track.get("popularity");
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("ReccoBeats returned a non-numeric popularity.");
        }
        double score = value.getAsDouble();
        if (Double.isNaN(score) || score < 0 || score > 100 || score != Math.floor(score)) {
            throw new IOException("ReccoBeats returned a popularity outside the integer range 0-100.");
        }
        return (int) score;
    }

    private static String join(Iterable<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append(separator);
            }
            result.append(value);
        }
        return result.toString();
    }

    private static JsonArray content(String json) throws IOException {
        try {
            JsonObject root = object(JsonParser.parseString(json));
            JsonElement content = root.get("content");
            if (content == null || !content.isJsonArray()) {
                throw new IOException("ReccoBeats response is missing the content array.");
            }
            return content.getAsJsonArray();
        } catch (JsonParseException exception) {
            throw new IOException("ReccoBeats returned invalid JSON.", exception);
        }
    }

    private static JsonObject object(JsonElement element) throws IOException {
        if (element == null || !element.isJsonObject()) {
            throw new IOException("ReccoBeats returned an invalid object.");
        }
        return element.getAsJsonObject();
    }

    private static String requiredText(JsonObject object, String key) throws IOException {
        String value = optionalText(object, key);
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("ReccoBeats response is missing " + key + ".");
        }
        return value;
    }

    private static String optionalText(JsonObject object, String key) throws IOException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("ReccoBeats returned an invalid " + key + " field.");
        }
        return element.getAsString();
    }

    private static String spotifyId(String href) {
        if (href == null) {
            return null;
        }
        Matcher matcher = SPOTIFY_TRACK.matcher(href);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Music search cancelled.");
        }
    }

    public static final class Result {
        public final List<Song> candidates;
        public final int returnedTracks;
        public final int unavailableTracks;
        public final int belowPopularityTracks;

        Result(List<Song> candidates, int returnedTracks, int unavailableTracks, int belowPopularityTracks) {
            this.candidates = candidates;
            this.returnedTracks = returnedTracks;
            this.unavailableTracks = unavailableTracks;
            this.belowPopularityTracks = belowPopularityTracks;
        }

        public List<Song> matchesFor(int cadence, String excludedSpotifyId) {
            if (cadence < CadenceStabilityTracker.MIN_CADENCE
                    || cadence > CadenceStabilityTracker.MAX_CADENCE) {
                throw new IllegalArgumentException("Running cadence must be 100-240.");
            }
            List<Song> matches = new ArrayList<>();
            for (Song song : candidates) {
                if (!song.spotifyId.equals(excludedSpotifyId)
                        && isEligible(song, cadence)) {
                    matches.add(song);
                }
            }
            Collections.sort(matches, (first, second) -> Double.compare(
                    Math.abs(first.bpm - cadence), Math.abs(second.bpm - cadence)));
            return Collections.unmodifiableList(matches);
        }
    }

    private static final class Candidate {
        final String title;
        final String artist;
        final String spotifyId;
        final int popularity;

        Candidate(String title, String artist, String spotifyId, int popularity) {
            this.title = title;
            this.artist = artist;
            this.spotifyId = spotifyId;
            this.popularity = popularity;
        }
    }
}
