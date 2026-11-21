package com.tollystream

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.Locale

data class Release(
    val title: String,
    val quality: String?,
    val size: String?,
    val magnet: String?,
    val torrent: String?,
    val direct: List<String> = emptyList(),
)

data class Topic(
    val url: String,
    val title: String,
    val poster: String?,
    val releases: List<Release>,
)

data class ListingItem(
    val url: String,
    val title: String,
)

object Parser {
    /** Short provider code used by Tollystream integrations. */
    const val SHORT_CODE = "tolly"

    private val QUALITY_RE = Regex("\\b(2160p|1080p|720p|480p|360p|4k)\\b", RegexOption.IGNORE_CASE)
    private val SIZE_RE = Regex("\\b(\\d+(?:\\.\\d+)?\\s?(?:GB|MB))\\b", RegexOption.IGNORE_CASE)
    private val YEAR_RE = Regex("\\b(19|20)\\d{2}\\b")

    private val MARKERS = setOf(
        "2160p", "1080p", "720p", "480p", "360p", "4k",
        "hq", "predvd", "predvdrip", "web", "webdl", "web-dl", "webrip",
        "hdrip", "hdtc", "hdcam", "camrip", "dvdrip", "brrip", "bluray",
        "x264", "x265", "h264", "h265", "hevc", "avc", "aac", "ac3", "dd", "ddp",
        "esub", "esubs", "proper", "repack", "hd", "full",
    )

    private val PRETTY = mapOf(
        "hq" to "HQ", "predvd" to "PreDVD", "web" to "WEB", "webdl" to "WEB-DL",
        "webrip" to "WEBRip", "esub" to "ESub", "esubs" to "ESubs", "hevc" to "HEVC",
        "avc" to "AVC", "x264" to "x264", "x265" to "x265", "dd" to "DD", "ddp" to "DDP",
        "hd" to "HD", "dvdrip" to "DVDRip", "brrip" to "BRRip", "bluray" to "BluRay",
        "hdrip" to "HDRip", "hdtc" to "HDTC", "hdcam" to "HDCam", "camrip" to "CAMRip",
    )

    private val DIRECT_HOSTS = listOf(
        "cyberloom", "hubcloud", "hubdrive", "gdflix", "filepress", "gdrive",
        "drive.google", "pixeldrain", "gofile", "workers.dev", "fastdl", "vcloud",
        "nexdrive", "hubcdn", "amazonaws", "dropbox", "mega.nz", "1fichier", "filescdn",
    )

    private val FILE_SUFFIX = Regex("\\.(?:mkv|mp4|avi|m2ts|mov|torrent)$", RegexOption.IGNORE_CASE)
    private val DOMAIN_PREFIX = Regex("^www\\.[A-Za-z0-9.\\-]+\\s*-\\s*", RegexOption.IGNORE_CASE)

    fun topicId(url: String): String {
        val after = url.substringAfterLast("/topic/", "")
        return after.substringBefore('-').substringBefore('/').substringBefore('?')
    }

    fun yearOf(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return YEAR_RE.find(text)?.value?.toIntOrNull()
    }

    /** Home / forum listing -> topics. Titles come from the slug, the listing has no real titles. */
    fun parseListing(html: String, base: String, forumPage: Boolean = false): List<ListingItem> {
        val doc = Jsoup.parse(html, base)
        val anchors: List<Element> = if (forumPage) {
            doc.select("a[href*='forums/topic/']")
        } else {
            doc.select("div.banger-container a[href*='forums/topic/']")
        }

        val seen = LinkedHashMap<String, ListingItem>()
        for (anchor in anchors) {
            val href = anchor.attr("href").trim()
            if (href.isBlank() || href.startsWith("#")) continue

            val absolute = Domains.absolute(base, href)
            if (!absolute.contains("/topic/")) continue
            if (absolute.endsWith("-0/") || absolute.endsWith("-0")) continue

            val id = topicId(absolute)
            if (id.isBlank() || id == "0") continue
            if (seen.containsKey(id)) continue

            val text = anchor.text().trim()
            val title = if (forumPage && text.length in 4..200 && !text.startsWith("[") && !text.contains("http")) {
                cleanTitle(text).ifBlank { titleFromUrl(absolute) }
            } else {
                titleFromUrl(absolute)
            }
            seen[id] = ListingItem(absolute, title)
        }
        return seen.values.toList()
    }

    /** Derive a readable movie name from a topic slug. */
    fun titleFromUrl(url: String): String {
        var slug = if (url.contains("/topic/")) url.substringAfterLast("/topic/") else url
        slug = slug.substringBefore('?').trimEnd('/')

        val dash = slug.indexOf('-')
        if (dash >= 0) slug = slug.substring(dash + 1)

        val tokens = slug.split('-').map { it.trim() }.filter { it.isNotEmpty() }
        val kept = ArrayList<String>()
        for (token in tokens) {
            val lower = token.lowercase()
            if (lower in MARKERS) break
            if (lower.endsWith("gb") || lower.endsWith("mb")) break
            if (kept.size >= 14) break
            kept.add(token)
        }

        val words = ArrayList<String>()
        for (token in kept) {
            if (YEAR_RE.matches(token)) {
                words.add("($token)")
                continue
            }
            words.add(pretty(token))
        }

        val name = words.joinToString(" ").trim()
        return name.ifBlank { slug.replace('-', ' ').trim() }
    }

    private fun pretty(token: String): String {
        val lower = token.lowercase()
        return PRETTY[lower] ?: lower.replaceFirstChar { it.uppercase() }
    }

