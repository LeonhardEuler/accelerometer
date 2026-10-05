package com.example.runningcadence;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SongFeedback {
    public enum Rating {
        NEUTRAL, LIKED, DISLIKED
    }

    private final Map<String, Rating> ratings = new LinkedHashMap<>();

    public Rating rating(String spotifyId) {
        Rating rating = ratings.get(spotifyId);
        return rating == null ? Rating.NEUTRAL : rating;
    }

    public boolean isDisliked(String spotifyId) {
        return rating(spotifyId) == Rating.DISLIKED;
    }

    public void setRating(String spotifyId, Rating rating) {
        if (spotifyId == null || !spotifyId.matches("[A-Za-z0-9]{22}") || rating == null) {
            throw new IllegalArgumentException("Feedback requires a Spotify track ID and rating.");
        }
        ratings.remove(spotifyId);
        if (rating != Rating.NEUTRAL) {
            ratings.put(spotifyId, rating);
        }
    }

    public int count(Rating rating) {
        int count = 0;
        for (Rating value : ratings.values()) {
            if (value == rating) {
                count++;
            }
        }
        return count;
    }

    public Snapshot snapshot(Genre genre) {
        LinkedHashSet<String> positive = new LinkedHashSet<>();
        positive.add(genre.seedTrackId);
        List<String> negative = new ArrayList<>();
        Set<String> excluded = new HashSet<>();
        List<String> newestFirst = new ArrayList<>(ratings.keySet());
        Collections.reverse(newestFirst);
        for (String id : newestFirst) {
            if (rating(id) == Rating.LIKED && positive.size() < 5) {
                positive.add(id);
            } else if (rating(id) == Rating.DISLIKED) {
                excluded.add(id);
                // The style anchor describes the genre even if that particular track is blocked.
                if (negative.size() < 5 && !id.equals(genre.seedTrackId)) {
                    negative.add(id);
                }
            }
        }
        return new Snapshot(new ArrayList<>(positive), negative, excluded);
    }

    public String toJson() {
        JsonArray entries = new JsonArray();
        for (Map.Entry<String, Rating> entry : ratings.entrySet()) {
            JsonObject object = new JsonObject();
            object.addProperty("id", entry.getKey());
            object.addProperty("rating", entry.getValue().name());
            entries.add(object);
        }
        return entries.toString();
    }

    public static SongFeedback fromJson(String json) {
        SongFeedback feedback = new SongFeedback();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("Saved feedback must be an array.");
        }
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Saved feedback contains an invalid entry.");
            }
            JsonObject entry = element.getAsJsonObject();
            feedback.setRating(requiredString(entry, "id"),
                    Rating.valueOf(requiredString(entry, "rating")));
        }
        return feedback;
    }

    private static String requiredString(JsonObject entry, String key) {
        JsonElement value = entry.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Saved feedback is missing " + key + ".");
        }
        return value.getAsString();
    }

    public static final class Snapshot {
        public final List<String> positiveSeeds;
        public final List<String> negativeSeeds;
        public final Set<String> excludedIds;

        private Snapshot(List<String> positiveSeeds, List<String> negativeSeeds, Set<String> excludedIds) {
            this.positiveSeeds = Collections.unmodifiableList(positiveSeeds);
            this.negativeSeeds = Collections.unmodifiableList(negativeSeeds);
            this.excludedIds = Collections.unmodifiableSet(excludedIds);
        }
    }
}
