package com.tollystream

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
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

class TollystreamProvider : MainAPI() {
    companion object {
        const val PROVIDER_NAME = "Tollystream"
        private const val CATALOG_TTL_MS = 10 * 60 * 1000L

        @Volatile
        private var catalog: List<Pair<String, String>>? = null

        @Volatile
        private var catalogAt = 0L
    }

    override var mainUrl = Domains.candidates.first()
    override var name = PROVIDER_NAME
    override var lang = "ta"

    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)
    override val hasQuickSearch = false

    override val mainPage = listOf(
        MainPageData("Latest Releases", "home", false),
        MainPageData("Tamil", "forum/9-tamil-language", false),
        MainPageData("Telugu", "forum/22-telugu-language", false),
        MainPageData("Hindi", "forum/56-hindi-language", false),
        MainPageData("Malayalam", "forum/34-malayalam-language", false),
        MainPageData("Kannada", "forum/67-kannada-language", false),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), false)

        val (html, base) = fetchSection(request.data)
        val items = Parser.parseListing(html, base, forumPage = request.data != "home")
        val results = items.map { newMovieSearchResponse(it.title, it.url, TvType.Movie) }
        return newHomePageResponse(request.name, results, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
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
            .map { newMovieSearchResponse(it.first, it.second, TvType.Movie) }
            .toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val (html, base) = fetchTopic(url)
        val fixed = Domains.rewrite(url, base)
        val topic = Parser.parseTopic(html, fixed, base)
        val title = topic.title.ifBlank { Parser.titleFromUrl(fixed) }

        return newMovieLoadResponse(title, fixed, TvType.Movie, fixed) {
            this.posterUrl = topic.poster
            this.year = Parser.yearOf(title)
            this.plot = topic.releases.joinToString("\n") { release ->
                listOfNotNull(release.quality, release.size, release.title.take(90))
                    .distinct()
                    .joinToString("  •  ")
            }.ifBlank { null }
            this.tags = topic.releases.mapNotNull { it.quality }.distinct()
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
            val torrent = release.torrent
            when {
                !magnet.isNullOrBlank() -> {
                    callback(
                        newExtractorLink(PROVIDER_NAME, label, magnet, ExtractorLinkType.MAGNET) {
                            this.quality = quality
                            this.referer = fixed
                            this.headers = mapOf("User-Agent" to USER_AGENT)
                        }
                    )
                    emitted = true
                }

                !torrent.isNullOrBlank() -> {
                    callback(
                        newExtractorLink(PROVIDER_NAME, label, torrent, ExtractorLinkType.TORRENT) {
                            this.quality = quality
                            this.referer = fixed
                            this.headers = mapOf("User-Agent" to USER_AGENT)
                        }
                    )
                    emitted = true
                }
            }

            for (direct in release.direct) {
                if (loadExtractor(direct, fixed, subtitleCallback, callback)) emitted = true
            }
        }

        return emitted
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
        val sections = listOf("home") + mainPage.map { it.data }.filter { it != "home" }

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
