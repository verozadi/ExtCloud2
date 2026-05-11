package com.nimegami

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

class NimegamiProvider : MainAPI() {
    override var mainUrl = "https://nimegami.id"
    override var name = "Nimegami🥶"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Update Anime",
        "$mainUrl/anime-terbaru-sub-indo/page/%d/" to "Anime Terbaru",
        "$mainUrl/tag/bd/page/%d/" to "BD",
        "$mainUrl/type/movie/page/%d/" to "Movie",
        "$mainUrl/type/ova/page/%d/" to "OVA",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
                .replace("/page/%d/", "/")
                .replace("page/%d/", "")
                .replace("%d", "1")
        } else {
            request.data.format(page)
        }
        val document = app.get(url, referer = "$mainUrl/").document
        val items = document.select(".post-article article, .archive article, article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.select(".pagination a.next, a.nextpostslink, .nav-links a.next").isNotEmpty()
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", referer = "$mainUrl/").document
        return document.select(".archive article, .post-article article, article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = fixUrl(url)
        val document = app.get(fixedUrl, referer = "$mainUrl/").document

        val title = detailValue(document, "Judul")
            ?: document.selectFirst("h1.title, h1.entry-title, h1")
                ?.text()
                ?.cleanTitle()
                ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".single .thumbnail img, .video-streaming img, img.wp-post-image")?.imageUrl()
        val type = getType(detailValue(document, "Type"), fixedUrl)
        val year = detailValue(document, "Musim / Rilis")?.let(::extractYear)
        val rating = detailValue(document, "Rating")
            ?.let { Regex("""(\d+(?:\.\d+)?)""").find(it)?.value?.toDoubleOrNull() }
        val tags = document.select("td.info_a a[href], a[href*='/category/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val plot = document.selectFirst("#Sinopsis p, .content#Sinopsis p, .content p")
            ?.text()
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.ifBlank { null }

        val episodes = document.select(".list_eps_stream li.select-eps[data]")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        val recommendations = document.select(".wrapper-2.post-2 a[href], .post-2 a[href]")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }

        return if (episodes.isNotEmpty() && type != TvType.AnimeMovie) {
            newAnimeLoadResponse(title, fixedUrl, type) {
                posterUrl = poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
                rating?.let { score = Score.from10(it) }
                showStatus = getStatus(document.text())
                this.recommendations = recommendations
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            val data = episodes.firstOrNull()?.data ?: fixedUrl
            newMovieLoadResponse(title, fixedUrl, type, data) {
                posterUrl = poster
                this.year = year
                plot?.let { this.plot = it }
                this.tags = tags
                rating?.let { score = Score.from10(it) }
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
        val emitted = linkedSetOf<String>()
        var handled = false
        val sources = decodeEpisodeSources(data).ifEmpty {
            val document = app.get(data, referer = "$mainUrl/").document
            document.select(".list_eps_stream li.select-eps[data]").firstOrNull()?.attr("data")
                ?.let(::decodeEpisodeSources)
                .orEmpty()
        }

        sources.forEach { source ->
            source.url.orEmpty().forEach { rawUrl ->
                val streamPage = normalizeUrl(rawUrl, mainUrl) ?: return@forEach
                val label = source.format?.ifBlank { null } ?: "Nimegami"
                runCatching {
                    loadExtractor(streamPage, "$mainUrl/", subtitleCallback) { link ->
                        if (emitted.add(link.url)) {
                            handled = true
                            runBlocking {
                                callback(
                                    newExtractorLink(
                                        source = link.source,
                                        name = "$name $label",
                                        url = link.url,
                                        type = link.type
                                    ) {
                                        referer = link.referer
                                        quality = qualityFromName(label)
                                        headers = link.headers + mapOf("Range" to "bytes=0-")
                                        extractorData = link.extractorData
                                    }
                                )
                            }
                        }
                    }
                }

                val direct = extractStreamUrl(streamPage)
                if (direct != null && emitted.add(direct)) {
                    handled = true
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name $label",
                            url = direct,
                            type = if (direct.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            referer = "$mainUrl/"
                            quality = qualityFromName(label)
                            headers = mapOf(
                                "Referer" to "$mainUrl/",
                                "Range" to "bytes=0-",
                                "User-Agent" to USER_AGENT,
                            )
                        }
                    )
                } else {
                    runCatching {
                        loadExtractor(streamPage, mainUrl, subtitleCallback) { link ->
                            if (emitted.add(link.url)) {
                                handled = true
                                callback(link)
                            }
                        }
                    }
                }
            }
        }

        if (sources.isEmpty() && data.startsWith("http", true)) {
            documentDownloadLinks(data).forEach { link ->
                runCatching {
                    loadExtractor(link, data, subtitleCallback) { extractorLink ->
                        if (emitted.add(extractorLink.url)) {
                            handled = true
                            callback(extractorLink)
                        }
                    }
                }
            }
        }

        return handled || emitted.isNotEmpty()
    }

    private suspend fun documentDownloadLinks(url: String): List<String> {
        val document = app.get(url, referer = "$mainUrl/").document
        return document.select(".download_box a[href], .download a[href]")
            .mapNotNull { it.attr("abs:href").ifBlank { it.attr("href") }.takeIf(String::isNotBlank) }
            .distinct()
    }

    private suspend fun extractStreamUrl(streamPage: String): String? {
        val response = runCatching {
            app.get(streamPage, referer = "$mainUrl/", headers = mapOf("User-Agent" to USER_AGENT))
        }.getOrNull() ?: return null
        val html = response.text
        val preloadUrl = listOf("stream_url", "hls_url", "stream_hls", "stream_m3u8", "direct_url")
            .firstNotNullOfOrNull { key ->
                Regex(""""$key"\s*:\s*"([^"]+)"""").find(html)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.streamUrlDecode()
                    ?.takeIf { it.isNotBlank() }
            }
        if (preloadUrl != null) return preloadUrl

        val inlineUrl = listOf(
            Regex("""INITIAL_STREAM_URL\s*=\s*["']([^"']+)["']"""),
            Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<video[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        ).firstNotNullOfOrNull { regex ->
            regex.find(html)
                ?.groupValues
                ?.getOrNull(1)
                ?.streamUrlDecode()
                ?.let { normalizeUrl(it, streamPage) }
                ?.takeIf { it.isNotBlank() }
        }
        if (inlineUrl != null) return inlineUrl

        val streamApi = Regex("""STREAM_URL_API\s*=\s*["']([^"']+)["']""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.streamUrlDecode()
            ?.let { normalizeUrl(it, streamPage) }
            ?: return null

        val apiResponse = runCatching {
            app.get(
                streamApi,
                referer = streamPage,
                headers = mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "User-Agent" to USER_AGENT,
                )
            )
        }.getOrNull() ?: return null

        return (tryParseJson<DirectStreamResponse>(apiResponse.text)?.url
            ?: Regex(""""url"\s*:\s*"([^"]+)"""")
                .find(apiResponse.text)
                ?.groupValues
                ?.getOrNull(1)
                ?.streamUrlDecode())
            ?.takeIf { it.isNotBlank() }
    }

    private fun decodeEpisodeSources(value: String): List<StreamSource> {
        if (value.isBlank()) return emptyList()
        val decoded = runCatching {
            String(Base64.getDecoder().decode(value.trim()))
        }.getOrElse { value }
        val parsed = tryParseJson<List<StreamSource>>(decoded).orEmpty()
        if (parsed.any { !it.url.isNullOrEmpty() }) return parsed

        return Regex("""\{[^{}]*"format"\s*:\s*"([^"]*)"[^{}]*"url"\s*:\s*\[(.*?)]""")
            .findAll(decoded)
            .mapNotNull { match ->
                val urls = Regex(""""([^"]+)"""")
                    .findAll(match.groupValues[2])
                    .map { it.groupValues[1].jsonUrlDecode() }
                    .filter { it.isNotBlank() }
                    .toList()
                    .takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                StreamSource(
                    format = match.groupValues[1].ifBlank { null },
                    url = urls,
                )
            }
            .toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst(".thumb a[href], .thumbnail a[href], h2 a[href], h3 a[href], .title-post2 a[href], a[href]")
            ?: if (tagName() == "a") this else return null
        val href = link.attr("abs:href").ifBlank { link.attr("href") }.takeIf { it.isNotBlank() }?.let(::fixUrl) ?: return null
        if (!href.contains(mainUrl) || href.contains("/category/") || href.contains("/tag/") || href.contains("/type/")) return null

        val title = listOf(
            link.attr("title"),
            selectFirst("h2 a, h3 a, .title-post2")?.text(),
            selectFirst("img")?.attr("alt"),
            link.text()
        ).firstOrNull { !it.isNullOrBlank() }
            ?.cleanTitle()
            ?: return null

        val poster = selectFirst("img")?.imageUrl()
        val type = getType(selectFirst(".terms_tag a[href*='/type/'], .bot-post a[href*='/type/']")?.text(), href)
        val episode = Regex("""(?:Ep\.?|Episode)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        return newAnimeSearchResponse(title, href, type) {
            posterUrl = poster
            episode?.let { addSub(it) }
        }
    }

    private fun Element.toEpisode(): Episode? {
        val data = attr("data").trim().takeIf { it.isNotBlank() } ?: return null
        val rawTitle = attr("title").ifBlank { text() }.trim()
        val epNum = Regex("""Episode\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            .find(rawTitle.ifBlank { id() })
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?: Regex("""play_eps_(\d+)""", RegexOption.IGNORE_CASE)
                .find(id())
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()

        return this@NimegamiProvider.newEpisode(data, {
            name = rawTitle.ifBlank { "Episode ${epNum ?: "?"}" }
            episode = epNum?.toInt()
        }, false)
    }

    private fun detailValue(document: Document, label: String): String? {
        return document.select("tr")
            .firstOrNull { row ->
                row.selectFirst("td.tablex")?.text()
                    ?.replace(":", "")
                    ?.trim()
                    ?.equals(label, true) == true
            }
            ?.select("td")
            ?.getOrNull(1)
            ?.text()
            ?.trim()
            ?.ifBlank { null }
    }

    private fun Element.imageUrl(): String? {
        return listOf(
            attr("abs:data-src"),
            attr("abs:data-lazy-src"),
            attr("abs:srcset").substringBefore(" "),
            attr("abs:src"),
            attr("data-src"),
            attr("data-lazy-src"),
            attr("srcset").substringBefore(" "),
            attr("src")
        ).firstOrNull { it.isNotBlank() }?.let(::fixUrl)
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

    private fun getType(typeLabel: String?, url: String): TvType {
        val value = typeLabel.orEmpty()
        return when {
            value.contains("movie", true) || url.contains("/type/movie", true) -> TvType.AnimeMovie
            value.contains("ova", true) || value.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun getStatus(value: String): ShowStatus? {
        return when {
            value.contains("On-Going", true) || value.contains("Ongoing", true) -> ShowStatus.Ongoing
            value.contains("Complete", true) || value.contains("End", true) -> ShowStatus.Completed
            else -> null
        }
    }

    private fun extractYear(value: String): Int? {
        return Regex("""(19|20)\d{2}""").find(value)?.value?.toIntOrNull()
    }

    private fun qualityFromName(value: String): Int {
        return Regex("""\b(2160|1440|1080|720|480|360|240)\b""", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""(?i)\s+Sub\s+Indo.*$"""), "")
            .replace(Regex("""(?i)\s+BD\s+Sub\s+Indo.*$"""), "")
            .replace(Regex("""(?i)\s*:\s*Episode\s+\d+.*$"""), "")
            .replace(Regex("""(?i)\s+Episode\s+\d+.*$"""), "")
            .trim()
    }

    private fun String.jsonUrlDecode(): String {
        return replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003f", "?")
            .replace("\\u002F", "/")
    }

    private fun String.streamUrlDecode(): String {
        return jsonUrlDecode()
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
    }

    data class StreamSource(
        val format: String? = null,
        val url: List<String>? = null,
    )

    data class DirectStreamResponse(
        val ok: Boolean? = null,
        val url: String? = null,
    )
}
