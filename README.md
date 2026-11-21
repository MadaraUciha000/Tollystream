# Tollystream

A CloudStream 3 extension repository for **Indian movies** sourced from **1TamilMV**
(Tamil, Telugu, Hindi, Malayalam, Kannada), with a **Cloudflare bypass on every request**.

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
* Home cards have no posters (the listing has no images); the poster appears on the detail page.

---

## Disclaimer

This plugin is a browser-like client. It does not host, upload or store any content; all
media is served by third-party sites. Use it only for content you are legally allowed to
access.
