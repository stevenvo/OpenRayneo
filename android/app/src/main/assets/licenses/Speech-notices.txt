# Offline English captions

The Android captions feature uses Vosk 0.3.75 and the English
`vosk-model-small-en-us-0.15` model. Both are published under Apache-2.0.
The app bundles the model for offline recognition; the model binaries are
excluded from this repository. Run `python3 scripts/prepare-speech-model.py`
from the repository root before building the feature edition.

Sources and license information:
- https://github.com/alphacep/vosk-api
- https://alphacephei.com/vosk/models
- https://github.com/alphacep/vosk-android-demo

Vosk's Android API uses Java Native Access (JNA) 5.18.1. JNA is dual-licensed
under LGPL-2.1-or-later and Apache-2.0; this distribution uses its Apache-2.0
option. See https://github.com/java-native-access/jna/blob/master/LICENSE.
The repository's Apache-2.0 license text is retained in LICENSE.

The downloaded model ZIP is pinned to SHA-256
`30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498`.
The model is approximately 40 MB compressed / 71 MB extracted. Loading it
needs additional phone memory. Captions run locally and do not use the
OpenAI key. Assistant voice still uses the separately documented cloud path.
