# OpenRayneo feature audit

Reviewed 2026-11-14. Baseline: [stevenvo/OpenRayneo commit 876826d](https://github.com/stevenvo/OpenRayneo/tree/876826d731fef6c606443902d634399bbd86b488), Android 1.0.0-en6 (25). Device evidence: RayNeo iO, Strix 1.0.6.11, Samsung Android 17. Upstream baseline: 7f2fc0d. This is a feature and implementation audit, not a claim that every path has passed hardware testing.

## Development update: en7

After the baseline audit, the Android source gained a Features tab, saved script
library, UTF-8 TXT import, native teleprompter transfer/pause/resume/stop,
continuous offline English captions with transcript export, and explicit
return-home controls. Connection alone no longer starts the prompt microphone
page. The model is bundled and hash-pinned. These are new implementations,
not yet wearer-verified on Strix. The remaining gaps in the baseline matrix
still apply unless listed here. Native keyword activation has **not** been
implemented or changed; [firmware validation](wake-word-validation.md) found
predefined wake modes and a separate native event path.

The en7 build is installed. Validation: 46 passing unit tests, lint with zero
errors/17 warnings, and one on-phone Vosk model-load/synthetic-audio smoke test.
Script save/open and connected-but-idle display/audio were checked on the phone.
This does not establish teleprompter playback or caption accuracy in the lenses.
[Turbo-IO comparison](turbo-io-comparison.md) covers the alternative firmware
and its Android version mismatch.

## What the project actually is

OpenRayneo contains two separate companion applications. The Swift macOS app has the broader glasses protocol implementation and a local HTTP API. Android is a smaller Kotlin/Compose app with its own Bluetooth implementation, display coordinator, notification listener, and assistant clients. Our English fork adds Bluetooth startup fixes, English UI, OpenAI glasses voice, streamed answers and live weather.

Neither app replaces Strix or installs an Android launcher on the glasses. Both communicate with existing firmware pages. The glasses are an embedded device, not an Android phone accepting APKs.

The inventory includes 13 Swift source files, 27 Android Kotlin production files, a C virtual-audio driver, build/packaging scripts, a Python Codex launcher, six Android test files, and protocol/product documentation. The audit follows UI entry points into services, transport messages and external dependencies; historical plans are distinguished from current executable code.

## Capability matrix at the en6 baseline

“Upstream tested” means evidence reported by the original author, usually on one Mac/device and an unspecified glasses firmware. It does not mean tested on Steven's Strix glasses.

| Feature | English Android en6 | macOS upstream | Readiness / missing work |
|---|---|---|---|
| BLE discovery, authentication, classic Bluetooth | Implemented; verified on Steven's phone | Implemented; upstream tested | Account-bound ECDH authentication explicitly unsupported |
| Reconnect | Three bounded retry attempts; manual reconnect verified | Connect/recovery controls | Long background runs, power cycles and noisy radio conditions still need testing |
| English interface | Implemented on Android | Desktop controls remain Chinese | English README is not evidence of an English desktop UI |
| Phone AI assistant | OpenAI-compatible Chat Completions, streaming, tools, cancellation | No equivalent integrated phone client | OpenAI tested; a native Anthropic Messages client is absent; compatible providers need their own verification |
| Computer Codex | Authenticated WebSocket, thread resume, approvals/cancel | Python helper launches Codex separately | Typed USB path tested; voice-to-Codex and remote-network deployments unverified |
| Glasses-microphone questions | Implemented in our fork; wearer confirmed earlier answer | Local Apple speech transcription | en6's new weather answer has not yet been confirmed in the lenses |
| Streaming speech and answers | OpenAI gpt-live-transcribe; PCM packets while speaking; streamed answer display | Streaming Apple local recognition | Current Android assistant uses discrete turns and pauses capture during answers; not a continuous meeting-caption mode |
| Phone-microphone speech input | Android speech-recognition activity in en-US | Not the glasses ASR path | Separate fallback; provider depends on the phone |
| Continuous captions | No dedicated Android mode or transcript document | Caption text API + local glasses ASR | Port needed; text rendering alone is not speech recognition |
| Offline glasses transcription | Not implemented | Apple on-device recognizer; language assets required | Apple's implementation cannot simply be copied to Android |
| Live translation | No dedicated transcription→translation mode | Can display caller-supplied translated text | A `translation` display field does not perform translation; language handling/model workflow needed |
| Teleprompter | Not implemented | Native script transfer, auto-scroll speed, pause/resume/stop | Port file transfer + controls; upstream tested short and 16 KB scripts |
| Script library / import | Not implemented | Paste title/text; one active script per process | No official library sync; DOC/DOCX/PDF import and smart-follow are absent |
| Voice memo / WAV recording | No recordings saved | Continuous stereo WAV, optional ASR recording, playback | Port needed; app-private storage/export/retention UI required |
| LifeLog | Not implemented | Finite experimental observation and mono recording | Not an all-day searchable memory product; cached A4 download absent |
| Keyword activation | Not implemented | Speech-triggered LifeLog experiments only | No keyword spotter; speech-triggered wake is not “Hey RayNeo” recognition |
| Wake from firmware sleep | Not implemented/verified | No verified keyword-wake takeover | Need native assistant wake protocol or LifeLog validation; a phone detector only works while it receives audio |
| Return to stock home | Closing/yielding the active companion page | Caption/prompt stop | Implemented mechanism; firmware can reject page startup (observed status 4) |
| Replace firmware home / custom launcher | No | No | No stock-firmware contract for replacement or arbitrary native menus |
| Read dashboard configuration | No Android UI/protocol path | Experimental GET /v1/dashboard | Read path; not a supported layout editor |
| Update native weather widgets | No; assistant weather is separate | Current-location/city weather writes | Desktop caller supplies data; no forecast fetcher/timer there |
| Fetch live weather for questions | Implemented; Bellevue NWS result verified on phone | Not fetched by bridge | en6 fixes NWS HTTP 301 coordinate normalization; non-US Open-Meteo path less tested |
| Todos | No | Experimental read/add/remove | Full-list synchronization; no concurrent-writer protection; deletion restricted to items created by current process |
| Calendar events | No | Protocol research only | Schedule writes/source authorization absent |
| Mail, stocks, world clock integrations | No | No product integrations found | Requires data-source implementations; existing firmware widgets are not equivalent to sync support |
| Custom notification push | Test/assistant tool | UI + HTTP API | Rendering previously wearer-confirmed; no delivery/read receipt |
| Mirror selected phone apps | No | No phone notification listener | Android listener currently filters Gaode; allowlist and privacy controls needed |
| Reply to notifications | No | No | Sending a notification is not replying to the source app |
| Navigation | Gaode notification-following, fixed 20×5 display | Manual direction/distance demonstration | No route planner, GPS, Google Maps or Apple Maps integration |
| Native device settings | No general battery/brightness/focus/head-gesture/crown settings UI | No complete settings implementation | Settings protocols and acknowledgements need mapping; don't infer support from firmware menus |
| Text/video display | Prompt and navigation surfaces | Character-video 26×7, 5/10 updates per second | Text-grid video only; not arbitrary graphics or screen mirroring |
| Virtual microphone | No Android system mic driver | Two macOS virtual devices with driver | macOS-only subsystem; cannot port its driver to an ordinary Android app |
| Local automation API | No Android server | Authenticated loopback HTTP API | Desktop API token/connection lifecycle; not an internet service |
| Firmware OTA/rollback/recovery | No | Analysis documents, no flasher | Keep official app available; custom Turbo-IO firmware is a separate project |

## Home screen and wake word: concrete findings

The stock Dashboard persists because OpenRayneo opens existing pages temporarily. Android already reacts to a device-ended page by yielding display ownership and invalidating stale work. Press-and-hold crown is documented by RayNeo as return-to-Dashboard; the default assistant shortcut is double-click, and the official assistant supports “Hey RayNeo” / “OK RayNeo”. These official controls do not establish that OpenRayneo handles their events. [RayNeo controls and assistant documentation](https://www.rayneo.com/pages/support-rayneo-io-ai-glasses).

Three separate improvements are possible: clearer return-home controls in Android; updating supported native dashboard data; and a companion-owned status page. A companion page should not be described as replacing the firmware launcher. Existing dashboard reads do not establish a safe layout-write contract.

The desktop LifeLog protocol is the best existing wake-related lead. It enables `life_log_switch` on launcher business 0x0F; glasses A1 wake events on business 0x0D are answered with A2 recording start and A8 page state. A3 carries bounded batches of 240-byte audio frames. Upstream controlled trials observed wake for both wearer and external speech, and quiet/head-up-only trials produced no wake. This proves neither a keyword detector nor reliable wake on Steven's Strix revision. [Protocol evidence](https://github.com/stevenvo/OpenRayneo/blob/876826d731fef6c606443902d634399bbd86b488/docs/lifelog-protocol.md).

A local phone keyword detector can avoid sending standby audio to OpenAI, but still needs live microphone audio from the glasses and consumes battery. Voice identity, false triggers, lock-screen reliability and deep-sleep behavior require physical tests. Arbitrary background speech must not be treated as an authorized command.

## Source map

| Area | Main implementation | What was checked |
|---|---|---|
| Android UI/configuration | CompanionApp, AssistantScreen, SetupScreens, AppModel, AppSettings | Three tabs; encrypted settings; no feature-library tab |
| Android transport | RayneoConnection, BleSetupSequence, ProtocolCodec, ConnectionSupervisor | BLE/SPP setup, CRC/framing, identity ordering, generation checks and retries |
| Android display | BridgeService, GlassesCoordinator, GlassesState, GlassesDisplay, GlassesPreview | Page startup acknowledgement, SID ownership, navigation/prompt arbitration, audio lifetime, stale answers, preview limits |
| Android assistant/audio | GlassesVoice, OpenAiSpeechStream, SpeechSegmenter, StreamingSpeech, PromptAudio, MobileAgent, CodexClient | PCM processing, manual commits, OpenAI-compatible calls, cancellation, local/remote distinctions |
| Android notification/navigation | NavigationNotifications, NotificationRecovery | Gaode-only filter and parser, foreground snapshots, bounded listener recovery |
| Android weather | WeatherTool, WeatherRedirect | Geocoding ambiguity, NWS forecast versus observations, bounded same-host redirects |
| Desktop UI/lifecycle | DesktopUI, DesktopBridge, BridgeProcessLifecycle, BluetoothPairing, PairingCommand | Seven panels, service and pairing controls, Chinese labels, no parity with all API routes |
| Desktop transport/API | main.swift | Authentication, notification payload, teleprompter/file transfer, bounded frame sizes, loopback API |
| Desktop displays | DisplayController, NavigationFrame | Captions/prompts, todo snapshots, weather commands, LifeLog handshakes |
| Desktop audio | SpeechController, WAVRecording, VirtualMicrophone, C audio driver | Apple local recognition, recording, separate virtual outputs, finite LifeLog inspection |
| Desktop video | TextVideo, FullWidthBrightnessPalette | Local movie-to-character rendering; alignment/ratio limits |
| Delivery | Gradle, Swift package, manifest, scripts, GitHub Actions | Android permissions/dependencies, release signing, desktop build-only CI, Codex helper |

Sources: [Android production code](https://github.com/stevenvo/OpenRayneo/tree/876826d731fef6c606443902d634399bbd86b488/android/app/src/main/java/com/openrayneo/android), [desktop production code](https://github.com/stevenvo/OpenRayneo/tree/876826d731fef6c606443902d634399bbd86b488/Sources/OpenRayneoBridge), [driver](https://github.com/stevenvo/OpenRayneo/tree/876826d731fef6c606443902d634399bbd86b488/Drivers/OpenRayneoAudio).

## What is not ready for everyday replacement

- The installed en6 build passes 34 regression tests and release lint (0 errors, 16 warnings). These cover Bluetooth callback ordering, English Gaode parsing, speech segmentation/PCM, transcript matching, display feedback and redirect validation. They are not a comprehensive device-acceptance suite.
- Desktop has no Swift test target. CI builds the Mac app; it does not run Android checks or exercise glasses hardware. Several old documents describe temporary tests that are no longer shipped.
- Startup status 4 has been observed on Steven's device. Bluetooth connectivity alone does not prove the firmware accepted a display/audio page. The UI preview is sent content, not a screenshot from the lenses.
- Android conversational memory is in-session, and there is no persistent transcript/recording library. Streaming voice currently stops on display handoff or audio loss; it does not run as a background wake-word service.
- The stronger of two decoded audio channels is selected to handle the silent-channel case on this Strix build. This is not validated wearer-only speech isolation.
- The direct distribution APK is non-debuggable but uses the lab development signing certificate. Signing backup/release management and Android CI should be established before broader distribution.
- Historic Android plans mention a navigation SDK and future ASR. The current app removed the SDK and our fork added cloud ASR; plans and stale README passages must not be used as current feature claims.

## Implementation order toward official-app parity

1. Preserve the tested en6 baseline. Add a Features page, explicit return-to-home controls, teleprompter library/transfer and live-caption mode.
2. Add English-first translation, transcript export and optional recording; selected-app notification mirroring; native dashboard reads and supported data updates.
3. Add local keyword detection and evaluate the LifeLog/native-assistant wake paths on Strix. Keep enabled/listening/transmitting states explicit.
4. Expand navigation, reminders/calendar and device-settings protocols with real source data and acknowledgements.
5. Address firmware-dependent launcher customization, OTA and proprietary/account services only after compatibility/recovery is understood. Full official-app parity cannot currently be claimed or guaranteed from this codebase.

The official app should remain installed but disabled during OpenRayneo use. That preserves a fallback for settings, firmware updates and missing services without competing for the Bluetooth connection.
