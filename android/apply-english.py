#!/usr/bin/env python3
"""Apply reviewed English text to this local fork, preserving wire/parser literals."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
pairs = {}
for line in (ROOT / "english-translations.tsv").read_text().splitlines():
    if not line:
        continue
    source, target = line.split("\t", 1)
    if source in pairs and pairs[source] != target:
        raise ValueError("Conflicting translation: " + source)
    pairs[source] = target
pattern = re.compile("|".join(re.escape(s) for s in sorted(pairs, key=len, reverse=True)))
punctuation = str.maketrans({"“": "'", "”": "'", "：": ": ", "，": ", ", "；": "; ", "。": ".", "（": "(", "）": ")", "／": "/"})
for path in (ROOT / "app/src/main/java/com/openrayneo/android").glob("*.kt"):
    text = path.read_text()
    marker = {
        "NavigationNotifications.kt": "internal object GaodeNotificationParser",
        "GlassesDisplay.kt": "internal object NavigationFrame",
    }.get(path.name)
    prefix, suffix = text, ""
    if marker:
        index = text.index(marker)
        prefix, suffix = text[:index], text[index:]
    prefix = pattern.sub(lambda m: pairs[m.group()], prefix).translate(punctuation)
    if path.name in {"CompanionApp.kt", "NavigationNotifications.kt"}:
        prefix = prefix.replace("} 米", "} m").replace("}米", "} m")
    if path.name in {"SetupScreens.kt", "NotificationRecovery.kt"}:
        prefix = prefix.replace("} 条", "}")
    if path.name == "PromptAudio.kt":
        prefix = prefix.replace(" 包", " packets")
    if path.name == "MainActivity.kt":
        prefix = prefix.replace('RecognizerIntent.EXTRA_LANGUAGE, "zh-CN"', 'RecognizerIntent.EXTRA_LANGUAGE, "en-US"')
    path.write_text(prefix + suffix)
print("Applied", len(pairs), "English text mappings; navigation parsing and frame encoding preserved.")
