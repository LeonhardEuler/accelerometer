package com.example.runningcadence;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

final class SpotifyServiceContext extends ContextWrapper {
    private static final String TAG = "SpotifyConnection";
    private final Set<ServiceConnection> bindings = new HashSet<>();
    private final boolean allowAuthorization;
    private boolean closed;
    private boolean serviceWasBound;

    SpotifyServiceContext(Context context, boolean allowAuthorization) {
        super(context.getApplicationContext());
        this.allowAuthorization = allowAuthorization;
    }

    @Override
    public Context getApplicationContext() {
        // App Remote 0.8.0 calls getApplicationContext() again before binding and unbinding.
        return this;
    }

    @Override
    public synchronized boolean bindService(Intent service, ServiceConnection connection, int flags) {
        ensureOpen();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && allowAuthorization && isSpotifyProtocol(service)) {
            flags |= Context.BIND_ALLOW_ACTIVITY_STARTS;
        }
        bindings.add(connection);
        boolean bound = super.bindService(service, connection, flags);
        if (bound) {
            serviceWasBound = true;
        }
        Log.i(TAG, "Spotify service bind accepted=" + bound
                + ", interactive=" + allowAuthorization + ", flags=" + flags);
        return bound;
    }

    @Override
    public synchronized void unbindService(ServiceConnection connection) {
        if (bindings.remove(connection) || !closed) {
            super.unbindService(connection);
        }
    }

    @Override
    public synchronized ComponentName startService(Intent service) {
        ensureOpen();
        return super.startService(service);
    }

    @Override
    @TargetApi(Build.VERSION_CODES.O)
    public synchronized ComponentName startForegroundService(Intent service) {
        ensureOpen();
        return super.startForegroundService(service);
    }

    synchronized boolean wasServiceBound() {
        return serviceWasBound;
    }

    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (ServiceConnection connection : bindings) {
            try {
                super.unbindService(connection);
            } catch (IllegalArgumentException error) {
                Log.w(TAG, "Spotify service binding was already released.", error);
            }
        }
        bindings.clear();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Spotify connection has been cancelled.");
        }
    }

    private static boolean isSpotifyProtocol(Intent intent) {
        String action = intent.getAction();
        String packageName = intent.getPackage();
        boolean knownPackage = "com.spotify.music".equals(packageName)
                || "com.spotify.music.debug".equals(packageName)
                || "com.spotify.music.canary".equals(packageName)
                || "com.spotify.music.partners".equals(packageName);
        return knownPackage && ("com.spotify.mobile.appprotocol.action.BIND_PROTOCOL_SERVICE".equals(action)
                || "com.spotify.mobile.appprotocol.action.START_APP_PROTOCOL_SERVICE".equals(action));
    }
}
