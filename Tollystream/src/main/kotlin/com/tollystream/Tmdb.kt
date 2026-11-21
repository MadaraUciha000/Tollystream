package com.tollystream

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class TmdbCast(
    val name: String,
    val image: String?,
)

data class TmdbDetails(
    val id: Int,
    val title: String,
    val year: Int?,
    val poster: String?,
    val backdrop: String?,
    val overview: String?,
    val rating: Double?,
    val votes: Int,
    val runtime: Int?,
    val genres: List<String>,
    val cast: List<TmdbCast>,
    val imdbId: String?,
    val trailerKey: String?,
)

/**
 * Minimal TMDB client: search by (cleaned) title + year, then fetch the full movie
 * record for poster/backdrop/plot/genres/runtime/cast.
 *
 * `api.tmdb.org` and `api.themoviedb.org` are the same API; the first one is tried
 * first because some networks resolve/allow it when the canonical host does not.
 */
object Tmdb {
    private val API_BASES = listOf("https://api.tmdb.org/3", "https://api.themoviedb.org/3")
    private const val API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"

    private const val POSTER_BASE = "https://image.tmdb.org/t/p/w500"
    private const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"
    private const val PROFILE_BASE = "https://image.tmdb.org/t/p/w185"

    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

    private data class Hit(
        val id: Int,
        val title: String,
        val year: Int?,
        val poster: String?,
        val backdrop: String?,
        val overview: String?,
        val rating: Double?,
    )

    private class Entry(val at: Long, val hit: Hit)

    private val searchCache = ConcurrentHashMap<String, Entry>()
    private val detailsCache = ConcurrentHashMap<Int, TmdbDetails>()

    suspend fun poster(title: String, year: Int?): String? = search(title, year)?.poster

    suspend fun details(title: String, year: Int?): TmdbDetails? {
        val hit = search(title, year) ?: return null
        detailsCache[hit.id]?.let { return it }

        val json = get("/movie/${hit.id}", "append_to_response=credits,external_ids,videos")
        val details = if (json != null) parseDetails(json, hit) else hit.toDetails()
        detailsCache[hit.id] = details
        return details
    }

    private fun Hit.toDetails() = TmdbDetails(
        id = id,
        title = title,
        year = year,
        poster = poster,
        backdrop = backdrop,
        overview = overview,
        rating = rating,
        votes = 0,
        runtime = null,
        genres = emptyList(),
        cast = emptyList(),
        imdbId = null,
        trailerKey = null,
    )

