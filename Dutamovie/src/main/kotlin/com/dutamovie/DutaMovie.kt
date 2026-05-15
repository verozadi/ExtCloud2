package com.dutamovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

open class DutaMovie : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = "https://duta.media"
    override var name = "DutaMovie🎉"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes =
            setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    private val fallbackMainUrl = "https://www.seosaja.com"
    private val rewriteHosts = setOf("simplycufflinks.com", "www.simplycufflinks.com")
    private val allowedHosts = rewriteHosts + setOf("www.seosaja.com", "seosaja.com")
    

    override val mainPage =
            mainPageOf(
                    "category/box-office/page/%d/" to "Box Office",
                    "category/serial-tv/page/%d/" to "Serial TV",
                    "category/animation/page/%d/" to "Animasi",
                    "country/korea/page/%d/" to "Serial TV Korea",
                    "country/indonesia/page/%d/" to "Serial TV Indonesia",
            )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var document =
                runCatching { app.get(pageUrl(request.data, page), referer = "$mainUrl/").document }
                        .getOrNull()
        var home = document?.toSearchResults().orEmpty()

        if (home.isEmpty()) {
            document =
                    app.get(seosajaPageUrl(request.name, page), referer = "$fallbackMainUrl/")
                            .document
            home = document.toSearchResults()
        }

        val hasNext =
                document!!
                        .select(
                                "link[rel=next], a.next.page-numbers, a.btn-next, a.page-numbers[href*='/page/${page + 1}/'], a[href*='/page/${page + 1}']"
                        )
                        .isNotEmpty()
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor =
                selectFirst(
                                "h3.poster-title a[href], h2.entry-title a[href], .content-thumbnail a[href], figure a[href][itemprop=url], a[href][itemprop=url]"
                        )
                        ?: return null
        val title =
                listOf(
                                selectFirst("h3.poster-title")?.text(),
                                selectFirst("h2.entry-title a[href]")?.text(),
                                anchor.attr("title")
                                        .substringAfter("Permalink ke:", anchor.attr("title"))
                                        .substringAfter("Permalink to:", anchor.attr("title")),
                                selectFirst("img[title]")?.attr("title"),
                                selectFirst("img[alt]")?.attr("alt"),
                                anchor.text(),
                        )
                        .firstOrNull { !it.isNullOrBlank() }
                        ?.cleanTitle()
                        ?: return null
        val itemBaseUrl = if (selectFirst("h3.poster-title") != null) fallbackMainUrl else mainUrl
        val href = normalizeUrl(anchor.attr("href"), itemBaseUrl)?.rewriteToMainHost() ?: return null
        if (!href.isAllowedProviderUrl()) return null
        val ratingText =
                this.selectFirst("div.gmr-rating-item, span.rating")?.text()?.replace("★", "")?.trim()
        val posterUrl =
                fixUrlNull(this.selectFirst(".content-thumbnail img, picture img, a[href] img, img")?.getImageAttr())
                        .fixImageQuality()
                        ?.rewriteToMainHost()
        val quality =
                this.select("div.gmr-qual, div.gmr-quality-item > a, span.label").text().trim().replace("-", "")
        val isSeries =
                href.contains("/tv/", true) ||
                        href.contains("/eps/", true) ||
                        selectFirst("div.gmr-numbeps > span, .gmr-posttype-item") != null ||
                        text().contains("TV Show", true) ||
                        Regex("""\bS\d+\s*E""", RegexOption.IGNORE_CASE).containsMatchIn(text())
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                if (quality.isNotBlank()) addQuality(quality)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
                this.score = Score.from10(ratingText?.toDoubleOrNull())
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val results =
                runCatching {
                            app.get(
                                            "${mainUrl}/?s=$encoded",
                                            referer = "$mainUrl/",
                                            timeout = 50L,
                                    )
                                    .document
                                    .toSearchResults()
                        }
                        .getOrDefault(emptyList())

        return results.ifEmpty {
            app.get("${fallbackMainUrl}/?s=$encoded", referer = "$fallbackMainUrl/", timeout = 50L)
                    .document
                    .toSearchResults()
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {

    // Ambil judul dari <h2 class="entry-title"><a>
    val title = selectFirst("h2.entry-title > a")
        ?.text()
        ?.trim()
        ?: return null

    // Ambil link dari anchor di entry-title
    val href = selectFirst("h2.entry-title > a")
        ?.attr("href")
        ?.trim()
        ?.let { normalizeUrl(it, mainUrl)?.rewriteToMainHost() }
        ?: return null

    // Poster dari elemen img di content-thumbnail
    val img = selectFirst("div.content-thumbnail img")
    val posterUrl =
        img?.attr("src")
            ?.ifBlank { img.attr("data-src") }
            ?.ifBlank { img.attr("srcset")?.split(" ")?.firstOrNull() }

    return newMovieSearchResponse(title, href, TvType.Movie) {
        this.posterUrl = fixUrlNull(posterUrl)
    }
}

    private fun Element.toSeosajaRelatedResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = normalizeUrl(anchor.attr("href"), fallbackMainUrl)?.rewriteToMainHost() ?: return null
        if (!href.isAllowedProviderUrl()) return null
        val title =
            listOf(
                    selectFirst(".video-title")?.text(),
                    selectFirst("img[title]")?.attr("title"),
                    selectFirst("img[alt]")?.attr("alt"),
                    anchor.text(),
                )
                .firstOrNull { !it.isNullOrBlank() }
                ?.cleanTitle()
                ?: return null
        val poster = fixUrlNull(selectFirst("img")?.getImageAttr()).fixImageQuality()?.rewriteToMainHost()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }


    override suspend fun load(url: String): LoadResponse {
    // Pakai Desktop User-Agent agar website tidak mengirim halaman mobile
    val desktopHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    val fetch = app.get(url, headers = desktopHeaders)
    val document = fetch.document

    if (runCatching { URI(url).host.orEmpty() }.getOrDefault("").contains("seosaja", true)) {
        val title = document.selectFirst("div.movie-info h1, h1")
            ?.text()
            ?.trim()
            .orEmpty()

        val poster =
            fixUrlNull(
                document.selectFirst("img.poster-baner, div.movie-info picture img, meta[property=og:image]")
                    ?.getImageAttr()
            )?.fixImageQuality()

        val tags =
            document.select("div.tag-list a[href*='/genre/']").map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

        val year = Regex("""(?:19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val description = document.selectFirst("div.synopsis, meta[name=description]")
            ?.let { it.attr("content").ifBlank { it.text() } }
            ?.trim()

        val rating =
            document.selectFirst("div.info-tag strong")
                ?.text()
                ?.replace(Regex("""[^\d.]"""), "")
                ?.trim()
                ?.ifBlank { null }

        val duration =
            document.selectFirst("div.info-tag span:matchesOwn(\\d+\\s*m)")
                ?.text()
                ?.replace(Regex("\\D"), "")
                ?.toIntOrNull()

        val actors =
            document.select("div.detail p:contains(Bintang Film) a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }

        val recommendations =
            document.select("div.related-content li, div.video-list-wrapper li")
                .mapNotNull { it.toSeosajaRelatedResult() }
                .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            addScore(rating)
            addActors(actors)
            this.recommendations = recommendations
            this.duration = duration ?: 0
        }
    }

    val title =
        document.selectFirst("h1.entry-title")
            ?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
            ?.trim()
            .orEmpty()

    val poster =
        fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr())
            ?.fixImageQuality()
            ?.rewriteToMainHost()

    val tags = document.select("strong:contains(Genre) ~ a").eachText()

    val year =
        document.select("div.gmr-moviedata strong:contains(Year:) > a")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

    val tvType = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
    val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
    val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
    val rating =
        document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")
            ?.text()?.trim()

    val actors =
        document.select("div.gmr-moviedata").last()
            ?.select("span[itemprop=actors]")?.map {
                it.select("a").text()
            }

    val duration = document.selectFirst("div.gmr-moviedata span[property=duration]")
        ?.text()
        ?.replace(Regex("\\D"), "")
        ?.toIntOrNull()

    val recommendations = document
    .select("article.item.col-md-20")
    .mapNotNull { it.toRecommendResult() }


    // =========================
    //  MOVIE
    // =========================

    if (tvType == TvType.Movie) {
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            addScore(rating)
            addActors(actors)
            this.recommendations = recommendations
            this.duration = duration ?: 0
            addTrailer(trailer, referer = mainUrl, addRaw = true)
        }
    }


    // =========================
    //  TV SERIES MODE
    // =========================

    // Tombol “View All Episodes” → URL halaman series
    val seriesUrl =
        document.selectFirst("a.button.button-shadow.active")?.attr("href")?.let {
            normalizeUrl(it, mainUrl)?.rewriteToMainHost()
        }
            ?: url.substringBefore("/eps/")

    val seriesDoc = app.get(seriesUrl, headers = desktopHeaders).document

    val episodeElements =
        seriesDoc.select("div.gmr-listseries a.button.button-shadow")

    // Nomor episode manual (agar tidak lompat)
    var episodeCounter = 1

    val episodes = episodeElements.mapNotNull { eps ->
        val href = (normalizeUrl(eps.attr("href"), mainUrl)?.rewriteToMainHost() ?: fixUrl(eps.attr("href"))).trim()
        val name = eps.text().trim()

        // Skip tombol "View All Episodes"
        if (name.contains("View All Episodes", ignoreCase = true)) return@mapNotNull null

        // Skip jika href sama dengan halaman series
        if (href == seriesUrl) return@mapNotNull null

        // Skip elemen non-episode
        if (!name.contains("Eps", ignoreCase = true)) return@mapNotNull null

        // Ambil season (default 1)
        val regex = Regex("""S(\d+)\s*Eps""", RegexOption.IGNORE_CASE)
        val match = regex.find(name)
        val season = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

        // Nomor episode final
        val epNum = episodeCounter++

        newEpisode(href) {
            this.name = name
            this.season = season
            this.episode = epNum
        }
    }

    // Return response TV Series
    return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
        this.posterUrl = poster
        this.year = year
        this.plot = description
        this.tags = tags
        addScore(rating)
        addActors(actors)
        this.recommendations = recommendations
        this.duration = duration ?: 0
        addTrailer(trailer, referer = mainUrl, addRaw = true)
    }
}



    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data).document
    var found = false

    document.select("a[data-href], option[value], a[data-url]").forEach { link ->
        val encoded =
            listOf(link.attr("data-href"), link.attr("value"), link.attr("data-url"))
                .firstOrNull { it.isNotBlank() }
                ?: return@forEach
        val streamUrl = encoded.decodeHexUrl() ?: encoded
        if (streamUrl.isBlank()) return@forEach
        found = true
        loadExtractor(httpsify(streamUrl), data, subtitleCallback, callback)
    }

    if (found) return true

    val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")

    // 🎬 Ambil iframe player (streaming)
    if (id.isNullOrEmpty()) {
        document.select("ul.muvipro-player-tabs li a").amap { ele ->
            val tabUrl = normalizeUrl(ele.attr("href"), mainUrl)?.rewriteToMainHost() ?: fixUrl(ele.attr("href"))
            val iframe = app.get(tabUrl)
                .document
                .selectFirst("div.gmr-embed-responsive iframe")
                ?.getIframeAttr()
                ?.let { httpsify(it) }
                ?: return@amap

            loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
        }
    } else {
        document.select("div.tab-content-ajax").amap { ele ->
            val server = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                referer = data,
                data = mapOf(
                    "action" to "muvipro_player_content",
                    "tab" to ele.attr("id"),
                    "post_id" to "$id"
                )
            ).document
                .select("iframe")
                .attr("src")
                .let { httpsify(it) }

            loadExtractor(server, "$mainUrl/", subtitleCallback, callback)
        }
    }

document.select("ul.gmr-download-list li a").forEach { linkEl ->
    val downloadUrl = linkEl.attr("href")
    if (downloadUrl.isNotBlank()) {
        loadExtractor(downloadUrl, data, subtitleCallback, callback)
    }
}

    return true
}


    private fun Document.toSearchResults(): List<SearchResponse> {
        return select(
                        "main article:has(h3.poster-title), article.item, article.item-infinite, div.gmr-item-modulepost, div.gmr-module-posts > div[class*=col-]"
                )
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
    }

    private fun pageUrl(pattern: String, page: Int): String {
        val path =
                if (page <= 1) {
                    pattern.replace("/page/%d/", "/")
                        .replace("/page/%d", "")
                        .replace("page/%d/", "")
                        .replace("page/%d", "")
                } else {
                    pattern.format(page)
                }
        return normalizeUrl(path, mainUrl) ?: "$mainUrl/"
    }

    private fun seosajaPageUrl(section: String, page: Int): String {
        val path =
                when (section) {
                    "Box Office" -> "genre/box-office/page/%d"
                    "Serial TV" -> "genre/serial-tv/page/%d"
                    "Animasi" -> "genre/animation/page/%d"
                    "Serial TV Korea" -> "country/korea/page/%d"
                    "Serial TV Indonesia" -> "country/indonesia/page/%d"
                    else -> "genre/box-office/page/%d"
                }
        val fixedPath = if (page <= 1) path.replace("/page/%d", "") else path.format(page)
        return normalizeUrl(fixedPath, fallbackMainUrl) ?: "$fallbackMainUrl/"
    }

    private fun normalizeUrl(raw: String, baseUrl: String): String? {
        val clean =
                Jsoup.parse(raw)
                        .text()
                        .trim()
                        .replace("&amp;", "&")
                        .takeIf {
                            it.isNotBlank() &&
                                    !it.startsWith("javascript:", true) &&
                                    !it.startsWith("data:", true)
                        }
                        ?: return null

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            else -> {
                val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                runCatching { URI(base).resolve(clean).toString() }.getOrNull()
            }
        }
    }

    private fun String.isAllowedProviderUrl(): Boolean {
        val host = runCatching { URI(this).host.orEmpty() }.getOrDefault("")
        return host.equals(URI(mainUrl).host, true) || allowedHosts.any { host.equals(it, true) }
    }

    private fun String.rewriteToMainHost(): String {
        val uri = runCatching { URI(this) }.getOrNull() ?: return this
        val host = uri.host ?: return this
        if (host.equals(URI(mainUrl).host, true) || rewriteHosts.none { host.equals(it, true) }) return this
        return URI(
                URI(mainUrl).scheme,
                uri.userInfo,
                URI(mainUrl).host,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment,
            )
            .toString()
    }

    private fun String.cleanTitle(): String {
        return Jsoup.parse(this)
                .text()
                .replace(Regex("""(?i)^Permalink\s+(?:ke|to):\s*"""), "")
                .replace(Regex("""(?i)^Nonton\s+(?:Film|Movie|Series|Serial|Drama)\s+"""), "")
                .replace(Regex("""(?i)\s+terbaru\s+di\s+Dutamovie21.*$"""), "")
                .replace(Regex("""(?i)\s+(?:Sub\s*Indo|Subtitle\s*Indonesia)\b.*$"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim()
    }


    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("content") -> this.attr("abs:content")
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
                ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun String.decodeHexUrl(): String? {
        val clean = trim()
        if (!clean.matches(Regex("""[0-9a-fA-F]+""")) || clean.length % 2 != 0) return null
        return runCatching {
            clean.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}
