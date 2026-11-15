#!/usr/bin/env python3
"""Download the pinned Apache-2.0 English Vosk model for offline Android captions."""
import hashlib
from pathlib import Path
import tempfile
import urllib.request
import zipfile

URL = 'https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip'
SHA256 = '30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498'
ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'android/app/src/main/assets'
with tempfile.TemporaryDirectory() as tmp:
    archive = Path(tmp) / 'model.zip'
    urllib.request.urlretrieve(URL, archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise SystemExit('Unexpected model hash; no files extracted')
    with zipfile.ZipFile(archive) as z:
        for entry in z.infolist():
            p = Path(entry.filename)
            if p.is_absolute() or '..' in p.parts or p.parts[0] != 'vosk-model-small-en-us-0.15':
                raise SystemExit('Unexpected archive member')
        if sum(e.file_size for e in z.infolist()) > 100_000_000:
            raise SystemExit('Unexpected expanded model size')
        z.extractall(DEST)
print('English speech model ready. It is excluded from Git.')
