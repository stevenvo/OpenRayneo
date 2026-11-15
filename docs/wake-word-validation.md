# RayNeo iO wake-word validation

Checked 2026-11-14. The evidence supports built-in firmware wake modes, not arbitrary user-defined phrases. OpenRayneo should first integrate with the native assistant wake protocol.

## Evidence and limits

1. The community-supplied **stock Strix OS 1.0.6.11** archive contains `pil_algo_wakeup_dll_nand.dll`. Its diagnostic strings identify a DSP hotword detector (version v0403), with separate `rayneo` and `xiaoleixiaolei` detections at file offsets **0x44523** and **0x44555**. This is static evidence from the downloaded stock image, not a dump read back from Steven's glasses.
2. `nuttx_ap.bin` contains a `DeviceStatusManager` wake-word preference validator restricting values to **1 or 2**, at **0xa0873**. It also contains `set_ai_wakeup_word`, a separate voice-wake enable command, an English-only KWS function name, and separate assistant KWS/AlwaysOn VAD resource paths. A mode selector is not an arbitrary text-setting interface.
3. The installed official app **1.0.20** was copied read-only for local analysis. Its Flutter library includes a wake-word settings page, the label **OK RayNeo / Hey RayNeo**, and **小雷小雷**. Relevant `libapp.so` offsets are **0x8c3a8c** (English label) and **0xcf07bd** (UTF-16LE Chinese phrase). The app remained disabled; no account data or API key was read.
4. RayNeo's public help page documents the English phrases and the crown assistant shortcut. [Official controls](https://www.rayneo.com/pages/support-rayneo-io-ai-glasses).

These sources support predefined wake choices with English and Chinese detector labels. They do **not** prove that each number selects one language rather than a combined/English-only mode, and do **not** establish the exact mode-number mapping, the current setting on the user's glasses, false-wake performance, or reliable wake from every sleep state. No undocumented setting was written and no firmware was flashed.

## Implementation consequence

Do not offer a made-up phrase as if the firmware supports it. First map the native assistant wake and audio events, preserve the firmware's keyword detector, then route the recognized command to the configured assistant. The LifeLog speech-triggered path is a separate mechanism and must not be presented as keyword detection.

A local phone keyword detector would be an alternative only while audio is available, not proof of deep-sleep wake support. The new local speech model being added to Android is for **continuous English captions**, not replacement firmware wake words.

## Provenance

| Input | SHA-256 |
|---|---|
| Stock Strix archive | e403d73b84c20277f183788d778beb7a0ad8336af27efb112c66b7169fcaa320 |
| Wake module | d1594cb8658b228e52c2606d02f1eab696d35a11ed42ddcb725e76a745f5550f |
| AP image | 9bbf7be31ebb7f8600e6ea4bf51831a151e2af6d4eb8e6ccfcf55d67f2e71260 |
| Official base APK | 506a2bc015df28ecd3c418762c412cef4bbdf67a9a087f57fd3957538653fe4b |
| Official ARM64 APK split | 5859d5354fbf3c07d24ea4b6a10ac23aaea8ae8b44b44689ac0ceba8fff88f1c |

REA 6.9.0 was attempted for additional inspection. Its generic inspector does not recognize the proprietary wake DLL; its APK provider needs a caller-supplied JADX MCP JAR, and firmware-region inspection needs a Linux Binwalk provider. The findings above come from bounded direct byte/string inspection and source comparison, not a successful full REA decompilation. Proprietary binaries and full extracted strings remain local and are excluded from Git.
