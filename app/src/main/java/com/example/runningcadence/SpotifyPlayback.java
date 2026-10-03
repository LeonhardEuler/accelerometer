package com.example.runningcadence;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.spotify.android.appremote.api.ConnectionParams;
import com.spotify.android.appremote.api.Connector;
import com.spotify.android.appremote.api.SpotifyAppRemote;
import com.spotify.protocol.client.Subscription;
import com.spotify.protocol.types.PlayerState;

import java.util.concurrent.TimeoutException;

public final class SpotifyPlayback {
    public static final String REDIRECT_URI = "runningcadence://spotify-callback";
    private static final String TAG = "SpotifyConnection";
    private static final long CONNECT_TIMEOUT_MS = 20_000;
    private static final long AUTHORIZE_TIMEOUT_MS = 60_000;

    public interface Listener {
        void onSpotifyConnecting(boolean authorizing);
        void onSpotifyConnected();
        void onSpotifyConnectionFailed(Throwable error);
        void onPlaybackAccepted(Song song);
        void onPlaybackPaused();
        void onPlaybackPauseFailed(Throwable error);
        void onPlaybackFailed(Throwable error);
        void onPlayerState(PlayerState state);
    }

    private final Activity activity;
    private final String clientId;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable connectionTimeout;
    private SpotifyServiceContext serviceContext;
    private SpotifyAppRemote remote;
    private Subscription<PlayerState> subscription;
    private boolean connecting;
    private boolean foreground;
    private boolean authorizing;
    private boolean reconnectAfterAuthorization;
    private Throwable pendingFailure;
    private long connectionStartedMs;
    private String connectionDetails = "";
    private int connectionVersion;
    private int playVersion;

    public SpotifyPlayback(Activity activity, String clientId, Listener listener) {
        this.activity = activity;
        this.clientId = clientId;
        this.listener = listener;
    }

    public boolean isConnected() {
        return remote != null && remote.isConnected();
    }

    public boolean isConnecting() {
        return connecting;
    }

    public String getConnectionDetails() {
        return connectionDetails;
    }

    public void onStart(boolean reconnectPreviouslyAuthorized) {
        foreground = true;
        if (pendingFailure != null) {
            Throwable error = pendingFailure;
            pendingFailure = null;
            listener.onSpotifyConnectionFailed(error);
            return;
        }
        if (reconnectAfterAuthorization || reconnectPreviouslyAuthorized) {
            reconnectAfterAuthorization = false;
            connect(false);
        }
    }

    public void onStop() {
        foreground = false;
        // Spotify's consent activity hides ours. Let that one bounded request finish.
        if (connecting && authorizing) {
            return;
        }
        disconnect();
    }

    public void connect(boolean showAuthorization) {
        if (!foreground || connecting || isConnected()) {
            return;
        }
        int version = ++connectionVersion;
        connecting = true;
        authorizing = showAuthorization;
        pendingFailure = null;
        connectionStartedMs = SystemClock.elapsedRealtime();
        serviceContext = new SpotifyServiceContext(activity, showAuthorization);
        long timeoutMs = showAuthorization ? AUTHORIZE_TIMEOUT_MS : CONNECT_TIMEOUT_MS;
        Log.i(TAG, "Starting connection: Android API " + Build.VERSION.SDK_INT
                + ", interactive=" + showAuthorization);
        listener.onSpotifyConnecting(showAuthorization);
        connectionTimeout = () -> {
            if (version == connectionVersion && connecting) {
                failConnection(new TimeoutException(
                        "No Spotify connection result within " + timeoutMs / 1_000 + " seconds."));
            }
        };
        handler.postDelayed(connectionTimeout, timeoutMs);
        ConnectionParams params = new ConnectionParams.Builder(clientId)
                .setRedirectUri(REDIRECT_URI)
                .showAuthView(showAuthorization)
                .build();
        Connector.ConnectionListener callback = new Connector.ConnectionListener() {
            @Override
            public void onConnected(SpotifyAppRemote connectedRemote) {
                if (version != connectionVersion) {
                    SpotifyAppRemote.disconnect(connectedRemote);
                    return;
                }
                clearConnectionTimeout();
                connecting = false;
                remote = connectedRemote;
                captureConnectionDetails("connected");
                Log.i(TAG, connectionDetails);
                if (!foreground) {
                    disconnect();
                    reconnectAfterAuthorization = true;
                    return;
                }
                subscription = remote.getPlayerApi().subscribeToPlayerState();
                subscription.setEventCallback(state -> {
                    if (version == connectionVersion) {
                        listener.onPlayerState(state);
                    }
                }).setErrorCallback(error -> {
                    if (version == connectionVersion) {
                        listener.onPlaybackFailed(error);
                    }
                });
                listener.onSpotifyConnected();
            }

            @Override
            public void onFailure(Throwable error) {
                if (version == connectionVersion) {
                    failConnection(error);
                }
            }
        };
        try {
            SpotifyAppRemote.connect(serviceContext, params, callback);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException error) {
            failConnection(error);
        }
    }

