package com.gojodesu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class GojodesuProvider : MainAPI() {
    override var mainUrl = "https://gojodesu.com"
    override var name = "GojoDesu"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "/anime/?status=&type=&order=update" to "Update Anime",
        "/anime/?status=&type=&order=latest" to "Anime Terbaru",
        "/anime/?status=ongoing&type=&order=update" to "Ongoing",
        "/anime/?status=completed&type=&order=update" to "Completed",
        "/anime/?status=&type=movie&order=update" to "Movie",
        "/anime/?status=&type=ova&order=update" to "OVA",
        "/anime/?status=&type=&order=popular" to "Popular",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val documents = if (request.data.contains("type=ova", true)) {
            val fallbackTypes = listOf("ova", "special", "ona", "bd")
            fallbackTypes.mapNotNull { type ->
                val data = request.data.replace(Regex("""type=[^&]*"""), "type=$type")
                runCatching { app.get(pageUrl(data, page), referer = "$mainUrl/").document }.getOrNull()
            }
        } else {
            listOf(app.get(pageUrl(request.data, page), referer = "$mainUrl/").document)
        }

        val items = documents.flatMap { it.toSearchResults() }.distinctBy { it.url }
        val hasNext =
            documents.any {
                it.select("a.next.page-numbers, .pagination a[href*='/page/${page + 1}/'], a[href*='/page/${page + 1}/']")
                    .isNotEmpty()
            }

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded", referer = "$mainUrl/").document
        return document.toSearchResults()
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = normalizeUrl(url, mainUrl) ?: url
        val document = app.get(fixedUrl, referer = "$mainUrl/").document

        val title =
            document.selectFirst(".animefull h1.entry-title, h1.entry-title[itemprop=name], h1.entry-title, meta[property=og:title], title")
                ?.let { it.attr("content").ifBlank { it.text() } }
                ?.cleanTitle()
                ?.takeIf { it.isNotBlank() }
                ?: throw ErrorLoadingException("Title not found")

        val poster =
            document.selectFirst(".animefull .thumb img, .single-info .thumb img, meta[property=og:image], img.wp-post-image")
                ?.imageUrl()
                ?.fixImageQuality()
        val type = getType(document.infoText("Type"), fixedUrl)
        val year = document.infoText("Released")?.extractYear()
        val rating = document.selectFirst("meta[itemprop=ratingValue], .rating strong")
            ?.let { it.attr("content").ifBlank { it.text() } }
            ?.toScore()
        val status = getStatus(document.infoText("Status"))
        val tags = document.select(".animefull .genxed a[href*='/genres/'], .single-info .genxed a[href*='/genres/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val plot = document.selectFirst(".bixbox.synp .entry-content, .entry-content[itemprop=description]")
            ?.cleanTextBlock()

        val episodes = document.select(".eplister ul li a[href], .eplister a[href]")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        val recommendations = document.select(".bixbox .listupd article.bs, .listupd article.bs")
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == fixedUrl }
            .distinctBy { it.url }

        return newAnimeLoadResponse(title, fixedUrl, type) {
            posterUrl = poster
            backgroundPosterUrl = poster
            this.year = year
            plot?.let { this.plot = it }
            this.tags = tags
            rating?.let { score = Score.from10(it) }
            showStatus = status
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, episodes.ifEmpty { listOf(newEpisode(fixedUrl)) })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedUrl = normalizeUrl(data, mainUrl) ?: data
        val document = app.get(fixedUrl, referer = "$mainUrl/").document
        val candidates = linkedSetOf<String>()

        document.select("#embed_holder iframe[src], .player-embed iframe[src], .video-content iframe[src], iframe[src]")
            .mapNotNullTo(candidates) { it.iframeUrl(fixedUrl) }

        document.select("select.mirror option[value], .mirror option[value]")
            .mapNotNull { option ->
                option.attr("value").takeIf { it.isNotBlank() }?.let { normalizeUrl(it, fixedUrl) }
            }
            .forEach { mirrorPage ->
                runCatching {
                    app.get(mirrorPage, referer = fixedUrl).document
                        .select("iframe[src], iframe[data-src], iframe[data-litespeed-src]")
                        .mapNotNullTo(candidates) { it.iframeUrl(mirrorPage) }
                }
            }

        candidates
            .filterNot { it.contains("youtube.", true) || it.contains("youtu.be", true) }
            .forEach { iframe ->
                runCatching { loadExtractor(iframe, fixedUrl, subtitleCallback, callback) }
            }

        return candidates.isNotEmpty()
    }

    private fun Document.toSearchResults(): List<SearchResponse> {
        return select("article.bs, .listupd .bs")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url.contains("/anime/", true) }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href][itemprop=url], .bsx > a[href], a.tip[href], h2 a[href], a.series[href], a[href]")
            ?: return null
        val href = normalizeUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!href.contains("/anime/", true)) return null

        val title = listOf(
            anchor.attr("title"),
            selectFirst("h2[itemprop=headline], .tt h2, h4 a.series")?.text(),
            selectFirst("img[title]")?.attr("title"),
            selectFirst("img[alt]")?.attr("alt"),
            anchor.text(),
        ).firstOrNull { !it.isNullOrBlank() }?.cleanTitle() ?: return null

        val poster = selectFirst(".limit img, img")?.imageUrl()?.fixImageQuality()
        val type = getType(selectFirst(".typez")?.text(), href)
        val episode = Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
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
        val href = normalizeUrl(attr("href"), mainUrl) ?: return null
        val label = selectFirst(".epl-title")?.text()?.trim().orEmpty()
            .ifBlank { text().replace(Regex("""\s+"""), " ").trim() }
        val episodeNumber =
            selectFirst(".epl-num")?.text()?.toIntOrNull()
                ?: Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(label)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                ?: Regex("""-episode-(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

        return newEpisode(href) {
            name = label.ifBlank { episodeNumber?.let { "Episode $it" } }
            episode = episodeNumber
        }
    }

    private fun Document.infoText(label: String): String? {
        return select(".infox .spe span, .single-info .spe span")
            .firstOrNull { span ->
                span.selectFirst("b")?.text()?.cleanLabel()?.equals(label, true) == true
            }
            ?.clone()
            ?.also { it.select("b").remove() }
            ?.text()
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.ifBlank { null }
    }

    private fun getType(value: String?, url: String): TvType {
        val text = value.orEmpty()
        return when {
            text.contains("movie", true) || url.contains("movie", true) -> TvType.AnimeMovie
            text.contains("ova", true) ||
                text.contains("special", true) ||
                text.contains("ona", true) ||
                text.contains("bd", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun getStatus(value: String?): ShowStatus? {
        return when {
            value?.contains("ongoing", true) == true -> ShowStatus.Ongoing
            value?.contains("completed", true) == true || value?.contains("complete", true) == true -> ShowStatus.Completed
            else -> null
        }
    }

    private fun pageUrl(pattern: String, page: Int): String {
        val fixed = normalizeUrl(pattern, mainUrl) ?: "$mainUrl/"
        if (page <= 1) return fixed
        val parts = fixed.split("?", limit = 2)
        val base = parts[0].trimEnd('/')
        val query = parts.getOrNull(1)?.let { "?$it" }.orEmpty()
        return "$base/page/$page/$query"
    }

    private fun normalizeUrl(raw: String, baseUrl: String): String? {
        val clean = Jsoup.parse(raw).text()
            .trim()
            .replace("&amp;", "&")
            .takeIf {
                it.isNotBlank() && !it.startsWith("javascript:", true) && !it.startsWith("data:", true)
            } ?: return null

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()
        }
    }

    private fun Element.imageUrl(): String? {
        return listOf(
            attr("abs:content"),
            attr("abs:data-src"),
            attr("abs:data-lazy-src"),
            attr("abs:data-litespeed-src"),
            attr("abs:srcset").substringBefore(" "),
            attr("abs:src"),
            attr("content"),
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-litespeed-src"),
            attr("srcset").substringBefore(" "),
            attr("src"),
        ).firstOrNull { it.isNotBlank() }?.let { normalizeUrl(it, mainUrl) }
    }

    private fun Element.iframeUrl(baseUrl: String): String? {
        return listOf(
            attr("data-litespeed-src"),
            attr("data-src"),
            attr("src"),
        ).firstOrNull { it.isNotBlank() }?.let { normalizeUrl(httpsify(it), baseUrl) }
    }

    private fun Element.cleanTextBlock(): String? {
        val content = clone()
        content.select("script, style, iframe, .sharedaddy").remove()
        return content.select("p").joinToString("\n") { it.text().trim() }
            .ifBlank { content.text().replace(Regex("""\s+"""), " ").trim() }
            .ifBlank { null }
    }

    private fun String.cleanTitle(): String {
        return Jsoup.parse(this).text()
            .replace(Regex("""(?i)\s+(?:Subtitle\s*Indonesia|Sub\s*Indo|English\s*Subbed)\b.*$"""), "")
            .replace(Regex("""(?i)\s*[-|]\s*GojoDesu.*$"""), "")
            .replace(Regex("""(?i)\bNonton\s+Anime\s+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanLabel(): String {
        return replace(":", "").replace(Regex("""\s+"""), " ").trim()
    }

    private fun String.fixImageQuality(): String {
        return replace(Regex("""-\d+x\d+(?=\.[a-zA-Z]{3,4}(?:$|[?#]))"""), "")
            .replace(Regex("""([?&])resize=\d+,\d+"""), "$1")
            .replace(Regex("""[?&]$"""), "")
    }

    private fun String.extractYear(): Int? {
        return Regex("""(19|20)\d{2}""").find(this)?.value?.toIntOrNull()
    }

    private fun String.toScore(): Double? {
        return Regex("""\d+(?:\.\d+)?""").find(this)?.value?.toDoubleOrNull()
    }
}

class GojodesuKotakAjaib : ExtractorApi() {
    override val name = "KotakAjaib"
    override val mainUrl = "https://kotakajaib.me"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.replace("/file/", "/embed/")
        val document = app.get(
            embedUrl,
            referer = referer,
            headers = mapOf("User-Agent" to USER_AGENT),
        ).document

        document.select("button.server-item[data-frame]").forEach { button ->
            val decoded = button.attr("data-frame").decodeBase64Url() ?: return@forEach
            val iframeUrl = httpsify(URLDecoder.decode(decoded, "UTF-8"))
            loadExtractor(iframeUrl, embedUrl, subtitleCallback, callback)
        }
    }

    private fun String.decodeBase64Url(): String? {
        val cleaned = trim().replace('-', '+').replace('_', '/')
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return runCatching { base64Decode(padded) }.getOrNull()
    }
}
