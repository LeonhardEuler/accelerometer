package com.example.runningcadence;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.spotify.android.appremote.api.SpotifyAppRemote;
import com.spotify.protocol.types.PlayerState;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity
        implements SensorEventListener, SpotifyPlayback.Listener {
    private static final String TAG = "RunningCadence";
    private static final long UI_REFRESH_MS = 250;
    private final StepCadenceDetector detector = new StepCadenceDetector();
    private final CadenceStabilityTracker stability = new CadenceStabilityTracker();
    private final MusicSearchGate searchGate = new MusicSearchGate();
    private final HttpJsonTransport transport = new HttpJsonTransport();
    private final ReccoBeatsClient reccoBeats = new ReccoBeatsClient(transport);
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private final Deque<Song> suggestions = new ArrayDeque<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable uiRefresh = new Runnable() {
        @Override
        public void run() {
            renderCadence();
            handler.postDelayed(this, UI_REFRESH_MS);
        }
    };

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private TextView cadenceText;
    private TextView statusText;
    private TextView musicStatus;
    private TextView songText;
    private TextView spotifyStatus;
    private Button connectButton;
    private Button nextButton;
    private Button playButton;
    private Button openButton;
    private SharedPreferences preferences;
    private SpotifyPlayback spotify;
    private Genre genre;
    private Song selectedSong;
    private String lastPlayedId;
    private String lastAutoAttemptId;
    private Future<?> searchTask;
    private int requestVersion;
    private int requestedCadence;
    private int currentCadence;
    private int stableCadence;
    private boolean foreground;
    private boolean sensing;
    private boolean searching;
    private boolean manualPlayRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getPreferences(MODE_PRIVATE);
        int savedGenre = preferences.getInt("genre", 0);
        if (savedGenre < 0 || savedGenre >= Genre.values().length) {
            Log.w(TAG, "Saved genre is invalid; selecting Pop.");
            savedGenre = 0;
        }
        genre = Genre.values()[savedGenre];
        spotify = new SpotifyPlayback(this, BuildConfig.SPOTIFY_CLIENT_ID, this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(createContentView());

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer == null) {
            statusText.setText(R.string.accelerometer_unavailable);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
        if (preferences.getBoolean("spotify_enabled", false) && isSpotifyConfigured()) {
            connectSpotify(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        detector.reset();
        stability.reset();
        currentCadence = 0;
        stableCadence = 0;
        if (accelerometer != null) {
            sensing = sensorManager.registerListener(
                    this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            statusText.setText(sensing ? R.string.start_running : R.string.sensor_start_failed);
        }
        if (sensing) {
            handler.post(uiRefresh);
        }
    }

    @Override
    protected void onPause() {
        sensing = false;
        stableCadence = 0;
        stability.reset();
        handler.removeCallbacks(uiRefresh);
        cancelSearch();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        foreground = false;
        spotify.disconnect();
        updateConnectButton();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        searchExecutor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }

        detector.addSample(
                event.timestamp, event.values[0], event.values[1], event.values[2]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void renderCadence() {
        long now = SystemClock.elapsedRealtime();
        currentCadence = detector.getStepsPerMinute(SystemClock.elapsedRealtimeNanos());
        stableCadence = stability.update(currentCadence, now);
        cadenceText.setText(String.format(Locale.getDefault(), "%d", currentCadence));
        statusText.setText(currentCadence == 0 ? R.string.start_running
                : stableCadence == 0 ? R.string.stabilizing : R.string.cadence_stable);

        if (searching && (currentCadence == 0
                || Math.abs(currentCadence - requestedCadence) > MusicSearchGate.CADENCE_CHANGE)) {
            cancelSearch();
        }
        if (!searching && searchGate.shouldSearch(genre, stableCadence, now)) {
            searchMusic(now);
        }
        maybeAutoPlay();
        long delay = searchGate.remainingDelayMs(now);
        nextButton.setEnabled(stableCadence > 0 && !searching);
        nextButton.setText(delay > 0 && suggestions.isEmpty()
                ? getString(R.string.retry_countdown, (delay + 999) / 1000)
                : getString(R.string.next_match));
    }

    private void searchMusic(long now) {
        searching = true;
        requestedCadence = stableCadence;
        searchGate.started(genre, stableCadence, now);
        int version = ++requestVersion;
        int target = stableCadence;
        Genre requestedGenre = genre;
        String excludedId = lastPlayedId;
        musicStatus.setText(getString(R.string.searching_music, target));
        searchTask = searchExecutor.submit(() -> {
            try {
                ReccoBeatsClient.Result result = reccoBeats.findMatches(
                        requestedGenre, target, excludedId);
                handler.post(() -> finishSearch(version, requestedGenre, target, result));
            } catch (IOException error) {
                handler.post(() -> failSearch(version, error));
            }
        });
    }

    private boolean isCurrentRequest(int version) {
        return version == requestVersion && foreground && sensing;
    }

    private void finishSearch(int version, Genre requestedGenre, int target,
            ReccoBeatsClient.Result result) {
        if (!isCurrentRequest(version)) {
            return;
        }
        searching = false;
        searchTask = null;
        if (genre != requestedGenre || stableCadence == 0
                || Math.abs(currentCadence - target) > ReccoBeatsClient.BPM_TOLERANCE) {
            musicStatus.setText(R.string.cadence_changed);
            searchGate.requestAnother();
            return;
        }
        suggestions.clear();
        suggestions.addAll(result.songs);
        if (result.unavailableTracks > 0) {
            Log.i(TAG, result.unavailableTracks + " recommendations lacked BPM or a Spotify link.");
        }
        if (!selectNextMatch()) {
            clearSong();
            musicStatus.setText(getString(R.string.no_matching_music,
                    target, result.unavailableTracks));
        } else {
            musicStatus.setText(getString(R.string.matches_found,
                    suggestions.size() + 1, result.unavailableTracks));
        }
    }

    private void failSearch(int version, IOException error) {
        if (!isCurrentRequest(version)) {
            return;
        }
        searching = false;
        searchTask = null;
        Log.e(TAG, "Music lookup failed.", error);
        if (error instanceof HttpJsonTransport.ApiException
                && ((HttpJsonTransport.ApiException) error).status == 429) {
            long delay = ((HttpJsonTransport.ApiException) error).retryDelayMs;
            searchGate.defer(SystemClock.elapsedRealtime(), delay);
            musicStatus.setText(getString(R.string.rate_limited, (delay + 999) / 1000));
        } else {
            musicStatus.setText(getString(R.string.music_search_failed, error.getMessage()));
        }
    }

    private void cancelSearch() {
        if (!searching) {
            return;
        }
        requestVersion++;
        searching = false;
        if (searchTask != null) {
            searchTask.cancel(true);
            searchTask = null;
        }
        transport.cancel();
        searchGate.requestAnother();
        musicStatus.setText(R.string.cadence_changed);
    }

    private boolean selectNextMatch() {
        while (!suggestions.isEmpty()) {
            Song song = suggestions.removeFirst();
            if (Math.abs(song.bpm - stableCadence) > ReccoBeatsClient.BPM_TOLERANCE
                    || Math.abs(song.bpm - currentCadence) > ReccoBeatsClient.BPM_TOLERANCE) {
                continue;
            }
            selectedSong = song;
            lastAutoAttemptId = null;
            songText.setText(getString(R.string.song_details, song.title, song.artist, song.bpm));
            playButton.setEnabled(true);
            openButton.setEnabled(true);
            maybeAutoPlay();
            return true;
        }
        return false;
    }

    private void clearSong() {
        selectedSong = null;
        manualPlayRequested = false;
        songText.setText(R.string.no_song);
        playButton.setEnabled(false);
        openButton.setEnabled(false);
    }

    private void maybeAutoPlay() {
        if (foreground && sensing && stableCadence > 0 && selectedSong != null
                && spotify.isConnected()
                && !selectedSong.spotifyId.equals(lastPlayedId)
                && !selectedSong.spotifyId.equals(lastAutoAttemptId)
                && Math.abs(selectedSong.bpm - stableCadence) <= ReccoBeatsClient.BPM_TOLERANCE
                && Math.abs(selectedSong.bpm - currentCadence) <= ReccoBeatsClient.BPM_TOLERANCE) {
            playSelectedSong();
        }
    }

    private void playSelectedSong() {
        if (selectedSong == null) {
            return;
        }
        if (!spotify.isConnected()) {
            manualPlayRequested = true;
            connectSpotify(true);
            return;
        }
        manualPlayRequested = false;
        lastAutoAttemptId = selectedSong.spotifyId;
        spotifyStatus.setText(R.string.starting_spotify_playback);
        spotify.play(selectedSong);
    }

    private boolean isSpotifyConfigured() {
        return !BuildConfig.SPOTIFY_CLIENT_ID.isEmpty();
    }

    private void connectSpotify(boolean showAuthorization) {
        if (!isSpotifyConfigured()) {
            spotifyStatus.setText(R.string.spotify_setup_required);
            return;
        }
        if (!SpotifyAppRemote.isSpotifyInstalled(this)) {
            spotifyStatus.setText(R.string.spotify_not_installed);
            return;
        }
        preferences.edit().putBoolean("spotify_enabled", true).apply();
        spotifyStatus.setText(R.string.connecting_spotify);
        spotify.connect(showAuthorization);
        updateConnectButton();
    }

    private void updateConnectButton() {
        connectButton.setEnabled(!spotify.isConnecting() && !spotify.isConnected());
        connectButton.setText(spotify.isConnected()
                ? R.string.spotify_connected : R.string.connect_spotify);
    }

    @Override
    public void onSpotifyConnected() {
        updateConnectButton();
        spotifyStatus.setText(R.string.spotify_ready);
        if (manualPlayRequested && selectedSong != null) {
            playSelectedSong();
        } else {
            maybeAutoPlay();
        }
    }

    @Override
    public void onSpotifyConnectionFailed(Throwable error) {
        updateConnectButton();
        Log.e(TAG, "Spotify connection failed.", error);
        spotifyStatus.setText(getString(R.string.spotify_connection_failed,
                error.getClass().getSimpleName()));
    }

    @Override
    public void onPlaybackAccepted(Song song) {
        lastPlayedId = song.spotifyId;
        spotifyStatus.setText(getString(R.string.spotify_playback_accepted, song.title));
    }

    @Override
    public void onPlaybackFailed(Throwable error) {
        Log.e(TAG, "Spotify playback failed.", error);
        spotifyStatus.setText(getString(R.string.spotify_playback_failed,
                error.getClass().getSimpleName()));
    }

    @Override
    public void onPlayerState(PlayerState state) {
        if (state.track != null) {
            spotifyStatus.setText(getString(state.isPaused
                    ? R.string.spotify_paused : R.string.spotify_playing, state.track.name));
        }
    }

    private void openInSpotify() {
        if (selectedSong == null) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(selectedSong.spotifyUrl())));
        } catch (ActivityNotFoundException error) {
            Log.e(TAG, "No application can open the Spotify track.", error);
            spotifyStatus.setText(R.string.spotify_cannot_open);
        }
    }

    private ScrollView createContentView() {
        int padding = dp(24);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(12, 18, 28));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(padding, padding, padding, padding);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            layout.setPadding(padding + insets.getSystemWindowInsetLeft(),
                    padding + insets.getSystemWindowInsetTop(),
                    padding + insets.getSystemWindowInsetRight(),
                    padding + insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title = createText(getString(R.string.title), 22, Color.LTGRAY);
        cadenceText = createText("0", 80, Color.WHITE);
        TextView unit = createText(getString(R.string.steps_per_minute), 20, Color.LTGRAY);
        statusText = createText(getString(R.string.start_running), 16, Color.rgb(96, 205, 255));

        layout.addView(title);
        layout.addView(cadenceText);
        layout.addView(unit);
        layout.addView(statusText);
        layout.addView(createText(getString(R.string.genre_label), 18, Color.WHITE));
        Spinner genres = new Spinner(this);
        genres.setContentDescription(getString(R.string.genre_label));
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.genres, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        genres.setAdapter(adapter);
        genres.setSelection(genre.ordinal());
        genres.setMinimumHeight(dp(48));
        genres.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Genre selected = Genre.values()[position];
                if (selected != genre) {
                    genre = selected;
                    preferences.edit().putInt("genre", position).apply();
                    cancelSearch();
                    suggestions.clear();
                    clearSong();
                    stability.reset();
                    stableCadence = 0;
                    musicStatus.setText(R.string.waiting_for_cadence);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        layout.addView(genres, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(createText(getString(R.string.genre_hint), 13, Color.LTGRAY));
        musicStatus = createText(getString(R.string.waiting_for_cadence), 15, Color.LTGRAY);
        songText = createText(getString(R.string.no_song), 19, Color.WHITE);
        layout.addView(musicStatus);
        layout.addView(songText);
        connectButton = createButton(R.string.connect_spotify, view -> connectSpotify(true));
        layout.addView(connectButton);
        spotifyStatus = createText(getString(isSpotifyConfigured()
                ? R.string.spotify_connect_hint : R.string.spotify_setup_required), 14, Color.LTGRAY);
        layout.addView(spotifyStatus);
        playButton = createButton(R.string.play_match, view -> playSelectedSong());
        playButton.setEnabled(false);
        layout.addView(playButton);
        nextButton = createButton(R.string.next_match, view -> {
            if (!selectNextMatch()) {
                searchGate.requestAnother();
                musicStatus.setText(R.string.waiting_for_search);
            }
        });
        nextButton.setEnabled(false);
        layout.addView(nextButton);
        openButton = createButton(R.string.open_spotify, view -> openInSpotify());
        openButton.setEnabled(false);
        layout.addView(openButton);
        layout.addView(createText(getString(R.string.music_attribution), 12, Color.LTGRAY));
        scroll.addView(layout);
        return scroll;
    }

    private Button createButton(int text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private TextView createText(String text, float textSizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(textSizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
