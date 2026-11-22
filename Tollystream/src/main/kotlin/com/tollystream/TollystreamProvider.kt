package com.tollystream

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

class TollystreamProvider(
    private val fixedLanguage: String? = null,
) : MainAPI() {
    companion object {
        const val PROVIDER_NAME = "Tollystream"
        private const val CATALOG_TTL_MS = 10 * 60 * 1000L
        private const val POSTER_LOOKUPS = 6
        private const val POSTER_TIMEOUT_MS = 2_000L
        private const val DETAILS_TIMEOUT_MS = 12_000L
        private val SUBFORUM_GATE = Semaphore(4)

        @Volatile
        private var catalog: List<Pair<String, String>>? = null

        @Volatile
        private var catalogAt = 0L
    }

    override var mainUrl = Domains.candidates.first()
    override var name = fixedLanguage?.let { "$PROVIDER_NAME · ${languageName(it)}" } ?: PROVIDER_NAME
    override var lang = when (fixedLanguage) {
        "ta" -> "ta"
        "te" -> "te"
        "hi" -> "hi"
        "ml" -> "ml"
        "kn" -> "kn"
        else -> "en"
    }

    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)
    override val hasQuickSearch = false

    override val mainPage: List<MainPageData>
        get() {
            val languageForums = listOf(
                "ta" to ("Tamil" to "forum/9-tamil-language"),
                "te" to ("Telugu" to "forum/22-telugu-language"),
                "hi" to ("Hindi" to "forum/56-hindi-language"),
                "ml" to ("Malayalam" to "forum/34-malayalam-language"),
                "kn" to ("Kannada" to "forum/67-kannada-language"),
                "multi" to ("Multilingual / Hollywood" to "forum/17-hollywood-movies-in-multi-audios"),
            )
            val selected = fixedLanguage ?: TollyPreferences.selected()
            val visible = languageForums.filter { selected == "all" || it.first == selected }
            return listOf(MainPageData("Latest Releases", "home", false)) +
                visible.map { (code, pair) -> MainPageData(pair.first, "language:$code:${pair.second}", false) }
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

        val (html, base) = fetchSection(request.data)
        val items = if (request.data.startsWith("language:")) {
            aggregateLanguageForums(request.data, html, base)
        } else {
            Parser.parseListing(html, base, forumPage = request.data != "home")
        }
        return newHomePageResponse(request.name, toResults(items), false)
    }

    /** Aggregate every child forum discovered on a language landing page. */
    private suspend fun aggregateLanguageForums(
        data: String,
        landingHtml: String,
        base: String,
    ): List<ListingItem> {
        val parentPath = data.substringAfter(':').substringAfter(':')
        val parentUrl = sectionUrl(base, parentPath)
        val subforums = Parser.parseSubforums(landingHtml, base)
        val urls = (listOf("Movies" to parentUrl) + subforums)
            .distinctBy { it.second }
        val pages = coroutineScope {
            urls.map { (_, forumUrl) ->
                async {
                    val html = if (forumUrl == parentUrl) landingHtml else {
                        SUBFORUM_GATE.withPermit {
                            withTimeoutOrNull(8_000L) { Cloudflare.html(forumUrl, timeout = 8) }
                        }
                    }
                    html?.let { Parser.parseListing(it, base, forumPage = true) }.orEmpty()
                }
            }.awaitAll()
        }
        val collected = LinkedHashMap<String, ListingItem>()
        pages.flatten().forEach { item ->
            collected.putIfAbsent(Parser.topicId(item.url), item)
        }
        return collected.values.toList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val official = officialSearch(query)
        if (official.isNotEmpty()) return official

        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        if (tokens.isEmpty()) return emptyList()

        return buildCatalog()
            .asSequence()
            .filter { (title, url) ->
                val haystack = "$title $url".lowercase()
                tokens.all { haystack.contains(it) }
            }
            .distinctBy { it.second }
            .take(60)
            .map { (title, url) ->
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.year = Parser.yearOf(title)
                }
            }
            .toList()
    }

    /** Search only the official 1TamilMV site through the Cloudflare client. */
    private suspend fun officialSearch(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.length < 2) return emptyList()

        val base = runCatching { Domains.base() }.getOrElse { Domains.candidates.first() }
        val url = "$base/search/?q=${java.net.URLEncoder.encode(clean, "UTF-8")}"
        val items = runCatching {
            Parser.parseSearch(Cloudflare.html(url), base)
        }.getOrDefault(emptyList())

        return items.distinctBy { it.url }.take(60).map { item ->
            newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                this.year = Parser.yearOf(item.title)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val (html, base) = fetchTopic(url)
        val fixed = Domains.rewrite(url, base)
        val topic = Parser.parseTopic(html, fixed, base)
        val topicTitle = topic.title.ifBlank { Parser.titleFromUrl(fixed) }
        val topicYear = Parser.yearOf(topicTitle)

        val meta = withTimeoutOrNull(DETAILS_TIMEOUT_MS) { Tmdb.details(topicTitle, topicYear) }

        val displayName = when {
            meta == null -> topicTitle
            meta.year != null -> "${meta.title} (${meta.year})"
            else -> meta.title
        }

        val releasePlot = topic.releases.joinToString("\n") { release ->
            listOfNotNull(release.quality, release.size, release.title.take(90))
                .distinct()
                .joinToString("  •  ")
        }

        return newMovieLoadResponse(displayName, fixed, TvType.Movie, fixed) {
            this.posterUrl = topic.poster ?: meta?.poster
            this.backgroundPosterUrl = meta?.backdrop ?: topic.poster
            this.plot = meta?.overview ?: releasePlot.ifBlank { null }
            this.year = meta?.year ?: topicYear
            this.duration = meta?.runtime
            this.tags = meta?.genres?.takeIf { it.isNotEmpty() }
                ?: topic.releases.mapNotNull { it.quality }.distinct()
            this.score = meta?.rating?.let { Score.from10(it) }
            this.actors = meta?.cast
                ?.takeIf { it.isNotEmpty() }
                ?.map { ActorData(Actor(it.name, it.image), ActorRole.Main) }
            meta?.imdbId?.let { addImdbId(it) }
            meta?.trailerKey?.let { addTrailer("https://www.youtube.com/watch?v=$it") }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val (html, base) = fetchTopic(data)
        val fixed = Domains.rewrite(data, base)
        val topic = Parser.parseTopic(html, fixed, base)
        var emitted = false

        for (release in topic.releases) {
            val quality = qualityInt(release.quality)
            val label = linkLabel(release)

            val magnet = release.magnet
            if (!magnet.isNullOrBlank()) {
                callback(
                    newExtractorLink(PROVIDER_NAME, label, magnet, ExtractorLinkType.MAGNET) {
                        this.quality = quality
                        this.referer = fixed
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    }
                )
                emitted = true
            }

            for (direct in release.direct) {
                if (loadExtractor(direct, fixed, subtitleCallback, callback)) emitted = true
            }
        }

        return emitted
    }

    /**
     * Listing cards have no images, so the first few per row are matched against TMDB
     * for a poster. Bounded on purpose: the row must not stall on a slow TMDB.
     */
    private suspend fun toResults(items: List<ListingItem>): List<SearchResponse> {
        if (items.isEmpty()) return emptyList()

        val head = items.take(POSTER_LOOKUPS)
        val posters = coroutineScope {
            val gate = Semaphore(POSTER_LOOKUPS)
            head.map { item ->
                async {
                    gate.withPermit {
                        val poster = withTimeoutOrNull(POSTER_TIMEOUT_MS) {
                            Tmdb.poster(item.title, Parser.yearOf(item.title))
                        }
                        item to poster
                    }
                }
            }.awaitAll().toMap()
        }

        return items.map { item ->
            newMovieSearchResponse(item.title, item.url, TvType.Movie) {
            this.posterUrl = posters[item]
                this.year = Parser.yearOf(item.title)
            }
        }
    }

    /** Fallback for titles missing from TMDB: scrape the banner from the topic post. */
    private suspend fun scrapeForumPoster(url: String): String? {
        return runCatching {
            val (html, base) = fetchTopic(url)
            val fixed = Domains.rewrite(url, base)
            Parser.parseTopic(html, fixed, base).poster
        }.getOrNull()
    }

    private fun linkLabel(release: Release): String {
        val quality = release.quality ?: "Rip"
        val kind = if (!release.magnet.isNullOrBlank()) "Magnet" else "Torrent"
        val size = release.size
        return if (size != null) "$quality • $size • $kind" else "$quality • $kind"
    }

    private fun qualityInt(quality: String?): Int = when (quality?.lowercase()) {
        "2160p" -> Qualities.P2160.value
        "1080p" -> Qualities.P1080.value
        "720p" -> Qualities.P720.value
        "480p" -> Qualities.P480.value
        "360p" -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun languageName(code: String): String = when (code) {
        "ta" -> "Tamil"
        "te" -> "Telugu"
        "hi" -> "Hindi"
        "ml" -> "Malayalam"
        "kn" -> "Kannada"
        "multi" -> "Multilingual"
        else -> code
    }

    private fun sectionUrl(base: String, data: String): String {
        return if (data == "home") {
            "$base/"
        } else {
            val slug = data.substringAfter("forum/").trim('/')
            "$base/index.php?/forums/forum/$slug/"
        }
    }

    private suspend fun fetchSection(data: String): Pair<String, String> {
        var base = Domains.base()
        return try {
            Cloudflare.html(sectionUrl(base, data)) to base
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (first: Exception) {
            Domains.invalidate()
            base = Domains.base(force = true)
            Cloudflare.html(sectionUrl(base, data)) to base
        }
    }

    private suspend fun fetchTopic(url: String): Pair<String, String> {
        var base = Domains.base()
        return try {
            Cloudflare.html(Domains.rewrite(url, base)) to base
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (first: Exception) {
            Domains.invalidate()
            base = Domains.base(force = true)
            Cloudflare.html(Domains.rewrite(url, base)) to base
        }
    }

    /** Home + every language forum, used for search (the site's own search needs a CSRF token). */
    private suspend fun buildCatalog(): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        val cached = catalog
        if (cached != null && now - catalogAt < CATALOG_TTL_MS) return cached

        val merged = LinkedHashMap<String, String>()
        val sections = listOf("home") + listOf(
            "forum/9-tamil-language",
            "forum/22-telugu-language",
            "forum/56-hindi-language",
            "forum/34-malayalam-language",
            "forum/67-kannada-language",
            "forum/17-hollywood-movies-in-multi-audios",
        )

        for (section in sections) {
            val page = runCatching { fetchSection(section) }.getOrNull() ?: continue
            val items = runCatching {
                Parser.parseListing(page.first, page.second, forumPage = section != "home")
            }.getOrNull() ?: continue

            for (item in items) merged.putIfAbsent(item.url, item.title)
        }

        val result = merged.map { (url, title) -> title to url }
        if (result.isNotEmpty()) {
            catalog = result
            catalogAt = now
        }
        return result
    }
}
