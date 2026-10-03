package com.example.runningcadence;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.spotify.android.appremote.api.SpotifyAppRemote;
import com.spotify.android.appremote.api.error.AuthenticationFailedException;
import com.spotify.android.appremote.api.error.NotLoggedInException;
import com.spotify.android.appremote.api.error.OfflineModeException;
import com.spotify.android.appremote.api.error.UserNotAuthorizedException;
import com.spotify.protocol.types.PlayerState;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

public final class MainActivity extends Activity
        implements SensorEventListener, SpotifyPlayback.Listener {
    private static final String TAG = "RunningCadence";
    private static final long UI_REFRESH_MS = 250;
    private static final int SENSOR_PERIOD_US = 20_000;
    private static final long SENSOR_STALE_MS = 1_500;
    private static final int MOTION_PERMISSION_REQUEST = 100;
    private static final double SONG_CHANGE_THRESHOLD = 5.0;
    private final StepCadenceDetector detector = new StepCadenceDetector();
    private final StepCadenceTracker systemCadence = new StepCadenceTracker();
    private final CadenceStabilityTracker stability = new CadenceStabilityTracker();
    private final CadenceSmoother cadenceSmoother = new CadenceSmoother();
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
    private Sensor stepSensor;
    private TextView cadenceText;
    private TextView liveCadenceText;
    private TextView statusText;
    private TextView sensorStatus;
    private TextView sensorSource;
    private TextView musicStatus;
    private TextView songText;
    private TextView spotifyStatus;
    private Button connectButton;
    private Button nextButton;
    private Button playButton;
    private Button openButton;
    private Button motionPermissionButton;
    private SharedPreferences preferences;
    private SpotifyPlayback spotify;
    private Genre genre;
    private Song selectedSong;
    private Song managedSong;
    private ReccoBeatsClient.Result cachedCandidates;
    private String lastPlayedId;
    private String lastAutoAttemptId;
    private String spotifyTrackUri;
    private Future<?> searchTask;
    private int requestVersion;
    private int suggestionsCadence;
    private int currentCadence;
    private int smoothedCadence;
    private int stableCadence;
    private boolean foreground;
    private boolean sensing;
    private boolean usingSystemSteps;
    private boolean searching;
    private boolean manualSearchRequested;
    private boolean manualPlayRequested;
    private boolean playPending;
    private boolean pausePending;
    private boolean pausedForCadence;
    private boolean pauseAttemptedForStop;
    private boolean interactiveSpotifyConnection;
    private long sensorStartedMs;
    private long lastSensorCallbackMs = -1;
    private long rateWindowStartedMs;
    private int sensorEventsInWindow;
    private int sensorRate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getPreferences(MODE_PRIVATE);
        String savedGenre = preferences.getString("running_genre", Genre.DANCE_EDM.name());
        try {
            genre = Genre.valueOf(savedGenre);
        } catch (IllegalArgumentException error) {
            Log.w(TAG, "Saved running style is invalid; selecting Dance / EDM.", error);
            genre = Genre.DANCE_EDM;
        }
        preferences.edit().putString("running_genre", genre.name()).apply();
        spotify = new SpotifyPlayback(this, spotifyClientId(), this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(createContentView());

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        stepSensor = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        if (accelerometer == null) {
            statusText.setText(R.string.accelerometer_unavailable);
            sensorStatus.setText(R.string.accelerometer_unavailable);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
        spotifyTrackUri = null;
        boolean previouslyAuthorized = isSpotifyConfigured()
                && spotifyClientId().equals(preferences.getString("spotify_authorized_client_id", ""));
        spotify.onStart(previouslyAuthorized);
        updateConnectButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startMeasurement();
        if (stepSensor != null && !hasMotionPermission()
                && !preferences.getBoolean("motion_permission_requested", false)) {
            requestMotionPermission();
        }
    }

    private void startMeasurement() {
        handler.removeCallbacks(uiRefresh);
        cancelSearch();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        sensing = false;
        usingSystemSteps = false;
        detector.reset();
        systemCadence.reset();
        stability.reset();
        cadenceSmoother.reset();
        currentCadence = 0;
        smoothedCadence = 0;
        stableCadence = 0;
        sensorStartedMs = SystemClock.elapsedRealtime();
        rateWindowStartedMs = sensorStartedMs;
        lastSensorCallbackMs = -1;
        sensorEventsInWindow = 0;
        sensorRate = 0;
        if (accelerometer != null) {
            sensing = sensorManager.registerListener(this, accelerometer, SENSOR_PERIOD_US, 0, handler);
            if (!sensing) {
                Log.e(TAG, "Accelerometer listener registration failed.");
            }
        }
        int sourceLabel = stepSensor == null ? R.string.source_accelerometer_unavailable
                : R.string.source_accelerometer_permission;
        if (stepSensor != null && hasMotionPermission()) {
            try {
                usingSystemSteps = sensorManager.registerListener(
                        this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, 0, handler);
            } catch (SecurityException error) {
                Log.e(TAG, "Step sensor permission was rejected.", error);
            }
            sourceLabel = usingSystemSteps ? R.string.source_system_steps
                    : R.string.source_accelerometer_failed;
            if (!usingSystemSteps) {
                Log.e(TAG, "System step detector unavailable; using the accelerometer estimate.");
            }
        }
        sensing = sensing || usingSystemSteps;
        sensorSource.setText(sourceLabel);
        motionPermissionButton.setVisibility(
                stepSensor != null && !hasMotionPermission() ? View.VISIBLE : View.GONE);
        statusText.setText(sensing ? R.string.start_running : R.string.sensor_start_failed);
        if (sensing) {
            handler.post(uiRefresh);
        } else {
            sensorStatus.setText(R.string.sensor_start_failed);
        }
    }

    private boolean hasMotionPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMotionPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasMotionPermission()) {
            return;
        }
        if (preferences.getBoolean("motion_permission_requested", false)
                && !shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.enable_step_detector)
                    .setMessage(R.string.motion_permission_settings_hint)
                    .setPositiveButton(R.string.open_app_settings, (dialog, which) ->
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        preferences.edit().putBoolean("motion_permission_requested", true).apply();
        requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION},
                MOTION_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MOTION_PERMISSION_REQUEST) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.motion_permission_denied, Toast.LENGTH_LONG).show();
            }
            if (sensing) {
                startMeasurement();
            }
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
        spotify.onStop();
        playPending = false;
        pausePending = false;
        pauseAttemptedForStop = false;
        updateConnectButton();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        spotify.disconnect();
        searchExecutor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!sensing) {
            return;
        }
        if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR && usingSystemSteps) {
            systemCadence.recordStep(event.timestamp, SystemClock.elapsedRealtimeNanos());
            return;
        }
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }
        lastSensorCallbackMs = SystemClock.elapsedRealtime();
        sensorEventsInWindow++;
        detector.addSample(
                event.timestamp, event.values[0], event.values[1], event.values[2]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void renderCadence() {
        long now = SystemClock.elapsedRealtime();
        if (now - rateWindowStartedMs >= 1_000) {
            sensorRate = Math.round(sensorEventsInWindow * 1_000f / (now - rateWindowStartedMs));
            sensorEventsInWindow = 0;
            rateWindowStartedMs = now;
        }
        boolean freshSensor = lastSensorCallbackMs >= 0
                && now - lastSensorCallbackMs <= SENSOR_STALE_MS;
        long nowNs = SystemClock.elapsedRealtimeNanos();
        currentCadence = usingSystemSteps ? systemCadence.getStepsPerMinute(nowNs)
                : freshSensor ? detector.getStepsPerMinute(nowNs) : 0;
        int detectedSteps = usingSystemSteps ? systemCadence.getTotalSteps() : detector.getTotalSteps();
        smoothedCadence = cadenceSmoother.update(currentCadence, now);
        stableCadence = stability.update(smoothedCadence, now);
        cadenceText.setText(String.format(Locale.getDefault(), "%d", smoothedCadence));
        liveCadenceText.setText(getString(R.string.live_cadence, currentCadence));
        if (!usingSystemSteps && !freshSensor) {
            statusText.setText(now - sensorStartedMs <= SENSOR_STALE_MS
                    ? R.string.sensor_waiting : R.string.sensor_no_events);
        } else if (currentCadence == 0) {
            statusText.setText(detectedSteps > 0
                    ? R.string.acquiring_rhythm : R.string.start_running);
        } else {
            statusText.setText(stableCadence == 0 ? R.string.stabilizing : R.string.cadence_stable);
        }
        sensorStatus.setText(getString(R.string.sensor_diagnostics,
                freshSensor ? sensorRate : 0, detectedSteps, detector.getFilteredAcceleration()));

        pauseIfStopped();
        if (searching && currentCadence == 0) {
            cancelSearch();
        }
        if (stableCadence > 0 && !searching && !playPending && !pausePending) {
            refreshSuggestions();
            if ((!canKeepSelectedSong() || manualSearchRequested) && selectNextMatch()) {
                showCachedMatchStatus();
            }
            if ((!canKeepSelectedSong() || manualSearchRequested)
                    && searchGate.shouldSearch(genre, smoothedCadence, now)) {
                searchMusic(now);
            }
        }
        maybeAutoPlay();
        playButton.setEnabled(canKeepSelectedSong() && currentCadence > 0
                && !playPending && !pausePending);
        long delay = searchGate.remainingDelayMs(now);
        nextButton.setEnabled(stableCadence > 0 && !searching && !playPending && !pausePending);
        nextButton.setText(delay > 0 && suggestions.isEmpty()
                ? getString(R.string.retry_countdown, (delay + 999) / 1000)
                : getString(R.string.next_match));
    }

    private void searchMusic(long now) {
        searching = true;
        searchGate.started(genre, smoothedCadence, now);
        int version = ++requestVersion;
        int target = smoothedCadence;
        Genre requestedGenre = genre;
        boolean replaceExisting = manualSearchRequested;
        manualSearchRequested = false;
        musicStatus.setText(getString(R.string.searching_music, target));
        searchTask = searchExecutor.submit(() -> {
            try {
                ReccoBeatsClient.Result result = reccoBeats.findCandidates(requestedGenre, target);
                handler.post(() -> finishSearch(version, requestedGenre, result, replaceExisting));
            } catch (IOException error) {
                handler.post(() -> failSearch(version, error));
            }
        });
    }

    private boolean isCurrentRequest(int version) {
        return version == requestVersion && foreground && sensing;
    }

    private void finishSearch(int version, Genre requestedGenre,
            ReccoBeatsClient.Result result, boolean replaceExisting) {
        if (!isCurrentRequest(version)) {
            return;
        }
        searching = false;
        searchTask = null;
        if (genre != requestedGenre) {
            return;
        }
        cachedCandidates = result;
        suggestionsCadence = 0;
        suggestions.clear();
        if (result.unavailableTracks > 0) {
            Log.i(TAG, result.unavailableTracks + " recommendations lacked popularity, BPM or a Spotify link.");
        }
        if (stableCadence == 0) {
            manualSearchRequested = replaceExisting;
            musicStatus.setText(getString(R.string.cached_waiting_cadence, result.candidates.size()));
            return;
        }
        refreshSuggestions();
        if ((replaceExisting || !canKeepSelectedSong()) && !selectNextMatch()
                && !canKeepSelectedSong() && !isSelectedSongInUse()) {
            clearSong();
        }
        showCachedMatchStatus();
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
        manualSearchRequested = false;
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

    private void refreshSuggestions() {
        if (cachedCandidates == null || stableCadence == 0
                || suggestionsCadence == smoothedCadence) {
            return;
        }
        suggestions.clear();
        suggestions.addAll(cachedCandidates.matchesFor(smoothedCadence,
                selectedSong == null ? null : selectedSong.spotifyId));
        suggestionsCadence = smoothedCadence;
        showCachedMatchStatus();
    }

    private boolean canKeepSelectedSong() {
        if (selectedSong == null) {
            return false;
        }
        if (!isSelectedSongInUse()) {
            return ReccoBeatsClient.isEligible(selectedSong, smoothedCadence);
        }
        return selectedSong.popularity >= ReccoBeatsClient.MIN_POPULARITY
                && Math.abs(selectedSong.bpm - smoothedCadence) <= SONG_CHANGE_THRESHOLD;
    }

    private boolean isSelectedSongInUse() {
        return selectedSong != null && (selectedSong.spotifyId.equals(lastPlayedId)
                || (pausedForCadence && managedSong != null
                && selectedSong.spotifyId.equals(managedSong.spotifyId)));
    }

    private void showCachedMatchStatus() {
        if (cachedCandidates == null || stableCadence == 0) {
            return;
        }
        int matches = cachedCandidates.matchesFor(smoothedCadence, null).size();
        if (matches == 0) {
            musicStatus.setText(getString(R.string.no_matching_music, smoothedCadence,
                    cachedCandidates.candidates.size(), cachedCandidates.returnedTracks,
                    cachedCandidates.belowPopularityTracks, cachedCandidates.unavailableTracks));
        } else {
            musicStatus.setText(getString(R.string.matches_found, smoothedCadence, matches,
                    cachedCandidates.candidates.size(), cachedCandidates.returnedTracks,
                    cachedCandidates.belowPopularityTracks, cachedCandidates.unavailableTracks));
        }
    }

    private boolean selectNextMatch() {
        if (stableCadence == 0) {
            return false;
        }
        while (!suggestions.isEmpty()) {
            Song song = suggestions.removeFirst();
            if ((selectedSong != null && song.spotifyId.equals(selectedSong.spotifyId))
                    || !ReccoBeatsClient.isEligible(song, smoothedCadence)) {
                continue;
            }
            selectedSong = song;
            manualSearchRequested = false;
            lastAutoAttemptId = null;
            songText.setText(getString(R.string.song_details,
                    song.title, song.artist, song.bpm, song.popularity));
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
        if (foreground && sensing && stableCadence > 0 && canKeepSelectedSong()
                && spotify.isConnected()
                && !playPending && !pausePending
                && (!pausedForCadence || spotifyTrackUri != null)
                && (pausedForCadence || !selectedSong.spotifyId.equals(lastPlayedId))
                && !selectedSong.spotifyId.equals(lastAutoAttemptId)) {
            playSelectedSong();
        }
    }

    private void pauseIfStopped() {
        if (currentCadence > 0) {
            pauseAttemptedForStop = false;
            return;
        }
        boolean ownsCurrentPlayback = managedSong != null
                && (playPending || managedSong.spotifyUri().equals(spotifyTrackUri));
        if (!foreground || !spotify.isConnected() || !ownsCurrentPlayback
                || (pausedForCadence && !playPending) || pausePending || pauseAttemptedForStop) {
            return;
        }
        pauseAttemptedForStop = true;
        pausePending = true;
        playPending = false;
        spotifyStatus.setText(R.string.pausing_for_cadence);
        spotify.pause();
    }

    private void playSelectedSong() {
        if (selectedSong == null) {
            return;
        }
        if (currentCadence == 0 || !canKeepSelectedSong()) {
            manualPlayRequested = false;
            spotifyStatus.setText(R.string.playback_waiting_for_cadence);
            return;
        }
        if (playPending || pausePending) {
            return;
        }
        if (!spotify.isConnected()) {
            manualPlayRequested = true;
            connectSpotify(true);
            return;
        }
        manualPlayRequested = false;
        lastAutoAttemptId = selectedSong.spotifyId;
        managedSong = selectedSong;
        playPending = true;
        spotifyStatus.setText(R.string.starting_spotify_playback);
        if (pausedForCadence && selectedSong.spotifyUri().equals(spotifyTrackUri)) {
            spotify.resume(selectedSong);
        } else {
            spotify.play(selectedSong);
        }
    }

    private boolean isSpotifyConfigured() {
        return spotifyClientId().matches("[a-fA-F0-9]{32}");
    }

    private String spotifyClientId() {
        return preferences.getString("spotify_client_id", BuildConfig.SPOTIFY_CLIENT_ID).trim();
    }

    private void connectSpotify(boolean showAuthorization) {
        interactiveSpotifyConnection = showAuthorization;
        if (!isSpotifyConfigured()) {
            spotifyStatus.setText(R.string.spotify_setup_required);
            if (showAuthorization) {
                showSpotifySetup();
            }
            return;
        }
        if (!SpotifyAppRemote.isSpotifyInstalled(this)) {
            spotifyStatus.setText(R.string.spotify_not_installed);
            if (showAuthorization) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.spotify_not_installed_title)
                        .setMessage(R.string.spotify_not_installed)
                        .setPositiveButton(R.string.install_spotify, (dialog, which) ->
                                openExternalUrl("https://play.google.com/store/apps/details?id=com.spotify.music"))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
            return;
        }
        spotify.connect(showAuthorization);
        updateConnectButton();
    }

    private void updateConnectButton() {
        connectButton.setEnabled(!spotify.isConnecting() && !spotify.isConnected());
        connectButton.setText(spotify.isConnected() ? R.string.spotify_connected
                : spotify.isConnecting() ? R.string.connecting_spotify
                : isSpotifyConfigured() ? R.string.connect_spotify : R.string.spotify_setup);
    }

    @Override
    public void onSpotifyConnecting(boolean authorizing) {
        spotifyStatus.setText(authorizing ? R.string.authorizing_spotify : R.string.connecting_spotify);
        updateConnectButton();
    }

    @Override
    public void onSpotifyConnected() {
        pauseAttemptedForStop = false;
        preferences.edit().putString("spotify_authorized_client_id", spotifyClientId()).apply();
        updateConnectButton();
        spotifyStatus.setText(R.string.spotify_ready);
        if (manualPlayRequested && selectedSong != null) {
            playSelectedSong();
        } else {
            maybeAutoPlay();
        }
        pauseIfStopped();
    }

    @Override
    public void onSpotifyConnectionFailed(Throwable error) {
        playPending = false;
        pausePending = false;
        updateConnectButton();
        int messageId = R.string.spotify_connection_failed;
        if (error instanceof TimeoutException) {
            messageId = R.string.spotify_connection_timed_out;
        } else if (error instanceof AuthenticationFailedException) {
            messageId = R.string.spotify_authentication_rejected;
        } else if (error instanceof UserNotAuthorizedException) {
            messageId = R.string.spotify_authorization_needed;
            preferences.edit().remove("spotify_authorized_client_id").apply();
        } else if (error instanceof NotLoggedInException) {
            messageId = R.string.spotify_sign_in_needed;
        } else if (error instanceof OfflineModeException) {
            messageId = R.string.spotify_offline;
        }
        String message = getString(messageId, error.getClass().getSimpleName())
                + "\n\n" + spotify.getConnectionDetails();
        Log.e(TAG, "Spotify connection failed: " + error.getClass().getSimpleName()
                + "; " + spotify.getConnectionDetails());
        spotifyStatus.setText(message);
        if (foreground && interactiveSpotifyConnection) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.spotify_connection_problem)
                    .setMessage(message)
                    .setPositiveButton(R.string.retry_spotify, (dialog, which) -> connectSpotify(true))
                    .setNeutralButton(R.string.copy_error_details, (dialog, which) ->
                            copyDetails(getString(R.string.spotify_connection_problem), message))
                    .setNegativeButton(android.R.string.ok, null)
                    .show();
        }
    }

    @Override
    public void onPlaybackAccepted(Song song) {
        playPending = false;
        pausedForCadence = false;
        managedSong = song;
        spotifyTrackUri = song.spotifyUri();
        lastPlayedId = song.spotifyId;
        spotifyStatus.setText(getString(R.string.spotify_playback_accepted, song.title));
        pauseIfStopped();
    }

    @Override
    public void onPlaybackPaused() {
        pausePending = false;
        pausedForCadence = true;
        lastAutoAttemptId = null;
        spotifyStatus.setText(R.string.paused_for_cadence);
    }

    @Override
    public void onPlaybackPauseFailed(Throwable error) {
        pausePending = false;
        Log.e(TAG, "Spotify pause failed.", error);
        spotifyStatus.setText(getString(R.string.spotify_pause_failed, error.getClass().getSimpleName()));
        updateConnectButton();
    }

    @Override
    public void onPlaybackFailed(Throwable error) {
        playPending = false;
        Log.e(TAG, "Spotify playback failed.", error);
        spotifyStatus.setText(getString(R.string.spotify_playback_failed,
                error.getClass().getSimpleName()));
    }

    @Override
    public void onPlayerState(PlayerState state) {
        spotifyTrackUri = state.track == null ? null : state.track.uri;
        if (managedSong != null && !managedSong.spotifyUri().equals(spotifyTrackUri)
                && !playPending && !pausePending) {
            pausedForCadence = false;
        } else if (managedSong != null && state.track != null && !state.isPaused
                && pausedForCadence && !playPending && !pausePending) {
            pausedForCadence = false;
            pauseAttemptedForStop = false;
        }
        if (state.track != null) {
            spotifyStatus.setText(getString(state.isPaused
                    ? R.string.spotify_paused : R.string.spotify_playing, state.track.name));
        }
        pauseIfStopped();
    }

    private void openInSpotify() {
        if (selectedSong == null) {
            return;
        }
        openExternalUrl(selectedSong.spotifyUrl());
    }

    private void openExternalUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException error) {
            Log.e(TAG, "No application can open the Spotify link.", error);
            spotifyStatus.setText(R.string.spotify_cannot_open);
            Toast.makeText(this, R.string.spotify_cannot_open, Toast.LENGTH_LONG).show();
        }
    }

    private void showSpotifySetup() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(8));
        TextView instructions = createText(getString(R.string.spotify_setup_instructions), 15, Color.WHITE);
        instructions.setGravity(Gravity.START);
        content.addView(instructions);

        EditText clientId = new EditText(this);
        clientId.setHint(R.string.spotify_client_id_hint);
        clientId.setSingleLine(true);
        clientId.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        clientId.setText(spotifyClientId());
        content.addView(clientId);

        String details = getString(R.string.spotify_registration_details,
                getPackageName(), SpotifyPlayback.REDIRECT_URI, signingFingerprint());
        TextView registration = createText(details, 14, Color.LTGRAY);
        registration.setGravity(Gravity.START);
        registration.setTextIsSelectable(true);
        content.addView(registration);
        content.addView(createButton(R.string.copy_spotify_details,
                view -> copyDetails(getString(R.string.spotify_setup), details)));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.spotify_setup)
                .setView(scroll)
                .setPositiveButton(R.string.save_and_connect, null)
                .setNeutralButton(R.string.spotify_dashboard,
                        (ignored, which) -> openExternalUrl("https://developer.spotify.com/dashboard"))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String value = clientId.getText().toString().trim();
                    if (!value.matches("[a-fA-F0-9]{32}")) {
                        clientId.setError(getString(R.string.spotify_client_id_invalid));
                        return;
                    }
                    preferences.edit().putString("spotify_client_id", value).apply();
                    spotify.disconnect();
                    spotify = new SpotifyPlayback(this, value, this);
                    spotify.onStart(false);
                    updateConnectButton();
                    dialog.dismiss();
                    connectSpotify(true);
                }));
        dialog.show();
    }

    private void copyDetails(String label, String details) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) {
            Log.e(TAG, "Clipboard service unavailable.");
            Toast.makeText(this, R.string.clipboard_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, details));
        Toast.makeText(this, R.string.spotify_details_copied, Toast.LENGTH_SHORT).show();
    }

    private String signingFingerprint() {
        try {
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = getPackageManager().getPackageInfo(
                        getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
            } else {
                PackageInfo info = getPackageManager().getPackageInfo(
                        getPackageName(), PackageManager.GET_SIGNATURES);
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length == 0) {
                Log.e(TAG, "Installed APK signing certificate is missing.");
                return getString(R.string.spotify_fingerprint_unavailable);
            }
            byte[] fingerprint = MessageDigest.getInstance("SHA-1").digest(signatures[0].toByteArray());
            StringBuilder result = new StringBuilder();
            for (byte value : fingerprint) {
                if (result.length() > 0) {
                    result.append(':');
                }
                result.append(String.format(Locale.ROOT, "%02X", value & 0xff));
            }
            return result.toString();
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException error) {
            Log.e(TAG, "Unable to read APK signing fingerprint.", error);
            return getString(R.string.spotify_fingerprint_unavailable);
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
        liveCadenceText = createText(getString(R.string.live_cadence, 0), 16, Color.LTGRAY);
        TextView unit = createText(getString(R.string.steps_per_minute), 20, Color.LTGRAY);
        statusText = createText(getString(R.string.start_running), 16, Color.rgb(96, 205, 255));

        layout.addView(title);
        layout.addView(cadenceText);
        layout.addView(unit);
        layout.addView(liveCadenceText);
        layout.addView(statusText);
        layout.addView(createText(getString(R.string.song_change_hint), 13, Color.LTGRAY));
        sensorSource = createText("", 14, Color.rgb(96, 205, 255));
        layout.addView(sensorSource);
        sensorStatus = createText(getString(R.string.sensor_waiting), 13, Color.LTGRAY);
        layout.addView(sensorStatus);
        layout.addView(createText(getString(R.string.measurement_hint), 13, Color.LTGRAY));
        motionPermissionButton = createButton(R.string.enable_step_detector,
                view -> requestMotionPermission());
        motionPermissionButton.setVisibility(View.GONE);
        layout.addView(motionPermissionButton);
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
                    preferences.edit().putString("running_genre", selected.name()).apply();
                    cancelSearch();
                    cachedCandidates = null;
                    suggestionsCadence = 0;
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
        connectButton = createButton(isSpotifyConfigured()
                ? R.string.connect_spotify : R.string.spotify_setup, view -> connectSpotify(true));
        layout.addView(connectButton);
        layout.addView(createButton(R.string.spotify_settings, view -> showSpotifySetup()));
        spotifyStatus = createText(getString(isSpotifyConfigured()
                ? R.string.spotify_connect_hint : R.string.spotify_setup_required), 14, Color.LTGRAY);
        layout.addView(spotifyStatus);
        playButton = createButton(R.string.play_match, view -> playSelectedSong());
        playButton.setEnabled(false);
        layout.addView(playButton);
        nextButton = createButton(R.string.next_match, view -> {
            refreshSuggestions();
            if (!selectNextMatch()) {
                manualSearchRequested = true;
                searchGate.requestAnother();
                musicStatus.setText(R.string.waiting_for_search);
            } else {
                showCachedMatchStatus();
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
