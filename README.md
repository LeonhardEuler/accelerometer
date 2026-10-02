# Running Cadence

A Java-only Android app that measures running cadence, finds tempo-matched songs
using ReccoBeats, and controls playback in the installed Spotify app. It prefers
Android's step detector and falls back to an accelerometer estimate. There is no
Kotlin or server component.

## Open and run

1. Open this directory in Android Studio.
2. Let Android Studio install Android SDK 35 and sync Gradle.
3. Run the `app` configuration on a physical Android phone.
4. Allow **Physical activity** when prompted so the phone's step detector can be
   used. The screen identifies the active measurement source.
5. Choose a genre. For automatic playback, configure and connect Spotify as
   described below before running.
6. Hold the phone securely or place it in a snug pocket and start running.

## Cadence measurement

- On Android 10 and newer, the system step detector requires the runtime
  `ACTIVITY_RECOGNITION` (Physical activity) permission. No location, microphone,
  contacts, or background tracking permission is requested.
- If the detector is unavailable, permission is declined, or it cannot start,
  the app explicitly identifies the accelerometer fallback. **Enable phone step
  detector** lets you grant permission later, including through app settings if
  Android will no longer display the permission prompt.
- The fallback removes gravity from the acceleration magnitude *without
  rectifying the resulting signal*, and uses time-based smoothing and positive
  peaks. This fixes the old detector sticking after its first impact during
  continuous motion.
- Both sources require at least five detected steps before displaying cadence.
  The estimate averages consistent intervals among the last nine steps, rejecting
  outliers around the median. One missed impact should not halve the estimate.
  Cadence becomes zero two seconds after the last received step; Android's step
  detection latency may delay that transition slightly.
- Live diagnostics show accelerometer sampling rate, motion and the selected
  source's step count. Refreshing does not require tapping a control.
- The accelerometer-only path remains an estimate: loose placement, arm motion
  and shaking can introduce errors. Screen taps are not a valid running simulation.

## Music matching

- The dropdown offers Pop, Rock, Hip-hop, Electronic / EDM, R&B, Country, Metal,
  and Indie. The selection is saved on the device.
- Matching starts after five seconds of cadence readings between 100 and 240
  SPM with a total spread of at most 6 SPM.
- ReccoBeats receives a curated genre seed and the target tempo. Its recommendation
  endpoint has **no genre filter**: genre matching is approximate, not guaranteed.
  Seed similarity may be weak when the tempo target differs substantially.
- The app fetches up to 40 recommendations, then separately looks up their audio
  features and joins them by ReccoBeats track ID. Only verified tempos within
  **1 BPM of the cadence** are eligible; the closest is selected first.
  It does not silently accept half-time/double-time or out-of-range matches.
- Recommendations have a minimum 30-second search cooldown. A new search is
  triggered by a genre change, a sustained cadence change of at least 10 SPM,
  or **Find another match**. That button first uses remaining cached matches.
  It does not bypass cooldowns or server `Retry-After` delays.
- Missing BPM, missing Spotify links, no matches, network errors and rate limits
  are surfaced on screen. Errors do not start an automatic retry loop.
- Sensor collection and matching run only while this screen is in the foreground.
  Leaving it cancels pending searches and disconnects App Remote; Spotify handles
  its own background playback. Returning to the same activity does not restart an
  already requested song.
  This is not a background running tracker or an automatically maintained playlist.

ReccoBeats requires no API key. Only the genre seed, target cadence and candidate
track IDs are sent; raw accelerometer samples stay on the device. Spotify playback
requires Spotify authorization separately.

### Genre seeds

These Spotify track IDs were resolved through ReccoBeats on October 2, 2026.
`Genre.java` contains the IDs; `R.array.genres` uses the same enum order.

| Genre | Seed |
| --- | --- |
| Pop | The Weeknd - Blinding Lights |
| Rock | Foo Fighters - Everlong |
| Hip-hop | Eminem - Lose Yourself |
| Electronic / EDM | Avicii - Levels |
| R&B | SZA - Kill Bill |
| Country | Luke Combs - When It Rains It Pours |
| Metal | Metallica - Master Of Puppets |
| Indie | Arctic Monkeys - Do I Wanna Know? |

## Enable automatic Spotify playback

The app uses Spotify App Remote, **not an embedded audio player**. ReccoBeats
provides metadata, not licensed audio streams. Spotify must be installed, signed
in, and authorized; track/account/region restrictions still apply.

1. Register an Android integration in the Spotify Developer Dashboard.
2. Register this exact redirect URI: `runningcadence://spotify-callback`.
3. Open **Set up Spotify** or **Spotify settings** in the app. It displays the
   installed APK's actual signing SHA-1, package and redirect URI, with a
   **Copy registration details** button. Register those details in the dashboard.
   Debug and release certificates differ.
4. Paste the developer app's **Client ID** into the setup dialog and tap
   **Save and connect**. It is saved on the device; no rebuild is required.
5. Approve Spotify access when prompted.
   If consent temporarily opens Spotify, return to Running Cadence.
6. Start running. A verified match is played automatically through Spotify when
   cadence is stable. **Play matched song** retries playback explicitly.

If no client ID is configured, the connection button opens setup instead of
silently repeating a status message. Missing Spotify installations and connection
failures produce actionable dialogs, and a stalled connection times out after
20 seconds rather than leaving the button disabled indefinitely.

For development, a build-time default can still be set with
`spotifyClientId=YOUR_32_CHARACTER_CLIENT_ID` in personal
`~/.gradle/gradle.properties`, or
`./gradlew :app:assembleDebug -PspotifyClientId=YOUR_32_CHARACTER_CLIENT_ID`.
An ID saved in the app takes precedence.

Do not put a Spotify client secret in this Android app. The client ID is a public
application identifier. If the developer app is in development mode, authorize
your account in the dashboard and satisfy Spotify's current developer-account
requirements.

Without a client ID, cadence measurement and ReccoBeats matching still work.
**Open song in Spotify** opens the track's HTTPS link in Spotify or a browser;
it does not guarantee automatic playback. Playback failures are displayed rather
than reported as successful.

The pinned Spotify App Remote 0.8.0 AAR is in `app/libs/`, together with its Apache
2.0 license. It was downloaded from Spotify's official Android SDK release.
Gson is shared by the SDK and the ReccoBeats JSON parser.

## Build

Requires JDK 17 and Android SDK 35. Set the SDK location through Android Studio,
`ANDROID_HOME`, or an untracked `local.properties`.

```sh
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

End-to-end playback and real accelerometer accuracy need a physical phone and
configured Spotify app.

Install the rebuilt APK on the phone to pick up changes; editing this project
does not update an already installed copy.

## API documentation

- ReccoBeats recommendations: https://reccobeats.com/docs/apis/get-recommendation
- ReccoBeats audio features: https://reccobeats.com/docs/apis/get-audio-features
- ReccoBeats terms: https://reccobeats.com/docs/documentation/terms-of-service
- Spotify App Remote setup: https://developer.spotify.com/documentation/android/tutorials/getting-started
- Spotify Android SDK release: https://github.com/spotify/android-sdk/releases/tag/v0.8.0-appremote_v2.1.0-auth
- Android motion sensors: https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion

ReccoBeats supplies its service as-is without availability or accuracy guarantees.
Review its terms and Spotify's platform terms before distributing an integration.
