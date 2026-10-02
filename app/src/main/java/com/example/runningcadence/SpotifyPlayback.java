package com.example.runningcadence;

import android.app.Activity;

import com.spotify.android.appremote.api.ConnectionParams;
import com.spotify.android.appremote.api.Connector;
import com.spotify.android.appremote.api.SpotifyAppRemote;
import com.spotify.protocol.client.Subscription;
import com.spotify.protocol.types.PlayerState;

public final class SpotifyPlayback {
    public static final String REDIRECT_URI = "runningcadence://spotify-callback";

    public interface Listener {
        void onSpotifyConnected();
        void onSpotifyConnectionFailed(Throwable error);
        void onPlaybackAccepted(Song song);
        void onPlaybackFailed(Throwable error);
        void onPlayerState(PlayerState state);
    }

    private final Activity activity;
    private final String clientId;
    private final Listener listener;
    private SpotifyAppRemote remote;
    private Subscription<PlayerState> subscription;
    private boolean connecting;
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

    public void connect(boolean showAuthorization) {
        if (connecting || isConnected()) {
            return;
        }
        int version = ++connectionVersion;
        connecting = true;
        ConnectionParams params = new ConnectionParams.Builder(clientId)
                .setRedirectUri(REDIRECT_URI)
                .showAuthView(showAuthorization)
                .build();
        SpotifyAppRemote.connect(activity, params, new Connector.ConnectionListener() {
            @Override
            public void onConnected(SpotifyAppRemote connectedRemote) {
                if (version != connectionVersion) {
                    SpotifyAppRemote.disconnect(connectedRemote);
                    return;
                }
                connecting = false;
                remote = connectedRemote;
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
                    connecting = false;
                    listener.onSpotifyConnectionFailed(error);
                }
            }
        });
    }

    public void play(Song song) {
        if (!isConnected()) {
            listener.onPlaybackFailed(new IllegalStateException("Spotify is not connected."));
            return;
        }
        int version = ++playVersion;
        remote.getPlayerApi().play(song.spotifyUri())
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

    public void disconnect() {
        connectionVersion++;
        playVersion++;
        connecting = false;
        if (subscription != null) {
            subscription.cancel();
            subscription = null;
        }
        if (remote != null) {
            SpotifyAppRemote.disconnect(remote);
            remote = null;
        }
    }
}
