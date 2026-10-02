package com.example.runningcadence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class HttpJsonTransport implements ReccoBeatsClient.Transport {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private volatile HttpURLConnection activeConnection;

    @Override
    public String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        activeConnection = connection;
        try {
            checkCancelled();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(12_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "RunningCadence/1.0");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new ApiException(status, retryDelayMs(connection));
            }
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkCancelled();
                    if (output.size() + count > MAX_RESPONSE_BYTES) {
                        throw new IOException("ReccoBeats response is too large.");
                    }
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
            activeConnection = null;
        }
    }

    public void cancel() {
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            connection.disconnect();
        }
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Music search cancelled.");
        }
    }

    private static long retryDelayMs(HttpURLConnection connection) {
        String header = connection.getHeaderField("Retry-After");
        if (header != null && header.trim().matches("[0-9]{1,9}")) {
            return Math.max(1_000, Long.parseLong(header.trim()) * 1_000);
        }
        long date = connection.getHeaderFieldDate("Retry-After", -1);
        return date > 0 ? Math.max(1_000, date - System.currentTimeMillis()) : 60_000;
    }

    public static final class ApiException extends IOException {
        public final int status;
        public final long retryDelayMs;

        ApiException(int status, long retryDelayMs) {
            super("ReccoBeats returned HTTP " + status + ".");
            this.status = status;
            this.retryDelayMs = retryDelayMs;
        }
    }
}
