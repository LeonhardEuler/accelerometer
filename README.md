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
- The large number is a **30-second rolling average** of cadence readings; live
  cadence is shown separately. During warm-up, the average uses the readings
  collected so far. A live reading of zero immediately returns zero and clears
  the averaging window, so stopping is not delayed by the 30-second smoothing.
  Pausing measurement also starts a fresh window on return.
- The accelerometer-only path remains an estimate: loose placement, arm motion
  and shaking can introduce errors. Screen taps are not a valid running simulation.

## Music matching

- The dropdown offers **Dance / EDM, Uptempo / Hardcore, Drum & Bass, House,
  Techno, Trance, Hardstyle, Upbeat Pop, and Pop Punk / Rock**.
  Dance / EDM is the default. The selection is saved by style name, not position;
  upgrading from the old broad-genre list selects Dance / EDM once without
  changing Spotify settings.
- Matching starts after five seconds of smoothed cadence readings between 100
  and 240 SPM with a total spread of at most 6 SPM.
- ReccoBeats receives a curated genre seed and the target tempo. Its recommendation
  endpoint has **no genre filter**: genre matching is approximate, not guaranteed.
  Seed similarity may be weak when the tempo target differs substantially.
- The app fetches up to **100 recommendations**, then looks up their audio
  features in batches of at most **40 IDs**, joining by ReccoBeats track ID.
  ReccoBeats' tempo parameter is a recommendation preference, not a hard filter.
- Recommendations also request **popularity=80** as a preference. Independently,
  the app enforces **popularity >=60/100** before looking up tempo: lower scores
  and tracks with unknown popularity never enter the playable cache. Each song's
  score and the number excluded by this filter are shown on screen.
- All verified popular candidates are retained in memory for the current genre,
  even if they do not match the original request's cadence. New songs must be
  within **2 BPM of the 30-second smoothed cadence**. The closest eligible song
  is selected first.
  Half-time/double-time or out-of-range matches are not silently accepted.
- Once a song has started, it is retained until its BPM differs from the smoothed
  cadence by **more than 5 BPM**. At exactly 5 BPM it is still kept. This prevents
  constant song switching around the tighter search threshold. Explicit genre
  changes and **Skip to next song** still select a new song on request.
- Changing cadence re-filters the cache without a network request. Results
  arriving during a cadence change are retained rather than discarded. A genre
  change clears the cache. This improves coverage but cannot guarantee a match
  at every BPM.
- A new search is considered when the current song and cached alternatives do
  cannot satisfy the retention/search thresholds at a new stable smoothed cadence,
  when the genre changes, or when explicitly
  requested with **Skip to next song** after cached alternatives are exhausted.
  Searches retain a 30-second cooldown and honor server `Retry-After` delays.
  An unchanged cadence does not repeatedly retry a failed or empty lookup.
- Missing popularity/BPM, missing Spotify links, no matches, network errors and rate limits
  are surfaced on screen. Errors do not start an automatic retry loop.
- Sensor collection and matching run only while this screen is in the foreground.
  Leaving it cancels pending searches and disconnects App Remote; Spotify handles
  its own background playback. This is not a background running tracker or an
  automatically maintained playlist.

## Automatic playback and stopping

After Spotify has been connected and authorized, a matching song starts
automatically at a steady smoothed cadence. When the live measured cadence becomes **0**,
the app immediately requests a Spotify **pause** for the music it started.
It does not pause unrelated Spotify music that was never controlled by this app.
The sensor's idle timeout still determines when cadence reaches 0.

At a steady pace within the retention threshold, playback resumes automatically. If the same song is
still selected and paused in Spotify, it resumes from its current position;
otherwise the new matched song starts. At a different pace, cached candidates
are considered before another network lookup.

An automatic tempo-driven track change plays up to **600 ms of the phone's
default notification sound**, once Spotify reports the new track as playing.
Initial playback, manual track/genre choices, resumes, failed requests and
cancelled transitions do not chime. The sound respects notification volume,
silent/vibrate mode and Do Not Disturb, and stops when the app loses focus or
live cadence reaches zero. It does not change the phone's volume.

**Skip to next song** selects and plays a different matching track from the
cached recommendations. If none remain, it requests more recommendations subject
to the existing search cooldown. It keeps the popularity and BPM requirements,
does not jump into Spotify's unrelated queue, and reports when no alternative
was found rather than restarting the current song. Manual skips do not chime.
The control is disabled while stopped, while cadence is unsettled or while a
search/playback command is in progress.

Playback changes are **immediate**, as selected for this POC: no fade effects
and no changes to the phone's media volume. Zero-cadence stopping operates while
this screen is active; measurement is suspended when it is hidden. Returning
requires acquiring a new step rhythm before automatic playback resumes.

ReccoBeats requires no API key. Only the genre seed, target cadence, popularity
preference and candidate track IDs are sent; raw accelerometer samples stay on the device. Spotify playback
requires Spotify authorization separately.

### Running-style seeds

These Spotify track IDs and their audio features were verified against ReccoBeats
on October 3, 2026.
`Genre.java` contains the IDs; `R.array.genres` uses the same enum order.

| Style | Seed |
| --- | --- |
| Dance / EDM | Avicii - Levels |
| Uptempo / Hardcore | Dimitri K - Early Uptempo Mash |
| Drum & Bass | Wilkinson - Afterglow |
| House | FISHER - Losing It |
| Techno | Adam Beyer, Bart Skils - Your Mind |
| Trance | Armin van Buuren - Blah Blah Blah |
| Hardstyle | Brennan Heart, Wildstylez - Lose My Mind |
| Upbeat Pop | The Weeknd - Blinding Lights |
| Pop Punk / Rock | Paramore - Misery Business |

Seeds are style references, not automatically playable recommendations. The
popularity >=60 rule still applies to every matched song, even when a seed
itself has a lower score.

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
failures produce actionable dialogs. Interactive authorization has a 60-second
deadline; reconnecting an already authorized app has a 20-second deadline.

The bundled App Remote 0.8.0 SDK binds Spotify using flags that predate Android
14's background activity launch rules. `SpotifyServiceContext` adds
`BIND_ALLOW_ACTIVITY_STARTS` on Android 14+ **only for an explicit authorization
request to Spotify's protocol service**. It preserves the scoped context when the
SDK asks for the application context. This lets Spotify open its approval screen
on Android 14-16 without lowering the target SDK or requesting overlay permissions.

A pending authorization request survives the temporary switch to Spotify's
approval screen. A completed connection is still disconnected while this app is
hidden, and reconnects when you return. Timed-out, cancelled and destroyed
connections release their service bindings, including requests that never
returned an App Remote instance.

Silent reconnection is enabled only after a connection has actually succeeded
for the configured Client ID. Merely tapping Connect does not enable it.
Connection errors distinguish timeouts from explicit registration/authorization
rejections. **Copy details** provides the Android version, connection mode,
whether service binding was accepted, elapsed time and error type; it does not
include credentials or tokens.

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
- Android 14 activity launch changes: https://developer.android.com/about/versions/14/behavior-changes-14
- Spotify SDK Android 14 connection report: https://github.com/spotify/android-sdk/issues/361

ReccoBeats supplies its service as-is without availability or accuracy guarantees.
Review its terms and Spotify's platform terms before distributing an integration.
