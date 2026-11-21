# Pre-built dictionaries

One `<lang>.json` file per supported language, in the wordfreq-style format the keyboard loads:
`[ [word, log10(frequency)], ... ]`, most frequent words first (~24k words, ~0.5 MB per file).

They are generated offline from the fastText Common Crawl word vectors
(https://fasttext.cc/docs/en/crawl-vectors.html) — only the head of each gzipped
`cc.<code>.300.vec.gz` is read, the gigabyte-long files are never downloaded fully:

    python3 ../tools/generate_dictionaries.py            # regenerate all
    python3 ../tools/generate_dictionaries.py en_US fr_FR # regenerate a subset

The Dictionnary app downloads these files as-is at runtime from this repository
(raw.githubusercontent.com, see `DICTIONARY_BASE_URL` in `helpers/Constants.kt`), so after
regenerating, commit and push this folder for the new files to be served.