    public void play(Song song) {
        startPlayback(song, false);
    }

    public void resume(Song song) {
        startPlayback(song, true);
    }

    private void startPlayback(Song song, boolean resume) {
        if (!isConnected()) {
            listener.onPlaybackFailed(new IllegalStateException("Spotify is not connected."));
            return;
        }
        int version = ++playVersion;
        (resume ? remote.getPlayerApi().resume() : remote.getPlayerApi().play(song.spotifyUri()))
                .setResultCallback(result -> {
                    if (version == playVersion) {
                        listener.onPlaybackAccepted(song);
                    }
                })
                .setErrorCallback(error -> {
                    if (version == playVersion) {
                        listener.onPlaybackFailed(error);
                    }
                });
    }

    public void pause() {
        if (!isConnected()) {
            listener.onPlaybackPauseFailed(new IllegalStateException("Spotify is not connected."));
            return;
        }
        int version = ++playVersion;
        remote.getPlayerApi().pause()
                .setResultCallback(result -> {
                    if (version == playVersion) {
                        listener.onPlaybackPaused();
                    }
                })
                .setErrorCallback(error -> {
                    if (version == playVersion) {
                        listener.onPlaybackPauseFailed(error);
                    }
                });
    }

    public void disconnect() {
        clearConnectionTimeout();
        connectionVersion++;
        playVersion++;
        connecting = false;
        authorizing = false;
        reconnectAfterAuthorization = false;
        pendingFailure = null;
        if (subscription != null) {
            subscription.cancel();
            subscription = null;
        }
        if (remote != null) {
            SpotifyAppRemote.disconnect(remote);
            remote = null;
        }
        if (serviceContext != null) {
            serviceContext.close();
            serviceContext = null;
        }
    }

    private void failConnection(Throwable error) {
        captureConnectionDetails(error instanceof TimeoutException ? "timed out" : "SDK rejected connection");
        Log.w(TAG, connectionDetails + "; " + error.getClass().getSimpleName());
        disconnect();
        if (foreground) {
            listener.onSpotifyConnectionFailed(error);
        } else {
            pendingFailure = error;
        }
    }

    private void captureConnectionDetails(String outcome) {
        connectionDetails = "Running Cadence " + BuildConfig.VERSION_NAME
                + " (build " + BuildConfig.VERSION_CODE + ")"
                + "\nAndroid " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\nApp Remote SDK: 0.8.0"
                + "\nRequest: " + (authorizing ? "interactive authorization" : "silent reconnect")
                + "\nService binding accepted: " + (serviceContext != null && serviceContext.wasServiceBound())
                + "\nElapsed: " + (SystemClock.elapsedRealtime() - connectionStartedMs) / 1_000 + " s"
                + "\nResult: " + outcome;
    }

    private void clearConnectionTimeout() {
        if (connectionTimeout != null) {
            handler.removeCallbacks(connectionTimeout);
            connectionTimeout = null;
        }
    }
}
