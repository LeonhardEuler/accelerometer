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

public final class MainActivity extends Activity
        implements SensorEventListener, SpotifyPlayback.Listener {
    private static final String TAG = "RunningCadence";
    private static final long UI_REFRESH_MS = 250;
    private static final int SENSOR_PERIOD_US = 20_000;
    private static final long SENSOR_STALE_MS = 1_500;
    private static final int MOTION_PERMISSION_REQUEST = 100;
    private final StepCadenceDetector detector = new StepCadenceDetector();
    private final StepCadenceTracker systemCadence = new StepCadenceTracker();
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
    private Sensor stepSensor;
    private TextView cadenceText;
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
    private String lastPlayedId;
    private String lastAutoAttemptId;
    private Future<?> searchTask;
    private int requestVersion;
    private int requestedCadence;
    private int currentCadence;
    private int stableCadence;
    private boolean foreground;
    private boolean sensing;
    private boolean usingSystemSteps;
    private boolean searching;
    private boolean manualPlayRequested;
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
        int savedGenre = preferences.getInt("genre", 0);
        if (savedGenre < 0 || savedGenre >= Genre.values().length) {
            Log.w(TAG, "Saved genre is invalid; selecting Pop.");
            savedGenre = 0;
        }
        genre = Genre.values()[savedGenre];
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
        if (preferences.getBoolean("spotify_enabled", false) && isSpotifyConfigured()) {
            connectSpotify(false);
        }
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
        currentCadence = 0;
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
        stableCadence = stability.update(currentCadence, now);
        cadenceText.setText(String.format(Locale.getDefault(), "%d", currentCadence));
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
        preferences.edit().putBoolean("spotify_enabled", true).apply();
        spotifyStatus.setText(R.string.connecting_spotify);
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
        if (foreground && interactiveSpotifyConnection) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.spotify_connection_problem)
                    .setMessage(getString(R.string.spotify_connection_failed,
                            error.getClass().getSimpleName()))
                    .setPositiveButton(R.string.spotify_setup, (dialog, which) -> showSpotifySetup())
                    .setNegativeButton(android.R.string.ok, null)
                    .show();
        }
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
        content.addView(createButton(R.string.copy_spotify_details, view -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard == null) {
                Log.e(TAG, "Clipboard service unavailable.");
                Toast.makeText(this, R.string.clipboard_unavailable, Toast.LENGTH_LONG).show();
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.spotify_setup), details));
            Toast.makeText(this, R.string.spotify_details_copied, Toast.LENGTH_SHORT).show();
        }));
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
                    updateConnectButton();
                    dialog.dismiss();
                    connectSpotify(true);
                }));
        dialog.show();
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
        TextView unit = createText(getString(R.string.steps_per_minute), 20, Color.LTGRAY);
        statusText = createText(getString(R.string.start_running), 16, Color.rgb(96, 205, 255));

        layout.addView(title);
        layout.addView(cadenceText);
        layout.addView(unit);
        layout.addView(statusText);
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