    /** Topic page -> title, poster and every release found in the first post. */
    fun parseTopic(html: String, url: String, base: String): Topic {
        val doc = Jsoup.parse(html, url)
        val heading = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val title = cleanTitle(heading).ifBlank { titleFromUrl(url) }

        val post = doc.selectFirst("div[data-role=commentContent]")
            ?: doc.selectFirst("div.cPost")
            ?: doc.body()

        val poster = post.select("img.ipsImage, img")
            .mapNotNull { imageUrl(it, base) }
            .firstOrNull { isPoster(it) }

        val releases = ArrayList<Release>()
        val seen = HashSet<String>()
        var lastStrong: String? = null

        for (element in post.select("strong, a")) {
            if (element.tagName().equals("strong", true)) {
                val strongText = cleanFileName(element.text())
                if (strongText.length >= 3) lastStrong = strongText
                continue
            }

            val href = element.attr("href").trim()
            if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript", true)) continue
            val text = element.text().trim()

            when {
                element.attr("data-fileext").equals("torrent", true) || href.contains("attachment.php") -> {
                    val absolute = Domains.absolute(base, href)
                    if (!seen.add(absolute)) continue

                    val fromAnchor = cleanFileName(text)
                    val fallback = lastStrong
                    val name = when {
                        fromAnchor.length >= 3 -> fromAnchor
                        !fallback.isNullOrBlank() -> fallback
                        else -> "Release ${releases.size + 1}"
                    }
                    releases.add(
                        Release(
                            title = name,
                            quality = qualityOf(name),
                            size = sizeOf(name),
                            magnet = null,
                            torrent = absolute,
                        )
                    )
                }

                href.startsWith("magnet:", true) -> {
                    val target = releases.indexOfLast { it.magnet.isNullOrBlank() }
                    if (target >= 0) {
                        val current = releases[target]
                        releases[target] = current.copy(
                            magnet = href,
                            size = current.size ?: sizeFromMagnet(href),
                            quality = current.quality ?: qualityOf(href),
                        )
                    } else {
                        val name = magnetName(href) ?: "Release ${releases.size + 1}"
                        releases.add(
                            Release(
                                title = name,
                                quality = qualityOf(name) ?: qualityOf(href),
                                size = sizeFromMagnet(href),
                                magnet = href,
                                torrent = null,
                            )
                        )
                    }
                }

                isDirect(href, text) -> {
                    val target = releases.indexOfLast { true }
                    if (target < 0) continue
                    val absolute = Domains.absolute(base, href)
                    val current = releases[target]
                    if (current.direct.contains(absolute)) continue
                    releases[target] = current.copy(direct = current.direct + absolute)
                }
            }
        }

        return Topic(url, title, poster, releases)
    }

    /** Forums may put the real image URL in a lazy-loading attribute. */
    private fun imageUrl(image: Element, base: String): String? {
        val source = listOf(
            image.attr("src"),
            image.attr("data-src"),
            image.attr("data-original"),
            image.attr("data-lazy-src"),
            image.attr("data-image"),
            image.attr("srcset").substringBefore(',').substringBefore(' '),
        ).firstOrNull { it.isNotBlank() } ?: return null
        return Domains.absolute(base, source)
    }

    fun cleanTitle(raw: String): String {
        var value = raw.replace(Regex("\\s*-\\s*\\[.*$"), "")
        value = value.replace(Regex("\\s+"), " ")
        return value.trim()
    }

    fun cleanFileName(raw: String): String {
        var value = raw.trim()
        value = value.replace(DOMAIN_PREFIX, "")
        value = value.replace(FILE_SUFFIX, "")
        value = value.replace(FILE_SUFFIX, "")
        return value.trim()
    }

    fun qualityOf(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = QUALITY_RE.find(text) ?: return null
        val quality = match.groupValues[1].lowercase()
        return if (quality == "4k") "2160p" else quality
    }

    fun sizeOf(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return SIZE_RE.find(text)?.groupValues?.get(1)?.replace(" ", "")
    }

    private fun sizeFromMagnet(magnet: String): String? {
        val bytes = Regex("[?&]xl=(\\d+)").find(magnet)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return if (bytes >= 1073741824L) {
            String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
        } else {
            String.format(Locale.US, "%.0f MB", bytes / 1048576.0)
        }
    }

    private fun magnetName(magnet: String): String? {
        val encoded = Regex("[?&]dn=([^&]+)").find(magnet)?.groupValues?.get(1) ?: return null
        return try {
            cleanFileName(URLDecoder.decode(encoded, "UTF-8").replace('+', ' '))
        } catch (e: Exception) {
            cleanFileName(encoded)
        }
    }

    private fun isPoster(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        if (lower.contains("utorrent")) return false
        if (lower.contains("smilies") || lower.contains("emoticon")) return false
        if (lower.contains("attachment.php")) return false
        if (lower.endsWith(".gif")) return false
        return lower.contains("pixelbb") || lower.contains("upload") || lower.contains("image") ||
            lower.contains("poster") || lower.contains("img")
    }

    private fun isDirect(href: String, text: String): Boolean {
        val lower = href.lowercase()
        if (!lower.startsWith("http")) return false
        if (lower.contains("1tamilmv")) return false
        if (lower.contains("pixelbb") || lower.contains("t.me/") || lower.contains("telegram")) return false
        if (lower.contains("twitter.com") || lower.contains("facebook.com")) return false

        if (DIRECT_HOSTS.any { lower.contains(it) }) return true
        return text.contains("DIRECT", ignoreCase = true) || text.contains("watch online", ignoreCase = true)
    }
}
