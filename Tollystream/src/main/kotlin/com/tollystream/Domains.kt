package com.tollystream

import java.util.concurrent.atomic.AtomicLong

/**
 * 1TamilMV rotates domains constantly and most mirrors are dead at any given time.
 * We probe the known mirrors, keep whichever one actually serves the forum and,
 * if the site advertises a newer "official website" host inside the page, use that.
 */
object Domains {
    private const val TTL_MS = 30 * 60 * 1000L

    val candidates = listOf(
        "https://www.1tamilmv.ceo",
        "https://www.1tamilmv.rocks",
        "https://www.1tamilmv.fi",
        "https://www.1tamilmv.io",
        "https://www.1tamilmv.cyou",
        "https://1tamilmv.ceo",
    )

    @Volatile
    private var cached: String? = null
    private val cachedAt = AtomicLong(0L)

    fun invalidate() {
        cached = null
        cachedAt.set(0L)
    }

    suspend fun base(force: Boolean = false): String {
        val now = System.currentTimeMillis()
        val current = cached
        if (!force && current != null && now - cachedAt.get() < TTL_MS) return current

        for (candidate in candidates) {
            val html = try {
                Cloudflare.html("$candidate/")
            } catch (e: Exception) {
                null
            } ?: continue

            if (!html.contains("1tamilmv", ignoreCase = true)) continue

            val host = hostFromPage(html) ?: candidate
            cached = host
            cachedAt.set(now)
            return host
        }

        val fallback = current ?: candidates.first()
        cached = fallback
        cachedAt.set(now)
        return fallback
    }

    /** Prefer the host the site itself links to (canonical/current domain). */
    private fun hostFromPage(html: String): String? {
        val topicLink = Regex(
            "https?://([A-Za-z0-9.\\-]*1tamilmv[A-Za-z0-9.\\-]*)/index\\.php\\?/forums/topic/",
            RegexOption.IGNORE_CASE
        ).find(html)
        val anyLink = Regex(
            "https?://([A-Za-z0-9.\\-]*1tamilmv[A-Za-z0-9.\\-]*)",
            RegexOption.IGNORE_CASE
        ).find(html)
        val host = topicLink?.groupValues?.get(1) ?: anyLink?.groupValues?.get(1) ?: return null
        return "https://" + host.lowercase()
    }

    fun absolute(base: String, href: String): String {
        val value = href.trim()
        return when {
            value.isBlank() -> base
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> base.trimEnd('/') + value
            else -> base.trimEnd('/') + "/" + value
        }
    }

    /** Point any stored 1TamilMV URL at the currently resolved live host. */
    fun rewrite(url: String, base: String): String {
        return url.replace(
            Regex("https?://[A-Za-z0-9.\\-]*1tamilmv[A-Za-z0-9.\\-]*", RegexOption.IGNORE_CASE),
            base.trimEnd('/')
        )
    }
}
