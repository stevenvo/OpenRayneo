# Local English edition

Based on OpenRayneo commit 7f2fc0dbb4414fa66bdc5a1be23d44b7d4b4ec31.
This is an unofficial English Android fork for RayNeo iO.
Source: https://github.com/stevenvo/OpenRayneo/tree/english-android

- Name: OpenRayneo English
- Package: com.openrayneo.android.english
- Version: 1.0.0-en7 (26)
- Android: 13 or later
- Release build: not debuggable, signed with this lab's development certificate.

The setup guide, tabs, settings, assistant, permission guidance, diagnostics,
connection errors and test notification are translated into English. The phone
assistant defaults to English while preserving its tool-use limitations.

## Features in en7 (physical glasses acceptance pending)

The **Features** tab adds a local script library (up to 30 scripts), UTF-8 TXT
import (32,000 bytes per script), native teleprompter transfer and playback
controls, and continuous offline English captions with explicit TXT export.
The teleprompter uses the upstream 0x11/0x14 protocol; successful writes are
not a receipt that the wearer saw playback. Smart-follow, DOC/PDF import and
synchronization with the official script library are not implemented.

Offline captions use Vosk on the phone. They do not upload audio or use the
OpenAI key. Recognition quality, long background sessions, and actual glasses
caption display still require wearer testing. The transcript retains the most
recent 60,000 characters in memory and is saved only through explicit export.
The OpenAI assistant voice path below is separate.

Connecting now leaves the stock glasses home screen alone. **Return to glasses
home** closes companion display/audio and stops the active feature. It does
not replace the firmware launcher or remap the crown. Native wake-word settings
remain unchanged: [validation](../docs/wake-word-validation.md).

Build preparation: run `python3 scripts/prepare-speech-model.py` from the repo
root before Gradle. The model is hash-pinned, excluded from Git, and packaged
inside the APK. See [speech dependencies/licenses](THIRD_PARTY_SPEECH.md).

[Baseline feature audit and parity gaps](../docs/feature-audit-en6.md) distinguish
upstream desktop features from Android implementations. Full official-app
parity is a development target, not a claim for this release. Translation,
notification mirroring, recording, native dashboard sync/settings, native wake
integration and broader navigation remain unfinished.

The en7 release passes 46 unit tests and release lint (zero errors, 17 warnings).
A separate instrumentation smoke test passed on the Samsung: the bundled Vosk
model loaded and processed synthetic silence without microphone, Bluetooth or
network access. On-phone checks confirmed script save/open and that a Bluetooth
connection leaves the display idle with glasses audio stopped. Native script
playback, caption accuracy and long-running sessions still need wearer testing.
An Android CI workflow is included; these results are from local/device checks.

## Glasses voice (experimental)

In Assistant, select Phone model or connect Computer Codex, then tap **Start
glasses voice**. Speak a short question and pause for about 0.7 seconds. The
recognized text goes to the selected assistant, and its answer appears in the
glasses. Listening resumes after the answer. Tap **Stop voice** in the app or
its foreground notification to stop. The mode starts off after each app launch.

Audio path: glasses microphones -> Bluetooth Opus -> phone PCM decoder ->
OpenAI `gpt-live-transcribe` -> selected assistant -> glasses prompt page.
Speech is sent during the question and partial transcripts appear on the
phone and glasses. Answers are displayed as the assistant streams them.
The glasses show the actual voice state, including when voice is off.
No phone microphone permission is needed for this path. The existing **Phone
voice input** remains a separate Android speech-service fallback (en-US).

Glasses voice requires an OpenAI API key configured under Phone model, with
`https://api.openai.com/v1` as the endpoint, even when Computer Codex provides
the answer. Transcription uses `wss://api.openai.com/v1/realtime?intent=transcription`,
24 kHz mono PCM in 100 ms packets, an English language hint, and low delay.
Each question has its own streaming session and matching committed item ID.
OpenAI API charges apply. This app
does not write audio recordings to storage. Audio leaves the phone only while
the mode is enabled; OpenAI's API data policies apply to those requests.
Stopping cancels pending transcription and ignores late results; it cannot
retract audio already sent. The existing assistant retains text conversation
context during the session.

The prototype uses energy and duration to detect utterances, not a wake word
or speaker identification. It can hear nearby speech. It keeps 300 ms of
pre-roll, requires 300 ms of voiced audio before streaming, ends after 0.7 s of quiet, and caps
each question at 20 s. It selects the stronger decoded channel and pauses
capture during transcription and answering. Background noise can cause false
starts. **Send speech now** is available during capture. Disconnects, display
handoff, audio failures, and navigation stop voice mode. Start it again explicitly.

Transcription API reference:
https://developers.openai.com/api/docs/guides/realtime-transcription

## Live weather

The Phone model can call `get_weather` for a named city, full state/region and
country. It resolves the city with Open-Meteo geocoding and checks for ambiguity.
For US cities it retrieves the National Weather Service forecast; elsewhere
it uses Open-Meteo's current model-based conditions and today/tomorrow forecast.
NWS period temperatures are forecast highs/lows, not current measurements.
Responses include location, local time, units and source. This does not use
phone GPS, send the OpenAI API key to the weather service, or add general web
search. If the city is missing or ambiguous, the assistant asks for it.
Data and attribution: https://open-meteo.com/ and https://open-meteo.com/en/docs;
https://www.weather.gov/documentation/services-web-api

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
Version en6 passes 34 unit tests and release lint (zero errors, 16 warnings).
Ten speech tests cover silence/click rejection, pause detection, pre-roll,
duration limits, PCM/WAV encoding, stereo handling, and cancellation generations.
Nine more tests cover streaming before end-of-turn, PCM conversion and packet
sizes, matching/duplicate transcripts, and display status without losing answers.
The en4 completed-question path was verified on 2026-11-14 with Strix 1.0.6.11:
SPP microphone packets were decoded, `gpt-transcribe` returned English text,
the configured phone model answered, and the wearer confirmed the answer in
the lenses. Multiple speech turns completed. Extended background reliability,
noisy environments, and voice-to-Computer-Codex remain unverified; typed
requests to both assistants were previously verified on this setup.
The en5 streaming connection, English transcript completion, and answer delivery
were tested on the same phone/glasses. Observed final-transcript latency was
approximately 0.45–0.65 seconds after commit, in addition to the 0.7-second
end-of-speech pause. This is a small functional sample, not a latency benchmark.
Version en6 follows bounded HTTPS redirects on the same weather host, fixing
the HTTP 301 returned when NWS rounds a location's coordinates. Five regression
tests cover canonical relative/absolute locations and rejected destinations.
The installed en6 phone model retrieved Bellevue's NWS forecast successfully
on 2026-11-14. The glasses rejected display startup with status 4 during this
check; wearer verification of the weather answer was deferred. Voice was off
at the end of that test. The phone's wired-power stay-awake setting was verified
active for both AC- and USB-classified cable connections.
The original application can be removed separately; this package has its own
settings and Android app identity.

Upstream source and Apache-2.0 license:
https://github.com/Tobi1chi/OpenRayneo
