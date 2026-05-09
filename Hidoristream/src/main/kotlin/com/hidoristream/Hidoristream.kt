package com.hidoristream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Hidoristream : MainAPI() {
    override var mainUrl = "https://v8.hidoristream.online"
    override var name = "Hidoristream🐹"
    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        var context: android.content.Context? = null
        fun getType(t: String?): TvType {
            if (t == null) return TvType.Anime
            return when {
                t.contains("Tv", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String?): ShowStatus {
            if (t == null) return ShowStatus.Completed
            return when {
                t.contains("Ongoing", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "anime/?status=&type=&order=update" to "Update Terbaru",
        "anime/?sub=&order=latest" to "Baru Ditambahkan",
        "anime/?status=&type=&order=popular" to "Terpopuler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}&page=$page"
        val document = app.get(url).document
        val items = document.select("div.listupd article.bs")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a")?.attr("href") ?: return null
        val title = selectFirst("div.tt")?.text()?.trim()
            ?: selectFirst("a")?.attr("title")?.trim()
            ?: return null
        val cleanTitle = title
            .replace(Regex("\\b(Sub(\\s*)?(title)?\\s*Indonesia|Subtitle\\s*Indonesia|Sub\\s*Indo)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val poster = selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        return newAnimeSearchResponse(cleanTitle, fixUrl(link), TvType.Anime) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.listupd article.bs")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = selectFirst("div.tt")?.text()?.trim() ?: return null
        val cleanTitle = title
            .replace(Regex("\\b(Sub(\\s*)?(title)?\\s*Indonesia|Subtitle\\s*Indonesia|Sub\\s*Indo)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val href = selectFirst("a")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        return newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")
            ?.text()
            ?.replace(Regex("\\b(Sub(\\s*)?(title)?\\s*Indonesia|Subtitle\\s*Indonesia)\\b", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        val poster = document.selectFirst("div.bigcontent img")?.getImageAttr()?.let { fixUrlNull(it) }

        val description = document.select("div.entry-content p")
            .joinToString("\n") { it.text() }
            .trim()

        val year = document.selectFirst("span:matchesOwn(Dirilis:)")?.ownText()
            ?.filter { it.isDigit() }
            ?.take(4)
            ?.toIntOrNull()

        val duration = document.selectFirst("div.spe span:contains(Durasi:)")?.ownText()?.let {
            val h = Regex("(\\d+)\\s*hr").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("(\\d+)\\s*min").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            h * 60 + m
        }

        val typeText = document.selectFirst("span:matchesOwn(Tipe:)")?.ownText()?.trim()
        val type = getType(typeText)

        val tags = document.select("div.genxed a").map { it.text() }
        val actors = document.select("span:has(b:matchesOwn(Artis:)) a").map { it.text().trim() }
        val rating = document.selectFirst("div.rating strong")
            ?.text()
            ?.replace("Rating", "")
            ?.trim()
            ?.toDoubleOrNull()
        val trailer = document.selectFirst("div.bixbox.trailer iframe")?.attr("src")
        val status = getStatus(document.selectFirst("span:matchesOwn(Status:)")?.ownText()?.trim())

        val recommendations = document.select("div.listupd article.bs")
            .mapNotNull { it.toRecommendResult() }

        val castList = document.select("div.bixbox.charvoice div.cvitem").mapNotNull { item ->
            val charBox = item.selectFirst(".cvsubitem.cvchar") ?: item
            val actorBox = item.selectFirst(".cvsubitem.cvactor") ?: item

            val charName = charBox.selectFirst(".cvcontent .charname")?.text()?.trim()
            val charRole = charBox.selectFirst(".cvcontent .charrole")?.text()?.trim()
            val charImg = charBox.selectFirst(".cvcover img")?.getImageAttr()?.let { fixUrlNull(it) }

            val actorName = actorBox.selectFirst(".cvcontent .charname")?.text()?.trim()
            val actorImg = actorBox.selectFirst(".cvcover img")?.getImageAttr()?.let { fixUrlNull(it) }

            if (charName.isNullOrBlank() && actorName.isNullOrBlank()) return@mapNotNull null

            val actor = Actor(charName ?: actorName ?: "", charImg)
            val voiceActor = actorName?.let { Actor(it, actorImg) }
            ActorData(
                actor = actor,
                roleString = charRole,
                voiceActor = voiceActor,
            )
        }

        val episodeElements = document.select("div.eplister ul li a")
        val episodes = episodeElements
            .reversed()
            .mapIndexed { index, aTag ->
                val href = fixUrl(aTag.attr("href"))
                newEpisode(href) {
                    name = "Episode ${index + 1}"
                    episode = index + 1
                }
            }

        val altTitles = listOfNotNull(
            title,
            document.selectFirst("span:matchesOwn(Judul Inggris:)")?.ownText()?.trim(),
            document.selectFirst("span:matchesOwn(Judul Jepang:)")?.ownText()?.trim(),
            document.selectFirst("span:matchesOwn(Judul Asli:)")?.ownText()?.trim(),
        ).distinct()

        val malIdFromPage = document.selectFirst("a[href*=\"myanimelist.net/anime/\"]")
            ?.attr("href")
            ?.substringAfter("/anime/", "")
            ?.substringBefore("/")
            ?.toIntOrNull()
        val aniIdFromPage = document.selectFirst("a[href*=\"anilist.co/anime/\"]")
            ?.attr("href")
            ?.substringAfter("/anime/", "")
            ?.substringBefore("/")
            ?.toIntOrNull()

        val tracker = APIHolder.getTracker(altTitles, TrackerType.getTypes(type), year, true)

        return if (episodes.isNotEmpty()) {
            newAnimeLoadResponse(title, url, type) {
                posterUrl = tracker?.image ?: poster
                backgroundPosterUrl = tracker?.cover
                this.year = year
                this.plot = description
                this.tags = tags
                showStatus = status
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addEpisodes(DubStatus.Subbed, episodes)
                rating?.let { addScore(it.toString(), 10) }
                addActors(actors)
                if (castList.isNotEmpty()) this.actors = castList
                addTrailer(trailer)
                addMalId(malIdFromPage ?: tracker?.malId)
                addAniListId(aniIdFromPage ?: tracker?.aniId?.toIntOrNull())
            }
        } else {
            newMovieLoadResponse(title, url, type, url) {
                posterUrl = tracker?.image ?: poster
                backgroundPosterUrl = tracker?.cover
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.duration = duration ?: 0
                rating?.let { addScore(it.toString(), 10) }
                addActors(actors)
                if (castList.isNotEmpty()) this.actors = castList
                addTrailer(trailer)
                addMalId(malIdFromPage ?: tracker?.malId)
                addAniListId(aniIdFromPage ?: tracker?.aniId?.toIntOrNull())
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val emitted = linkedSetOf<String>()

        document.selectFirst("div.player-embed iframe")
            ?.getIframeAttr()
            ?.let { iframe ->
                resolveMirrorLink(iframe, "Player", data, emitted, subtitleCallback, callback)
            }

        val mirrorOptions = document.select("select.mirror option[value]:not([disabled])")
        for (opt in mirrorOptions) {
            val label = opt.text().trim().ifBlank { "Mirror" }
            decodeMirrorLinks(opt.attr("value")).forEach { mirrorUrl ->
                resolveMirrorLink(mirrorUrl, label, data, emitted, subtitleCallback, callback)
            }
        }

        val downloadLinks = document.select("div.dlbox li span.e a[href]")
            .ifEmpty { document.select("div.soraurlx a[href], div.soraddlx a[href], div.releases a[href]") }
        for (a in downloadLinks) {
            val url = a.attr("href").trim()
            if (url.isBlank()) continue

            val fixedUrl = httpsify(url)
            val host = fixedUrl.substringAfter("://").substringBefore("/").substringBefore("?")
            val qualityText = a.parents()
                .select("strong")
                .eachText()
                .firstOrNull()
                ?: a.parent()?.previousElementSibling()?.text()
                ?: a.closest("li")?.selectFirst("strong")?.text()
                ?: "Download"
            val label = a.text().trim().ifBlank { host }

            loadExtractor(fixedUrl, data, subtitleCallback, callback)

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name Download - $qualityText - $label",
                    url = fixedUrl,
                    type = INFER_TYPE,
                ) {
                    this.referer = data
                    this.quality = getQualityFromName(qualityText)
                }
            )
        }

        return true
    }

    private fun decodeMirrorLinks(value: String): List<String> {
        if (value.isBlank()) return emptyList()

        val decodedHtml = runCatching {
            base64Decode(value.replace("\\s".toRegex(), ""))
        }.getOrElse { value }

        val links = linkedSetOf<String>()
        val mirrorDoc = Jsoup.parse(decodedHtml)
        mirrorDoc.select("iframe[src], iframe[data-src], source[src], video[src], a[href]").forEach { element ->
            val rawUrl = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("href") }
            normalizeMirrorUrl(rawUrl, mainUrl)?.let(links::add)
        }

        Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
            .findAll(decodedHtml)
            .mapNotNull { normalizeMirrorUrl(it.value, mainUrl) }
            .forEach(links::add)

        return links.filterNot(::isNoiseFrame)
    }

    private suspend fun resolveMirrorLink(
        rawUrl: String,
        label: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val normalized = normalizeMirrorUrl(rawUrl, referer) ?: return
        if (!emitted.add(normalized)) return

        if (isDirectMedia(normalized)) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - $label",
                    url = normalized,
                    type = INFER_TYPE,
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(label)
                    this.headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
                }
            )
            return
        }

        val resolvedUrl = if (normalized.contains("short.icu", true)) {
            runCatching {
                app.get(
                    normalized,
                    referer = referer,
                    allowRedirects = true,
                    headers = mapOf("User-Agent" to USER_AGENT),
                ).url
            }.getOrDefault(normalized)
        } else {
            normalized
        }

        runCatching {
            loadExtractor(resolvedUrl, referer, subtitleCallback, callback)
        }
    }

    private fun normalizeMirrorUrl(rawUrl: String, baseUrl: String): String? {
        val clean = rawUrl.trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .takeIf {
                it.isNotBlank() &&
                    !it.startsWith("javascript:", true) &&
                    !it.startsWith("data:", true)
            }
            ?: return null

        return when {
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> httpsify(clean)
            else -> runCatching { java.net.URI(baseUrl).resolve(clean).toString() }.getOrNull()?.let(::httpsify)
        }
    }

    private fun isDirectMedia(url: String): Boolean {
        return Regex("""(?i)\.(m3u8|mp4)(?:$|[?#&])""").containsMatchIn(url) ||
            url.contains("/stream/", true)
    }

    private fun isNoiseFrame(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("facebook.com/plugins") ||
            lower.contains("histats.com") ||
            lower.contains("about:blank")
    }

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
            ?: this?.attr("data-src").takeIf { it?.isNotEmpty() == true }
            ?: this?.attr("src")
    }
}
