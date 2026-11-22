package com.tollystream

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.CancellationException

/**
 * Single Cloudflare bypass layer used by every request in this plugin.
 *
 * [CloudflareKiller] is an OkHttp interceptor that detects a Cloudflare challenge
 * (server: cloudflare + 403/503), opens a WebView to solve it and replays the request
 * with the resulting cookies. It keeps cookies per host, so exactly one instance must
 * exist for the whole plugin (its constructor wipes the cookie jar).
 */
object Cloudflare {
    @Volatile
    private var killer: CloudflareKiller? = null

    private fun interceptor(): CloudflareKiller =
        killer ?: CloudflareKiller().also { killer = it }

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Upgrade-Insecure-Requests" to "1",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
    )

    suspend fun get(url: String, referer: String? = null, timeout: Long = 15): NiceResponse {
        return app.get(
            url,
            headers = browserHeaders,
            referer = referer,
            interceptor = interceptor(),
            timeout = timeout,
        )
    }

    /** CloudflareKiller handles challenges; avoid retrying cancellation/timeouts here. */
    suspend fun html(url: String, referer: String? = null, timeout: Long = 15): String {
        return try {
            get(url, referer, timeout).text
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (first: Exception) {
            throw first
        }
    }
}
