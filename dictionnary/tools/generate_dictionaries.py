#!/usr/bin/env python3
"""Regenerates the pre-built dictionary files served from this repository.

Source: pre-trained fastText word vectors trained on Common Crawl
(https://fasttext.cc/docs/en/crawl-vectors.html) — one gzipped "cc.<code>.300.vec.gz" file per
language, several gigabytes each. Words are sorted by decreasing frequency in the file, so only
the head of each stream is read: enough lines to keep MAX_WORDS words, then the connection is
closed (the full file is never downloaded).

Output: dictionnary/dictionaries/<lang>.json in the wordfreq-style format the keyboard loads:
[ [word, log10(frequency)], ... ], most frequent first. Those small files are what the
Dictionnary app actually downloads at runtime (from raw.githubusercontent.com), the keyboard
app itself has no internet permission.

Usage:
    python3 dictionnary/tools/generate_dictionaries.py              # all languages
    python3 dictionnary/tools/generate_dictionaries.py en_US fr_FR # subset
"""

import json
import math
import os
import sys
import urllib.request
import zlib

VECTORS_BASE = "https://dl.fbaipublicfiles.com/fasttext/vectors-crawl"
OUT_DIR = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "dictionaries"))

MAX_WORDS = 25000          # words kept per dictionary, same order of magnitude as bundled en_US.json
CHUNK = 1 << 20            # 1 MiB compressed read chunks
MAX_COMPRESSED = 64 << 20  # give up after 64 MiB compressed if MAX_WORDS is not reached

# dictionary language ("<lang>.json") -> fastText crawl vector language code
LANG_TO_VEC = {
    "en_US": "en", "fr_FR": "fr", "de_DE": "de", "es_ES": "es", "it_IT": "it",
    "pt_PT": "pt", "nl_NL": "nl", "pl_PL": "pl", "sv_SE": "sv", "da_DK": "da",
    "nb_NO": "no", "ru_RU": "ru", "uk_UA": "uk", "cs_CZ": "cs", "tr_TR": "tr",
    "el_GR": "el", "he_IL": "he", "ar_AR": "ar", "bn_BD": "bn", "lv_LV": "lv",
    "lt_LT": "lt", "ro_RO": "ro", "sl_SI": "sl", "bg_BG": "bg", "eo": "eo",
}


def fetch_head_lines(url):
    """Streams the head of a gzipped .vec file, returns its first lines (incl. header)."""
    req = urllib.request.Request(url, headers={"Accept-Encoding": "identity"})
    dec = zlib.decompressobj(16 + zlib.MAX_WBITS)  # gzip wrapper, tolerant of truncation
    lines = []
    buf = ""
    downloaded = 0
    with urllib.request.urlopen(req, timeout=60) as resp:
        while downloaded < MAX_COMPRESSED and len(lines) < MAX_WORDS + 1:
            chunk = resp.read(CHUNK)
            if not chunk:
                break
            downloaded += len(chunk)
            buf += dec.decompress(chunk).decode("utf-8", "replace")
            while "\n" in buf and len(lines) < MAX_WORDS + 1:
                line, buf = buf.split("\n", 1)
                lines.append(line)
    return lines, downloaded


def convert(lines):
    """vec lines -> wordfreq-style entries [ [word, log10(freq)], ... ], most frequent first."""
    if not lines:
        return []
    header = lines[0].split()
    vocab_size = int(header[0]) if header and header[0].isdigit() else 1 << 62
    pairs = []
    rank = 0
    for line in lines[1:]:
        if len(pairs) >= MAX_WORDS:
            break
        rank += 1
        parts = line.split(" ", 1)
        if len(parts) < 2:
            continue
        word = parts[0]
        # vector files also contain punctuation/number tokens and the "</s>" marker
        if word == "</s>" or not any(c.isalpha() for c in word):
            continue
        pairs.append((word, vocab_size - rank))  # synthetic decreasing count from the rank
    total = sum(c for _, c in pairs)
    return [[w, round(math.log10(c / total), 4)] for w, c in pairs]


def generate(lang):
    code = LANG_TO_VEC[lang]
    url = f"{VECTORS_BASE}/cc.{code}.300.vec.gz"
    lines, downloaded = fetch_head_lines(url)
    entries = convert(lines)
    if not entries:
        raise RuntimeError(f"{lang}: no entries parsed from {url}")
    os.makedirs(OUT_DIR, exist_ok=True)
    out = os.path.join(OUT_DIR, f"{lang}.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(entries, f, ensure_ascii=False, separators=(",", ":"))
    size = os.path.getsize(out)
    print(f"{lang}: {len(entries)} words, {size / 1024:.0f} KB (from {downloaded / 1048576:.0f} MiB of {url}) -> {out}")
    return out


def main():
    langs = sys.argv[1:] or list(LANG_TO_VEC)
    unknown = [l for l in langs if l not in LANG_TO_VEC]
    if unknown:
        sys.exit(f"unknown languages: {unknown} (known: {sorted(LANG_TO_VEC)})")
    for lang in langs:
        generate(lang)


if __name__ == "__main__":
    main()
