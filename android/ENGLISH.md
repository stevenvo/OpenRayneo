# Local English edition

Based on OpenRayneo commit 7f2fc0dbb4414fa66bdc5a1be23d44b7d4b4ec31.
This is an unofficial English Android fork for RayNeo iO.
Source: https://github.com/stevenvo/OpenRayneo/tree/english-android

- Name: OpenRayneo English
- Package: com.openrayneo.android.english
- Version: 1.0.0-en4 (23)
- Android: 13 or later
- Release build: not debuggable, signed with this lab's development certificate.

The setup guide, tabs, settings, assistant, permission guidance, diagnostics,
connection errors and test notification are translated into English. The phone
assistant defaults to English while preserving its tool-use limitations.

## Glasses voice (experimental)

In Assistant, select Phone model or connect Computer Codex, then tap **Start
glasses voice**. Speak a short question and pause for about 1.2 seconds. The
recognized text goes to the selected assistant, and its answer appears in the
glasses. Listening resumes after the answer. Tap **Stop voice** in the app or
its foreground notification to stop. The mode starts off after each app launch.

Audio path: glasses microphones -> Bluetooth Opus -> phone PCM decoder ->
OpenAI `gpt-transcribe` -> selected assistant -> glasses prompt page.
No phone microphone permission is needed for this path. The existing **Phone
voice input** remains a separate Android speech-service fallback (en-US).

Glasses voice requires an OpenAI API key configured under Phone model, with
`https://api.openai.com/v1` as the endpoint, even when Computer Codex provides
the answer. Transcription uses `POST /v1/audio/transcriptions`, an English
language hint, and a WAV held in memory. OpenAI API charges apply. This app
does not write audio recordings to storage. Audio leaves the phone only while
the mode is enabled; OpenAI's API data policies apply to those requests.
Stopping cancels pending transcription and ignores late results; it cannot
retract audio already sent. The existing assistant retains text conversation
context during the session.

The prototype uses energy and duration to detect utterances, not a wake word
or speaker identification. It can hear nearby speech. It keeps 300 ms of
pre-roll, requires 300 ms of voiced audio, ends after 1.2 s of quiet, and caps
each question at 20 s. It selects the stronger decoded channel and pauses
capture during transcription and answering. Background noise can cause false
starts. **Send speech now** is available during capture. Disconnects, display
handoff, audio failures, and navigation stop voice mode. Start it again explicitly.

Transcription API reference:
https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create

Chinese Gaode notification recognition is retained, while extracted direction
labels, distance units, the navigation demo and display-session language are
English. The navigation grid still uses the original 20x5 full-width layout.
Original third-party notification text and road names are shown as supplied;
these are not automatically translated. This does not add
Google Maps support or a firmware language setting.

english-translations.tsv records the reviewed text changes.
apply-english.py applies the bulk text translation to an upstream checkout.
The Bluetooth fix, English navigation output, display-session locale and
package/version/manifest changes are additional patches in this source tree.
The EnglishNavigationCompatibilityTest regression suite covers actual Chinese
notification formats, distance conversion, non-navigation notices and grid size.

Build using JDK 17, Android SDK 36 / Build-Tools 36.0.0 and Gradle 8.14:

    ./gradlew :app:assembleRelease :app:lintRelease :app:testReleaseUnitTest

Version en2 fixes the Bluetooth startup race observed on Android 17: an MTU
callback can arrive before service discovery and can repeat. Identity reading
now waits for validated characteristics and a suitable MTU, and starts only once
per connection. Four additional regression tests cover these callback orders
and reconnect state. Service UUID diagnostics are logged without identity bytes.
See Android's BluetoothGattCallback.onMtuChanged documentation for its
connection-event behavior: https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback#onMtuChanged(android.bluetooth.BluetoothGatt,%20int,%20int)

Bluetooth and firmware protocols are inherited from upstream. Physical BLE
authentication, RFCOMM connection, audio reception and notification display were
verified on an iO / Strix 1.0.6.11 with a Samsung Android 17 phone.
The wearer confirmed the English notification. The official RayNeo app needed
to be disabled because Android restarted its companion service after force-stop.
Version en4 passes 20 unit tests and release lint (zero errors, 16 warnings).
Ten speech tests cover silence/click rejection, pause detection, pre-roll,
duration limits, PCM/WAV encoding, stereo handling, and cancellation generations.
End-to-end glasses voice and extended background reliability are still being
validated; an APK build alone does not establish hardware compatibility.
The original application can be removed separately; this package has its own
settings and Android app identity.

Upstream source and Apache-2.0 license:
https://github.com/Tobi1chi/OpenRayneo
