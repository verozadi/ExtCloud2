package com.dramaid

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

class DramaIdProvider : MainAPI() {
    override var mainUrl = "https://drama-id.com"
    override var name = "DramaID"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Update Terbaru",
        "$mainUrl/negara/korea-selatan/page/%d/" to "Drama Korea",
        "$mainUrl/negara/china/page/%d/" to "Drama China",
        "$mainUrl/negara/japan/page/%d/" to "Drama Jepang",
        "$mainUrl/negara/thailand/page/%d/" to "Drama Thailand",
        "$mainUrl/status-drama/ongoing/page/%d/" to "Ongoing",
        "$mainUrl/status-drama/complete/page/%d/" to "Tamat",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = app.get(url, referer = "$mainUrl/").document
        val items = document.toSearchResults()
        val hasNext = document.select(".pagination a[href]:matchesOwn((?i)Next), .pagination a[href*='/page/${page + 1}/']").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", referer = "$mainUrl/").document
        return document.toSearchResults()
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl, referer = "$mainUrl/").document

        val title = detailValue(document, "Judul")
            ?: document.selectFirst("h1.single-title, h1.single_h2, meta[property=og:title], title")
                ?.let { it.attr("content").ifBlank { it.text() } }
                ?.cleanTitle()
                ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst(".thumbnail_single img, meta[property=og:image], img.wp-post-image")
            ?.imageUrl()
        val plot = document.select("#sinopsis p, .synopsis p")
            .joinToString("\n") { it.text().trim() }
            .trim()
            .ifBlank { null }
        val year = detailValue(document, "Tahun")?.let(::extractYear)
        val rating = detailValue(document, "Skor")?.toScore()
        val tags = document.select("#informasi li:has(strong:matchesOwn((?i)Genres)) a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val status = getStatus(detailValue(document, "Status"))
        val duration = detailValue(document, "Durasi")?.durationToMinutes()
        val episodes = document.select(".daftar-episode li a[href*='episode='], .episode-list li a[href*='episode=']")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
        val recommendations = document.select("article")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }
        val type = if (episodes.size <= 1 && detailValue(document, "Tipe")?.contains("movie", true) == true) {
            TvType.Movie
        } else {
            TvType.AsianDrama
        }

        return if (episodes.size > 1 || type != TvType.Movie) {
            newTvSeriesLoadResponse(title, fixedUrl, type, episodes.ifEmpty { listOf(newEpisode(fixedUrl)) }) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
                rating?.let { score = Score.from10(it) }
                showStatus = status
                duration?.let { this.duration = it }
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, fixedUrl, TvType.Movie, episodes.firstOrNull()?.data ?: fixedUrl) {
                posterUrl = poster
                backgroundPosterUrl = poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
                rating?.let { score = Score.from10(it) }
                duration?.let { this.duration = it }
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedUrl = fixUrl(data)
        val document = app.get(fixedUrl, referer = "$mainUrl/").document
        val emitted = linkedSetOf<String>()
        var handled = false

        document.select(".streaming_load[data]").forEach { element ->
            val decodedHtml = decodeBase64(element.attr("data")) ?: return@forEach
            Jsoup.parse(decodedHtml).select("iframe[src], iframe[data-src]").forEach { iframe ->
                val iframeUrl = normalizeUrl(iframe.attr("src").ifBlank { iframe.attr("data-src") }, fixedUrl) ?: return@forEach
                handled = true
                runCatching { loadExtractor(iframeUrl, fixedUrl, subtitleCallback, callback) }
                decodeBerkasDriveId(iframeUrl)?.let { resolver ->
                    runCatching { loadExtractor(resolver, fixedUrl, subtitleCallback, callback) }
                }
            }
        }

        document.select(".resolusi-list li[data], .server-list li[data]").forEach { element ->
            val decodedJson = decodeBase64(element.attr("data")) ?: return@forEach
            val resolution = tryParseJson<ResolutionData>(decodedJson)
            val servers = resolution?.links.orEmpty().ifEmpty {
                listOfNotNull(tryParseJson<ServerData>(decodedJson))
            }
            val qualityLabel = resolution?.resolution ?: element.text().trim()

            resolution?.subtitle_url
                ?.takeIf { it.isNotBlank() }
                ?.let { normalizeUrl(it, fixedUrl) }
                ?.let { subtitleCallback(newSubtitleFile("Indonesian", it)) }

            servers.forEach { server ->
                val serverUrl = normalizeUrl(server.url.orEmpty(), fixedUrl) ?: return@forEach
                handled = true
                runCatching { loadExtractor(serverUrl, fixedUrl, subtitleCallback, callback) }
                emitDirectIfMedia(serverUrl, qualityLabel.ifBlank { server.urutan_text ?: "Server" }, fixedUrl, qualityLabel, emitted, callback)
            }
        }

        document.select(".download a[href], .link_download a[href]").forEach { link ->
            val downloadUrl = normalizeUrl(link.attr("href"), fixedUrl) ?: return@forEach
            val label = link.closest(".link_download")?.selectFirst("strong")?.text()?.trim()
                ?: link.attr("title").ifBlank { link.text() }.trim()
            handled = true
            runCatching { loadExtractor(downloadUrl, fixedUrl, subtitleCallback, callback) }
            emitDirectIfMedia(downloadUrl, label.ifBlank { "Download" }, fixedUrl, label, emitted, callback)
        }

        return handled || emitted.isNotEmpty()
    }

    private fun Document.toSearchResults(): List<SearchResponse> {
        return select("article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("h3.title_post a[href], .thumbnail a[href], a[href*='/nonton-']")
            ?: if (tagName() == "a") this else return null
        val href = normalizeUrl(link.attr("href"), mainUrl) ?: return null
        if (!href.contains("/nonton-", true)) return null

        val title = listOf(
            link.attr("title"),
            selectFirst("h3.title_post a")?.text(),
            selectFirst("img[alt]")?.attr("alt"),
            link.text(),
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?: return null

        val poster = selectFirst(".thumbnail img, img")?.imageUrl()
        val type = if (text().contains("Episode:", true)) TvType.AsianDrama else TvType.Movie

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                posterUrl = poster
            }
        }
    }

    private fun Element.toEpisode(): Episode? {
        val href = normalizeUrl(attr("href"), mainUrl) ?: return null
        val title = attr("title").ifBlank { selectFirst(".title_episode, .title_episode_2")?.text() ?: text() }
            .replace(Regex("""\s+"""), " ")
            .trim()
        val episodeNumber = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(title.ifBlank { href })
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""[?&]episode=(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        return newEpisode(href) {
            name = episodeNumber?.let { "Episode $it" } ?: title.ifBlank { "Episode" }
            episode = episodeNumber
        }
    }

    private suspend fun emitDirectIfMedia(
        url: String,
        label: String,
        refererUrl: String,
        qualityLabel: String?,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!isMediaUrl(url) || isResolverUrl(url)) return
        if (!emitted.add(url)) return

        callback(
            newExtractorLink(
                source = name,
                name = "$name ${label.cleanLabel()}",
                url = url,
                type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                referer = refererUrl
                quality = qualityFromLabel(qualityLabel ?: label)
                headers = mapOf(
                    "Referer" to refererUrl,
                    "Range" to "bytes=0-",
                )
            }
        )
    }

    private fun isMediaUrl(url: String): Boolean {
        return Regex("""(?i)\.(mp4|m3u8)(?:$|[?#&])""").containsMatchIn(url)
    }

    private fun isResolverUrl(url: String): Boolean {
        return url.contains("stordl.halahgan.com", true) || url.contains("dl.berkasdrive.com/streaming", true)
    }

    private fun detailValue(document: Document, label: String): String? {
        return document.select("#informasi li, .info li")
            .firstOrNull { item ->
                item.selectFirst("strong")?.text()
                    ?.replace(":", "")
                    ?.trim()
                    ?.equals(label, true) == true
            }
            ?.let { item ->
                val clone = item.clone()
                clone.select("strong").remove()
                clone.text().trim().ifBlank { null }
            }
    }

    private fun Element.imageUrl(): String? {
        return listOf(
            attr("abs:content"),
            attr("abs:data-src"),
            attr("abs:data-lazy-src"),
            attr("abs:srcset").substringBefore(" "),
            attr("abs:src"),
            attr("content"),
            attr("data-src"),
            attr("data-lazy-src"),
            attr("srcset").substringBefore(" "),
            attr("src"),
        ).firstOrNull { it.isNotBlank() }?.let(::fixUrl)
    }

    private fun pageUrl(pattern: String, page: Int): String {
        if (page > 1) return pattern.format(page)
        return pattern
            .replace("/page/%d/", "/")
            .replace("page/%d/", "")
            .replace("%d", "1")
    }

    private fun normalizeUrl(raw: String, baseUrl: String): String? {
        val clean = raw.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .takeIf { it.isNotBlank() && !it.startsWith("javascript:", true) && !it.startsWith("data:", true) }
            ?: return null

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()
        }
    }

    private fun decodeBerkasDriveId(url: String): String? {
        if (!url.contains("dl.berkasdrive.com/streaming", true)) return null
        return url.substringAfter("?", "")
            .split("&")
            .firstOrNull { it.substringBefore("=") == "id" }
            ?.substringAfter("=", "")
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?.let(::decodeBase64)
            ?.let { normalizeUrl(it, mainUrl) }
    }

    private fun decodeBase64(value: String): String? {
        val clean = value.trim().replace("\\s".toRegex(), "")
        if (clean.isBlank()) return null
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded)) }.getOrNull()
    }

    private fun getStatus(value: String?): ShowStatus? {
        return when {
            value == null -> null
            value.contains("ongoing", true) -> ShowStatus.Ongoing
            value.contains("complete", true) || value.contains("tamat", true) -> ShowStatus.Completed
            else -> null
        }
    }

    private fun extractYear(value: String): Int? {
        return Regex("""(19|20)\d{2}""").find(value)?.value?.toIntOrNull()
    }

    private fun String.toScore(): Double? {
        return Regex("""\d+(?:\.\d+)?""").find(this)?.value?.toDoubleOrNull()
    }

    private fun String.durationToMinutes(): Int? {
        val hours = Regex("""(\d+)\s*hr""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return (hours * 60 + minutes).takeIf { it > 0 }
    }

    private fun qualityFromLabel(value: String): Int {
        return Regex("""\b(2160|1440|1080|720|480|360|240)\b""", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun String.cleanTitle(): String {
        return Jsoup.parse(this).text()
            .replace(Regex("""(?i)^Nonton\s+(?:Drakor|Drama)\s+"""), "")
            .replace(Regex("""(?i)\s+Subtitle\s+Indonesia.*$"""), "")
            .replace(Regex("""(?i)\s+Sub\s+Indo.*$"""), "")
            .replace(Regex("""(?i)\s+Episode\s+\d+.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanLabel(): String {
        return replace(Regex("""\s+"""), " ").trim().ifBlank { "Server" }
    }

    data class ResolutionData(
        val resolution: String? = null,
        val subtitle_url: String? = null,
        val links: List<ServerData>? = null,
    )

    data class ServerData(
        val url: String? = null,
        val mode: String? = null,
        val urutan_text: String? = null,
    )
}
