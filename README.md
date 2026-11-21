# Tollystream

**Author: Taisir Shaik**

A CloudStream 3 extension repository for **Indian movies** sourced from **1TamilMV**
(Tamil, Telugu, Hindi, Malayalam, Kannada), with a **Cloudflare bypass on every request**
and **TMDB metadata** (poster, banner, plot, rating, genres, runtime, cast, IMDb id).

Every release on a 1TamilMV topic is exposed as a playable link:

| Link type | When | Playback |
|---|---|---|
| `MAGNET` | magnet link present (preferred) | CloudStream's built-in torrent server |
| `TORRENT` | only a `.torrent` attachment exists | CloudStream's built-in torrent server |
| `VIDEO`/`HLS` | topic has a `DIRECT` host link | resolved with CloudStream's extractors |

---

## Install

1. In CloudStream: **Settings → Extensions → Add repository** and paste:

```
https://raw.githubusercontent.com/MadaraUciha000/Tollystream/builds/repo.json
```

   The `builds` part matters. The `repo.json` in the root of the `main` branch is a source
   file; only the copy on the `builds` branch is ready to install.

2. Open the repository and install **Tollystream**.
3. Torrent playback uses CloudStream's bundled torrent server. If playback stalls, check
   that your network allows BitTorrent traffic (some ISPs block trackers).

> If the repository shows **no plugins**, you added the main-branch URL (or a `REPO`
> placeholder). Delete that repository entry and re-add the `builds/repo.json` URL above.

---

## How it works

### Cloudflare bypass
`Cloudflare.kt` owns a single `CloudflareKiller` (OkHttp interceptor). Every request goes
through it, with browser-like headers. When Cloudflare answers with a challenge
(`server: cloudflare` + `403/503`), the killer opens a WebView, solves it, stores the
cookies for that host and replays the request. On a hard failure the session is reset and
the request is retried once.

### Domain rotation
`Domains.kt` probes the known mirrors (`1tamilmv.ceo`, `.rocks`, `.fi`, `.io`, `.cyou`),
picks the first one that serves the forum and then prefers the host the site itself links
to. The result is cached for 30 minutes and invalidated on failure, so stored topic URLs
from an old mirror keep working.

### Rows
* **Latest Releases** – the `TOP RELEASES THIS WEEK` + `RECENTLY ADDED` blocks on the home page.
* **Tamil / Telugu / Hindi / Malayalam / Kannada** – the language forums.

### Search
1TamilMV's own search endpoint requires a CSRF token, so search filters a cached catalog
built from the home page and the language forums (10 minute TTL).

### Parsing
`Parser.kt` reads the first post (`div[data-role=commentContent]`) and pairs each torrent
attachment with the magnet link that follows it, taking the size from the magnet's `xl=`
parameter and the quality from the file name. Topic titles are taken from the `h1`; home
cards derive their title from the URL slug (the listing itself only shows sizes).

### TMDB metadata
`Tmdb.kt` turns a noisy topic title (`O.M: Operation Mole (2026) Tamil HQ PreDVD`) into a
TMDB match:

1. `cleanQuery` strips year brackets, parentheticals, languages, quality tags, codecs, audio
   labels and sizes → `O M Operation Mole`,
2. `/search/movie` is queried with the year, then without it, then with progressively shorter
   word prefixes,
3. candidates are scored on title similarity, year match and poster presence,
4. `/movie/{id}?append_to_response=credits,external_ids` fills in the detail page.

That gives the detail screen its **banner (`backgroundPosterUrl`), poster, overview, rating,
genres, runtime, top-billed cast with photos, and IMDb id** (used for sync). Home rows have no
images on 1TamilMV, so the first 12 cards of every row are matched against TMDB for a poster
(bounded to 9 s per row, 3.5 s per lookup, so a slow TMDB never stalls the row). Any failure
degrades gracefully to the topic's own poster and release list.

---

## Build & deploy

Pushing to `main` runs `.github/workflows/build.yml`:

1. checkout `main` and the `builds` branch (created automatically on the first run),
2. install JDK 17 + Android SDK 36,
3. `./gradlew make makePluginsJson`,
4. copy `*.cs3`, `plugins.json` and a rewritten `repo.json` into `builds`,
5. force-push `builds`.

Local build:

```bash
export ANDROID_HOME=/path/to/android-sdk   # platform 36 required
./gradlew make makePluginsJson
```

Artifacts land in `Tollystream/build/`.

---

## Limitations

* Torrents take a few seconds to start while peers are found; dead torrents will not play.
* Domain churn is constant. If everything fails, bump `Domains.candidates`.
* Search is a local filter over recent topics, not a full-site index.
* Home cards are poster-less beyond the first 12 per row (the listing has no images).
* TMDB matching is title-based; an obscure title with an unusual transliteration may not match
  and then falls back to the site's own data.

---

## Disclaimer

This plugin is a browser-like client. It does not host, upload or store any content; all
media is served by third-party sites. Use it only for content you are legally allowed to
access.