    private fun parseDetails(json: JSONObject, hit: Hit): TmdbDetails {
        val genres = json.optJSONArray("genres")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
            }
        } ?: emptyList()

        val cast = json.optJSONObject("credits")?.optJSONArray("cast")?.let { array ->
            (0 until minOf(array.length(), 12)).mapNotNull { index ->
                val person = array.optJSONObject(index) ?: return@mapNotNull null
                val name = person.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TmdbCast(name, image(person.optString("profile_path"), PROFILE_BASE))
            }
        } ?: emptyList()

        val trailerKey = json.optJSONObject("videos")?.optJSONArray("results")?.let { array ->
            var teaser: String? = null
            var any: String? = null
            for (index in 0 until array.length()) {
                val video = array.optJSONObject(index) ?: continue
                if (!video.optString("site").equals("YouTube", ignoreCase = true)) continue
                val key = video.optString("key").takeIf { it.isNotBlank() } ?: continue
                if (any == null) any = key
                when (video.optString("type").lowercase(Locale.US)) {
                    "trailer" -> return@let key
                    "teaser" -> if (teaser == null) teaser = key
                }
            }
            teaser ?: any
        }

        return TmdbDetails(
            id = hit.id,
            title = json.optString("title").ifBlank { hit.title },
            year = yearOf(json.optString("release_date")) ?: hit.year,
            poster = image(json.optString("poster_path"), POSTER_BASE) ?: hit.poster,
            backdrop = image(json.optString("backdrop_path"), BACKDROP_BASE) ?: hit.backdrop,
            overview = json.optString("overview").takeIf { it.isNotBlank() } ?: hit.overview,
            rating = json.optDouble("vote_average", 0.0).takeIf { it > 0.0 } ?: hit.rating,
            votes = json.optInt("vote_count", 0),
            runtime = json.optInt("runtime", 0).takeIf { it > 0 },
            genres = genres,
            cast = cast,
            imdbId = json.optString("imdb_id").takeIf { it.startsWith("tt") },
            trailerKey = trailerKey,
        )
    }

    private suspend fun search(title: String, year: Int?): Hit? {
        val query = cleanQuery(title)
        if (query.length < 2) return null

        val key = "$query|${year ?: 0}"
        val cached = searchCache[key]
        if (cached != null && System.currentTimeMillis() - cached.at < CACHE_TTL_MS) return cached.hit

        var hit = searchOnce(query, year) ?: searchOnce(query, null)
        if (hit == null) {
            val tokens = query.split(" ")
            for (size in intArrayOf(5, 4, 3, 2)) {
                if (tokens.size <= size) continue
                val shorter = tokens.take(size).joinToString(" ")
                hit = searchOnce(shorter, year) ?: searchOnce(shorter, null)
                if (hit != null) break
            }
        }

        if (hit != null) searchCache[key] = Entry(System.currentTimeMillis(), hit)
        return hit
    }

    private suspend fun searchOnce(query: String, year: Int?): Hit? {
        val extra = buildString {
            append("query=").append(URLEncoder.encode(query, "UTF-8"))
            if (year != null) append("&year=").append(year)
        }
        val json = get("/search/movie", extra) ?: return null
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null

        val wanted = normalize(query)
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE

        for (index in 0 until minOf(results.length(), 8)) {
            val candidate = results.optJSONObject(index) ?: continue
            val releaseYear = yearOf(candidate.optString("release_date"))
            var score = similarity(wanted, normalize(candidate.optString("title")))
            if (year != null && releaseYear == year) score += 40
            if (candidate.optString("poster_path").isBlank()) score -= 15
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }

        val chosen = best ?: return null
        return Hit(
            id = chosen.optInt("id"),
            title = chosen.optString("title"),
            year = yearOf(chosen.optString("release_date")),
            poster = image(chosen.optString("poster_path"), POSTER_BASE),
            backdrop = image(chosen.optString("backdrop_path"), BACKDROP_BASE),
            overview = chosen.optString("overview").takeIf { it.isNotBlank() },
            rating = chosen.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
        )
    }

    private suspend fun get(path: String, extra: String = ""): JSONObject? {
        val params = buildString {
            append("api_key=").append(API_KEY)
            append("&language=en-US&include_adult=false")
            if (extra.isNotBlank()) append('&').append(extra)
        }

        for (base in API_BASES) {
            try {
                val response = app.get("$base$path?$params", timeout = 15)
                return JSONObject(response.text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // try the next host
            }
        }
        return null
    }

    /** Strip language/quality/size noise so "O.M: Operation Mole (2026) Tamil HQ PreDVD" -> "O M Operation Mole". */
    fun cleanQuery(raw: String): String {
        var value = raw
        value = value.replace(Regex("\\(\\s*(19|20)\\d{2}\\s*\\)"), " ")
        value = value.replace(Regex("\\([^)]*\\)"), " ")
        value = value.replace(Regex("\\[[^\\]]*]"), " ")
        value = value.replace(
            Regex(
                "(?i)\\b(2160p|1080p|720p|480p|360p|4k|hq|predvd|pre[- ]?dvd|web[- ]?dl|webrip|" +
                    "hdrip|hdtc|hdcam|camrip|dvdrip|brrip|bluray|x264|x265|h264|h265|hevc|avc|aac|ac3|" +
                    "eac3|ddp|dd|esubs?|proper|repack|hd|full|true)\\b"
            ),
            " "
        )
        value = value.replace(
            Regex(
                "(?i)\\b(tamil|telugu|hindi|malayalam|kannada|english|tam|tel|hin|mal|kan|eng|" +
                    "dubbed|dual|audio|audios?|auds?|original|org|clean|quality)\\b"
            ),
            " "
        )
        value = value.replace(Regex("(?i)\\b(k?tvhd|hdtv|tv|hdr|sdr)\\b"), " ")
        value = value.replace(Regex("(?i)\\b(ddp?|dd|aac|ac3|eac3)\\s?\\d(\\.\\d)?\\b"), " ")
        value = value.replace(Regex("(?i)\\b\\d{2,4}\\s?kbps\\b"), " ")
        value = value.replace(Regex("(?i)\\b\\d+(\\.\\d+)?\\s?(gb|mb)\\b"), " ")
        value = value.replace(Regex("[^A-Za-z0-9]+"), " ")
        return value.replace(Regex("\\s+"), " ").trim()
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun similarity(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        if (a == b) return 100
        if (b.startsWith(a) || a.startsWith(b)) return 80
        if (b.contains(a) || a.contains(b)) return 60
        val aTokens = a.split(" ").toSet()
        val bTokens = b.split(" ").toSet()
        return aTokens.intersect(bTokens).size * 10
    }

    private fun yearOf(date: String): Int? =
        date.take(4).toIntOrNull()?.takeIf { it in 1900..2100 }

    private fun image(path: String, base: String): String? =
        if (path.isBlank()) null else "$base$path"
}
