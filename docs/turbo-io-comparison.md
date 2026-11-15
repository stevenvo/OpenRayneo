# Turbo-IO feature and compatibility check

Checked 2026-11-14 against current public main, commit
[`28a0fef`](https://github.com/Turbo1123/Turbo-IO/tree/28a0fef0a42773f38222fcd0db2e0f2852ed4257).
GitHub's branch and release metadata were checked live. This is source/archive
inspection; Turbo-IO was not installed or flashed on Steven's glasses.

**Yes, Turbo-IO has much more native UI work than OpenRayneo. It is not a
drop-in replacement for this Android/Strix setup.** It combines patches to the
stock glasses firmware with extensions injected into particular versions of
the official phone app. Many official features remain available through that
host rather than being reimplemented by Turbo-IO.

## What exists

| Capability | Evidence and limits |
|---|---|
| Native home/app menu | Firmware launcher modifications and extra native app entries; phone controls for two/three menu rows. This is substantially beyond OpenRayneo's temporary display pages. It is not a general Android launcher. |
| Custom dashboard and small apps | Card renderer/editor and a bounded four-slot app runtime, with crown events and phone/backend communication. Android's published card installation path specifically requires TVP2. Templates are examples, not preauthorized live services. |
| Music, reading, Pomodoro, contextual English | Native modules are included in the 1.0.6.11 build inputs, with phone-side controls/data services. Reading includes TXT; the public package does not include the WeRead web-text integration. Contextual English and several Android paths still need hardware acceptance. |
| Navigation | Native map/route rendering plus phone routing/location code. Newer iOS documentation reports lock-screen navigation problems; Android's port is not fully hardware-verified. |
| Camera/video displays | Native phone-camera preview/control and video/image display code. Android offers local video/test patterns, not phone screen mirroring or AirPlay reception. The newest R1 video fix has offline evidence, with continuous hardware playback still pending in its release notes. |
| Teleprompter | Inherits the official feature and adds a news-to-teleprompter controller using the existing native business/file-transfer protocol. It does not require replacing the stock teleprompter engine. |
| Custom AI and spoken answers | The Android addon intercepts the official app's final ASR/NLP callbacks and can substitute a configured model response. TTS is phone-side. This is not a standalone replacement for the official wake/audio/ASR pipeline. |
| Captions and translation | Android has cloud caption/translation code with a separately configured WebSocket service. It uses an actual Android audio input route (phone, compatible headset, etc.); this does not establish working direct iO microphone routing. Offline translation is deferred on Android. Separate iOS local-speech/translation code exists. |
| Notifications, LifeLog, recordings | Phone-side history, enhanced LifeLog, audio-save and recording-organization modules exist, often depending on official host hooks. Several are implemented/offline-tested but still awaiting device verification. |
| Custom wake phrase | No implementation found in the inspected Android addon or 1.0.6.11 extension sources. The shipped keyword-detector DLL is identical to stock. New wake diagnostics concern head-up display/IMU behavior, not arbitrary spoken keywords. |
| Dots / full ecosystem parity | Explicitly unfinished. Presence of a menu entry, source directory or protocol handler is not evidence of a complete product. |

Primary sources: [Android capability/status table](https://github.com/Turbo1123/Turbo-IO/blob/28a0fef0a42773f38222fcd0db2e0f2852ed4257/android-addon/README.md),
[native build inputs](https://github.com/Turbo1123/Turbo-IO/blob/28a0fef0a42773f38222fcd0db2e0f2852ed4257/firmware-research/strix-1.0.6.11/build-inputs.json),
[ASR interception](https://github.com/Turbo1123/Turbo-IO/blob/28a0fef0a42773f38222fcd0db2e0f2852ed4257/android-addon/src/com/turboio/addon/TurboAddon.java),
[cloud captions](https://github.com/Turbo1123/Turbo-IO/blob/28a0fef0a42773f38222fcd0db2e0f2852ed4257/android-addon/src/com/turboio/addon/CloudCaption.java),
[teleprompter](https://github.com/Turbo1123/Turbo-IO/blob/28a0fef0a42773f38222fcd0db2e0f2852ed4257/android-addon/src/com/turboio/addon/NewsTele.java),
[iOS local translation](https://github.com/Turbo1123/Turbo-IO/tree/28a0fef0a42773f38222fcd0db2e0f2852ed4257/official-addon/local-translation),
[version-specific firmware notes](https://github.com/Turbo1123/Turbo-IO/blob/28a0fef0a42773f38222fcd0db2e0f2852ed4257/firmware-research/strix-1.0.6.11/README.md).

## How the UX is organized

The Android extension adds a **Turbo IO button inside the official app** and
opens its own full-screen hub without replacing the vendor's tabs. The hub has
Home, Translation, News and Settings navigation, six illustrated feature tiles,
and shortcuts for model configuration and camera control. Its native Android
views use dark plum backgrounds, off-white text, lime buttons, rounded cards
and scrollable detail screens. Much of the inspected UI text is Chinese.
[Phone hub implementation](https://github.com/Turbo1123/Turbo-IO/blob/28a0fef0a42773f38222fcd0db2e0f2852ed4257/android-addon/src/com/turboio/addon/EditorialUI.java).

The glasses' native renderer provides **two or three rows of three icons**,
selection motion, page indicators, and a time/battery header. Its 18 labels mix
stock applications with Turbo additions; Dots remains unfinished despite its
menu entry. Crown input controls selection and app-specific actions. This
provides a more integrated native menu than OpenRayneo can offer through stock
temporary pages. [Menu renderer](https://github.com/Turbo1123/Turbo-IO/blob/28a0fef0a42773f38222fcd0db2e0f2852ed4257/firmware-research/strix-1.0.6.11/src/focus-v1/focus_menu.c).

Complex authoring stays on the phone: **choose a template → edit/preview →
confirm sending → read the glasses' result**. The dashboard editor supports
dragging components, text/value edits, undo and local drafts. The app gallery
previews packages and queries four actual device slots before installation;
phone drafts are distinguished from installed content.
[Card editor](https://github.com/Turbo1123/Turbo-IO/blob/28a0fef0a42773f38222fcd0db2e0f2852ed4257/android-addon/src/com/turboio/addon/CardUI.java),
[app gallery](https://github.com/Turbo1123/Turbo-IO/blob/28a0fef0a42773f38222fcd0db2e0f2852ed4257/android-addon/src/com/turboio/addon/GalleryUI.java).

This is a source-based UX description, not a hands-on usability evaluation.
Several repository hero illustrations are explicitly labeled generated
concepts. Some UI labels also retain old version references, so release gates
and code profiles are stronger compatibility evidence than screen copy.

## The compatibility gap on this device

Steven has **Android app 1.0.20 (309), Strix OS 1.0.6.11**.

The current Android package builder accepts only two pinned official APKs:
**1.0.6 (216)** and **1.0.7 (230)**. Its firmware installer accepts one exact
**TVP2 / Strix 1.0.5.5** archive, offered only over 1.0.4.12 or 1.0.5.5.
There is no supported 1.0.20/1.0.6.11 profile in those paths. Changing a version
string or removing a hash check would not adapt the host's changed classes or
firmware protocol. [Host profiles](https://github.com/Turbo1123/Turbo-IO/blob/28a0fef0a42773f38222fcd0db2e0f2852ed4257/android-addon/host-profiles.mjs),
[firmware pins](https://github.com/Turbo1123/Turbo-IO/blob/28a0fef0a42773f38222fcd0db2e0f2852ed4257/android-addon/src/com/turboio/addon/OtaPackage.java).

The newer **TMIG2-VIDEO-R1 for Strix 1.0.6.11** is published, but its matching
documented phone environment is iPhone 17 Pro Max / RayNeo iOS 1.0.21 with a
matching integrated plugin. The supplied original IPA does not contain that
plugin. R1 is a prerelease; its release distinguishes successful base-TMIG2
flashing from R1 package/offline validation. [Release](https://github.com/Turbo1123/Turbo-IO/releases/tag/strix-10611-tmig2-video-r1).

## Independent firmware check

I compared every member of the downloaded stock and custom 1.0.6.11 archives.
Both contain 17 members. Only `nuttx_ap.bin` and its manifest differ; the other
15 payloads, including `pil_algo_wakeup_dll_nand.dll`, match byte for byte.
The custom AP sources include 90 compilation units. The detector comparison
supports retaining the stock wake vocabulary; it does not prove every
assistant or sleep-state behavior is unchanged, since the AP was modified.

| Archive | SHA-256 |
|---|---|
| Stock | `e403d73b84c20277f183788d778beb7a0ad8336af27efb112c66b7169fcaa320` |
| TMIG2-VIDEO-R1 | `c03321061605aba186b44e7b90d86d3b13a2f75e081687fc684680e2800a4e8a` |

## Implication for our project

Turbo-IO is valuable research for native menus, dashboard data and app-runtime
protocols. For this device, keeping stock Strix while completing OpenRayneo's
companion features avoids an unsupported Android/firmware combination. A
Turbo route would require a deliberate Android 1.0.20/Strix 1.0.6.11 port and
hardware validation, not just installing its existing APK.

For native keyword activation, its reuse of official ASR explains why it does
not automatically solve OpenRayneo's missing wake/audio handshake. Our next
protocol task remains handling the firmware's built-in assistant wake event.

Turbo-IO's original extension code is under **PolyForm Noncommercial 1.0.0**,
whereas OpenRayneo is Apache-2.0. No Turbo implementation was copied into this
fork. Any future reuse must retain the applicable terms rather than being
presented as Apache-only code. [Turbo license](https://github.com/Turbo1123/Turbo-IO/blob/28a0fef0a42773f38222fcd0db2e0f2852ed4257/LICENSE).
