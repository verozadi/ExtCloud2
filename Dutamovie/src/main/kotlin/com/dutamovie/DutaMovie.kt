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
    override var mainUrl = "https://simplycufflinks.com"
    override var name = "DutaMovie🎉"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes =
            setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    

    override val mainPage =
            mainPageOf(
                    "category/box-office/page/%d/" to "Box Office",
                    "category/serial-tv/page/%d/" to "Serial TV",
                    "category/animation/page/%d/" to "Animasi",
                    "country/korea/page/%d/" to "Serial TV Korea",
                    "country/indonesia/page/%d/" to "Serial TV Indonesia",
            )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(pageUrl(request.data, page), referer = "$mainUrl/").document
        val home = document.toSearchResults()
        val hasNext =
                document
                        .select(
                                "link[rel=next], a.next.page-numbers, a.page-numbers[href*='/page/${page + 1}/']"
                        )
                        .isNotEmpty()
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor =
                selectFirst("h2.entry-title a[href], .content-thumbnail a[href], a[href][itemprop=url]")
                        ?: return null
        val title =
                listOf(
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
        val href = normalizeUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!href.contains(URI(mainUrl).host, true)) return null
        val ratingText = this.selectFirst("div.gmr-rating-item")?.text()?.trim()
        val posterUrl =
                fixUrlNull(this.selectFirst(".content-thumbnail img, a[href] img, img")?.getImageAttr())
                        .fixImageQuality()
        val quality =
                this.select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
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
        val document =
                app.get(
                                "${mainUrl}/?s=$encoded&post_type[]=post&post_type[]=tv",
                                referer = "$mainUrl/",
                                timeout = 50L,
                        )
                        .document
        val results = document.toSearchResults()
        return results
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


    override suspend fun load(url: String): LoadResponse {
    // Pakai Desktop User-Agent agar website tidak mengirim halaman mobile
    val desktopHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    val fetch = app.get(url, headers = desktopHeaders)
    val document = fetch.document

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
        document.selectFirst("a.button.button-shadow.active")?.attr("href")
            ?: url.substringBefore("/eps/")

    val seriesDoc = app.get(seriesUrl, headers = desktopHeaders).document

    val episodeElements =
        seriesDoc.select("div.gmr-listseries a.button.button-shadow")

    // Nomor episode manual (agar tidak lompat)
    var episodeCounter = 1

    val episodes = episodeElements.mapNotNull { eps ->
        val href = fixUrl(eps.attr("href")).trim()
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
    val id = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")

    // 🎬 Ambil iframe player (streaming)
    if (id.isNullOrEmpty()) {
        document.select("ul.muvipro-player-tabs li a").amap { ele ->
            val iframe = app.get(fixUrl(ele.attr("href")))
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
                        "article.item, article.item-infinite, div.gmr-item-modulepost, div.gmr-module-posts > div[class*=col-]"
                )
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
    }

    private fun pageUrl(pattern: String, page: Int): String {
        val path =
                if (page <= 1) {
                    pattern.replace("/page/%d/", "/").replace("page/%d/", "")
                } else {
                    pattern.format(page)
                }
        return normalizeUrl(path, mainUrl) ?: "$mainUrl/"
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
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrNull()
        }
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

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}
