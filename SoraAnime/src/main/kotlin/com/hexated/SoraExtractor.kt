package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.RequestBodyTypes
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONArray
import org.json.JSONObject
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

object SoraExtractor : SoraAnime() {

    suspend fun invokeIdlix(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val query = title?.trim() ?: return
        val searchRes = app.get(
            "$idlixAPI/api/search?q=${query.urlEncodeCompat()}&limit=20",
            interceptor = wpRedisInterceptor
        ).text
        val results = tryParseJson<IdlixSearchResponse>(searchRes)?.results ?: return

        val match = results.firstOrNull { result ->
            val resultYear = (result.releaseDate ?: result.firstAirDate)
                ?.substringBefore("-")?.toIntOrNull()
            (result.title?.equals(query, true) == true || result.originalTitle?.equals(query, true) == true) &&
            (year == null || resultYear == year)
        } ?: results.firstOrNull { result ->
            result.title?.contains(query, true) == true || result.originalTitle?.contains(query, true) == true
        } ?: return

        val isMovie = season == null
        val contentType = if (isMovie) "movie" else "episode"
        val detailUrl = if (isMovie || match.contentType == "movie") {
            "$idlixAPI/api/movies/${match.slug}"
        } else {
            "$idlixAPI/api/series/${match.slug}"
        }
        val detail = app.get(detailUrl, interceptor = wpRedisInterceptor)
            .parsedSafe<IdlixDetailResponse>() ?: return

        val contentId = if (isMovie) {
            detail.id ?: return
        } else {
            val targetSeason = detail.seasons?.find { it.seasonNumber == season }
                ?: detail.firstSeason
            val targetEpisode = targetSeason?.episodes?.find { it.episodeNumber == episode }
            targetEpisode?.id ?: return
        }

        if (runCatching {
                invokeIdlixChallenge(contentType, contentId, subtitleCallback, callback)
            }.getOrDefault(false)
        ) return

        runCatching {
            invokeIdlixPlayInfo(contentType, contentId, subtitleCallback, callback)
        }.onFailure { logError(it) }
    }

    private suspend fun invokeIdlixChallenge(
        contentType: String,
        contentId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ts = System.currentTimeMillis()
        val aclrRes = app.get(
            "$idlixAPI/pagead/ad_frame.js?_=$ts",
            referer = idlixAPI,
            interceptor = wpRedisInterceptor
        ).text
        val aclr = Regex("""__aclr\s*=\s*"([a-f0-9]+)""").find(aclrRes)?.groupValues?.getOrNull(1)

        val challengeJson = """{"contentType": "$contentType", "contentId": "$contentId"${if (aclr != null) ", \"clearance\": \"$aclr\"" else ""}}"""
        val challengeText = app.post(
            "$idlixAPI/api/watch/challenge",
            requestBody = challengeJson.toRequestBody("application/json".toMediaTypeOrNull()),
            headers = mapOf(
                "accept" to "*/*",
                "content-type" to "application/json",
                "origin" to idlixAPI,
                "referer" to idlixAPI,
            ),
            interceptor = wpRedisInterceptor
        ).text

        val challengeRes = tryParseJson<IdlixChallengeResponse>(challengeText) ?: return false
        val nonce = solvePow(challengeRes.challenge, challengeRes.difficulty)

        val solveJson = """{"challenge": "${challengeRes.challenge}", "signature": "${challengeRes.signature}", "nonce": $nonce}"""
        val solveRes = app.post(
            "$idlixAPI/api/watch/solve",
            requestBody = solveJson.toRequestBody("application/json".toMediaTypeOrNull()),
            headers = mapOf(
                "accept" to "*/*",
                "content-type" to "application/json",
                "origin" to idlixAPI,
                "referer" to idlixAPI,
            ),
            interceptor = wpRedisInterceptor
        ).text

        val embedUrl = extractUrlFromSolveResponse(solveRes) ?: return false
        val embedPageUrl = when {
            embedUrl.startsWith("http", true) -> embedUrl
            embedUrl.startsWith("/") -> "$idlixAPI$embedUrl"
            else -> "$idlixAPI/$embedUrl"
        }

        var emitted = false
        loadExtractor(embedPageUrl, "$idlixAPI/", subtitleCallback) { link ->
            emitted = true
            callback.invoke(link)
        }
        return emitted
    }

    private suspend fun invokeIdlixPlayInfo(
        contentType: String,
        contentId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val res = app.get(
            "$idlixAPI/api/watch/play-info/$contentType/$contentId",
            interceptor = wpRedisInterceptor,
        ).parsedSafe<IdlixPlayInfoResponse>() ?: return false

        val redeemUrl = res.redeemUrl?.takeIf { it.isNotBlank() } ?: return false
        val claim = res.claim?.takeIf { it.isNotBlank() } ?: return false
        val iframeResponse = app.post(
            redeemUrl,
            requestBody = """{"claim":"$claim"}""".toRequestBody("application/json".toMediaTypeOrNull()),
            headers = mapOf(
                "accept" to "application/json,text/plain,*/*",
                "content-type" to "application/json",
                "origin" to idlixAPI,
                "referer" to idlixAPI,
            ),
            interceptor = wpRedisInterceptor,
        ).parsedSafe<IdlixIframeResponse>() ?: return false

        var emitted = false
        iframeResponse.url?.takeIf { it.isNotBlank() }?.let { streamUrl ->
            M3u8Helper.generateM3u8("Idlix", streamUrl, idlixAPI).forEach { link ->
                emitted = true
                callback.invoke(link)
            }
        }

        iframeResponse.subtitles.orEmpty().forEach { subtitle ->
            val path = subtitle.path?.takeIf { it.isNotBlank() } ?: return@forEach
            subtitleCallback.invoke(
                newSubtitleFile(
                    subtitle.label?.takeIf { it.isNotBlank() } ?: subtitle.lang ?: "Unknown",
                    path,
                )
            )
        }

        return emitted
    }

    suspend fun invokeYflix(
        tmdbId: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ids = listOfNotNull(tmdbId?.toString(), imdbId).distinct()
        if (ids.isEmpty()) return

        val patterns = listOf(
            if (season == null) "embed/movie/%s" else "embed/tv/%s/$season/$episode",
            if (season == null) "watch/movie/%s" else "watch/tv/%s/$season/$episode",
            if (season == null) "movie/%s" else "tv/%s/$season/$episode",
        )

        for (id in ids) {
            for (pattern in patterns) {
                val url = "$yflixAPI/${pattern.format(id)}"
                val res = runCatching { app.get(url, interceptor = wpRedisInterceptor) }.getOrNull() ?: continue
                val doc = res.document
                val html = res.text

                val iframe = doc.selectFirst("iframe[src]")?.attr("abs:src")?.trim().orEmpty()
                    .let { if (it.startsWith("//")) "https:$it" else it }
                    .takeIf { it.isNotBlank() }
                val iframeData = doc.selectFirst("iframe[data-src]")?.attr("abs:data-src")?.trim().orEmpty()
                    .let { if (it.startsWith("//")) "https:$it" else it }
                    .takeIf { it.isNotBlank() }
                val videoSrc = doc.selectFirst("video source[src], source[src]")?.attr("abs:src")?.trim().orEmpty()
                    .takeIf { it.isNotBlank() }
                val videoDirect = doc.selectFirst("video[src]")?.attr("abs:src")?.trim().orEmpty()
                    .takeIf { it.isNotBlank() }

                val scriptData = doc.select("script").joinToString("\n") { it.data() }
                val scriptUrl = Regex("""https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*""", RegexOption.IGNORE_CASE)
                    .find(scriptData)?.value.orEmpty().takeIf { it.isNotBlank() }

                val picked = listOfNotNull(iframe, iframeData, videoSrc, videoDirect, scriptUrl)
                    .firstOrNull { it.startsWith("http", true) } ?: continue

                if (picked.contains(".m3u8", true)) {
                    M3u8Helper.generateM3u8("Yflix", picked, "$yflixAPI/").forEach(callback)
                    return
                } else if (picked.contains(".mp4", true)) {
                    callback.invoke(
                        newExtractorLink("Yflix", "Yflix", picked, INFER_TYPE) {
                            this.referer = "$yflixAPI/"
                        }
                    )
                    return
                } else {
                    loadExtractor(picked, "$yflixAPI/", subtitleCallback, callback)
                    return
                }
            }
        }

        // Fallback: webview on first pattern with first id
        val fallbackUrl = "$yflixAPI/${patterns.first().format(ids.first())}"
        invokeWebviewEmbedSource("Yflix", fallbackUrl, "$yflixAPI/", yflixAPI, callback)
    }

    private suspend fun invokeWpmovies(
        name: String? = null,
        url: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        fixIframe: Boolean = false,
        encrypt: Boolean = false,
        hasCloudflare: Boolean = false,
        interceptor: Interceptor? = null,
    ) {

        val res = app.get(url ?: return, interceptor = if (hasCloudflare) interceptor else null)
        val referer = getBaseUrl(res.url)
        val document = res.document
        document.select("ul#playeroptionsul > li").map {
            Triple(
                it.attr("data-post"),
                it.attr("data-nume"),
                it.attr("data-type")
            )
        }.amap { (id, nume, type) ->
            val json = app.post(
                url = "$referer/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax", "post" to id, "nume" to nume, "type" to type
                ),
                headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest"),
                referer = url,
                interceptor = if (hasCloudflare) interceptor else null
            ).text
            val source = tryParseJson<ResponseHash>(json)?.let {
                when {
                    encrypt -> {
                        val meta = tryParseJson<Map<String, String>>(it.embed_url)?.get("m")
                            ?: return@amap
                        val key = generateWpKey(it.key ?: return@amap, meta)
                        AesHelper.cryptoAESHandler(
                            it.embed_url,
                            key.toByteArray(),
                            false
                        )?.fixUrlBloat()
                    }

                    fixIframe -> Jsoup.parse(it.embed_url).select("IFRAME").attr("SRC")
                    else -> it.embed_url
                }
            } ?: return@amap
            when {
                source.contains("jeniusplay", true) -> {
                    Jeniusplay2().getUrl(source, "$referer/", subtitleCallback, callback)
                }

                !source.contains("youtube") -> {
                    loadExtractor(source, "$referer/", subtitleCallback, callback)
                }

                else -> {
                    return@amap
                }
            }

        }
    }

    suspend fun invokeMultimovies(
        titleCandidates: List<String>,
        year: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val queries = titleCandidates.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (queries.isEmpty()) return

        val urls = linkedSetOf<String>()
        queries.forEach { query ->
            val slug = query.createSlug() ?: return@forEach
            if (season == null) {
                urls += "$multimoviesAPI/movies/$slug/"
            } else if (episode != null) {
                urls += "$multimoviesAPI/episodes/$slug-${season}x$episode/"
            }
        }

        if (season == null) {
            queries.forEach { query ->
                val searchDocument = app.get("$multimoviesAPI/?s=${query.urlEncodeCompat()}").document
                searchDocument.select("article[id^=post-] a[href*=/movies/]")
                    .mapNotNull { anchor ->
                        val href = anchor.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val article = anchor.parents().firstOrNull { it.tagName().equals("article", true) }
                        val candidateTitle = article?.selectFirst("h3")?.text()?.trim()
                            ?: anchor.attr("title").trim().ifBlank { anchor.text().trim() }
                        val candidateYear = article?.selectFirst("span.wdate, span.year")?.text()?.trim()?.toIntOrNull()
                        SearchMatchCandidate(
                            url = href,
                            title = candidateTitle,
                            year = candidateYear,
                            sourceQuery = query,
                        )
                    }
                    .distinctBy { it.url }
                    .bestSearchMatch(queries, year)
                    ?.url
                    ?.let { urls += it }
            }
        }

        urls.take(2).forEach { url ->
            invokeMultimoviesUrl(url, subtitleCallback, callback)
        }
    }

    private suspend fun invokeMultimoviesUrl(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val response = app.get(url)
        if (!response.isSuccessful) return
        val referer = getBaseUrl(response.url)
        response.document.select("ul#playeroptionsul > li.dooplay_player_option")
            .filterNot { it.attr("data-nume").equals("trailer", true) }
            .map {
                Triple(
                    it.attr("data-post"),
                    it.attr("data-nume"),
                    it.attr("data-type")
                )
            }
            .filter { (id, nume, type) -> id.isNotBlank() && nume.isNotBlank() && type.isNotBlank() }
            .amap { (id, nume, type) ->
                val json = app.post(
                    url = "$referer/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to id,
                        "nume" to nume,
                        "type" to type
                    ),
                    headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest"),
                    referer = url,
                ).text
                val source = tryParseJson<ResponseHash>(json)?.embed_url
                    ?.takeIf { it.isNotBlank() && !it.contains("youtube", true) }
                    ?: return@amap

                var emitted = false
                loadExtractor(source, "$referer/", subtitleCallback) { link ->
                    emitted = true
                    callback.invoke(link)
                }
                if (!emitted) {
                    invokeWebviewEmbedSource(
                        "MultiMovies",
                        source,
                        "$referer/",
                        getBaseUrl(source),
                        callback,
                        useOkhttp = false
                    )
                }
            }
    }

    suspend fun invokeVidsrccc(
        tmdbId: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {

        val url = if (season == null) {
            "$vidsrcccAPI/v2/embed/movie/$tmdbId"
        } else {
            "$vidsrcccAPI/v2/embed/tv/$tmdbId/$season/$episode"
        }

        val script =
            app.get(url).document.selectFirst("script:containsData(userId)")?.data() ?: return

        val userId = script.substringAfter("userId = \"").substringBefore("\";")
        val v = script.substringAfter("v = \"").substringBefore("\";")

        val vrf = VidsrcHelper.encryptAesCbc("$tmdbId", "secret_$userId")

        val serverUrl = if (season == null) {
            "$vidsrcccAPI/api/$tmdbId/servers?id=$tmdbId&type=movie&v=$v&vrf=$vrf&imdbId=$imdbId"
        } else {
            "$vidsrcccAPI/api/$tmdbId/servers?id=$tmdbId&type=tv&v=$v&vrf=$vrf&imdbId=$imdbId&season=$season&episode=$episode"
        }

        app.get(serverUrl).parsedSafe<VidsrcccResponse>()?.data?.amap {
            val sources =
                app.get("$vidsrcccAPI/api/source/${it.hash}").parsedSafe<VidsrcccResult>()?.data
                    ?: return@amap

            when {
                it.name.equals("VidPlay") -> {

                    callback.invoke(
                        newExtractorLink(
                            "VidPlay",
                            "VidPlay",
                            sources.source ?: return@amap,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$vidsrcccAPI/"
                        }
                    )

                    sources.subtitles?.map {
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                it.label ?: return@map,
                                it.file ?: return@map
                            )
                        )
                    }
                }

                it.name.equals("UpCloud") -> {
                    val scriptData = app.get(
                        sources.source ?: return@amap,
                        referer = "$vidsrcccAPI/"
                    ).document.selectFirst("script:containsData(source =)")?.data()
                    val iframe = Regex("source\\s*=\\s*\"([^\"]+)").find(
                        scriptData ?: return@amap
                    )?.groupValues?.get(1)?.fixUrlBloat()

                    val iframeRes =
                        app.get(iframe ?: return@amap, referer = "https://lucky.vidbox.site/").text

                    val id = iframe.substringAfterLast("/").substringBefore("?")
                    val key = Regex("\\w{48}").find(iframeRes)?.groupValues?.get(0) ?: return@amap

                    app.get(
                        "${iframe.substringBeforeLast("/")}/getSources?id=$id&_k=$key",
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                        ),
                        referer = iframe
                    ).parsedSafe<UpcloudResult>()?.sources?.amap file@{ source ->
                        callback.invoke(
                            newExtractorLink(
                                "UpCloud",
                                "UpCloud",
                                source.file ?: return@file,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$vidsrcccAPI/"
                            }
                        )
                    }

                }

                else -> {
                    return@amap
                }
            }
        }


    }

    suspend fun invokeVidsrc(
        tmdbId: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val api = "https://cloudnestra.com"
        val imdbUrl = imdbId?.takeIf { it.isNotBlank() }?.let {
            if (season == null) {
                "$vidSrcAPI/embed/movie?imdb=$it"
            } else {
                "$vidSrcAPI/embed/tv?imdb=$it&season=$season&episode=$episode"
            }
        }
        val legacyUrl = tmdbId?.let {
            if (season == null) {
                "$vidSrcAPI/embed/$it"
            } else {
                "$vidSrcAPI/embed/$it/${season}-${episode}"
            }
        }
        val vsembedUrl = tmdbId?.let {
            if (season == null) {
                "https://vsembed.ru/embed/$it"
            } else {
                "https://vsembed.ru/embed/$it/${season}-${episode}"
            }
        }
        val vidsrcMeUrl = tmdbId?.let {
            if (season == null) {
                "$vidsrcMeAPI/embed/movie/$it"
            } else {
                "$vidsrcMeAPI/embed/tv/$it/$season/$episode"
            }
        }
        val candidateUrls = listOfNotNull(imdbUrl, legacyUrl, vsembedUrl, vidsrcMeUrl).distinct()

        if (candidateUrls.any { loadVidsrcXpass(it, season != null, "$vidSrcAPI/", callback) }) return

        val primaryUrl = imdbUrl ?: legacyUrl ?: vidsrcMeUrl ?: return
        val document = app.get(primaryUrl).document
        val playerIframe = document.selectFirst("iframe#player_iframe")?.attr("src")
            ?.let { iframe ->
                if (iframe.startsWith("//")) "https:$iframe" else iframe
            }

        val rcpPath = when {
            !playerIframe.isNullOrBlank() && playerIframe.contains("/rcp/") -> playerIframe
            else -> document.select(".serversList .server")
                .firstOrNull { it.text().equals("CloudStream Pro", ignoreCase = true) }
                ?.attr("data-hash")
                ?.takeIf { it.isNotBlank() }
                ?.let { "$api/rcp/$it" }
        } ?: return

        val hash = app.get(rcpPath, referer = primaryUrl).text
            .substringAfter("/prorcp/")
            .substringBefore("'")
            .ifBlank { return }

        val res = app.get("$api/prorcp/$hash", referer = "$api/").text
        val m3u8Link = Regex("""https:.*?\.m3u8[^"'\\\s]*""").find(res)?.value ?: return
        val streamHeaders = mapOf(
            "Accept" to "*/*",
            "Referer" to "$api/",
            "Origin" to api,
            "User-Agent" to USER_AGENT,
        )

        val generatedLinks = M3u8Helper.generateM3u8(
            "Vidsrc",
            m3u8Link,
            "$api/",
            headers = streamHeaders,
        )
        if (generatedLinks.isNotEmpty()) {
            generatedLinks.forEach(callback)
            return
        }

        callback.invoke(
            newExtractorLink(
                "Vidsrc",
                "Vidsrc",
                m3u8Link,
                ExtractorLinkType.M3U8
            ) {
                this.referer = "$api/"
                this.headers = streamHeaders
            }
        )

    }

    suspend fun invokeVidsrcme(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = tmdbId ?: return
        val embedUrl = if (season == null) {
            "$vidsrcMeAPI/embed/movie/$id"
        } else {
            "$vidsrcMeAPI/embed/tv/$id/$season/$episode"
        }

        if (loadVidsrcXpass(embedUrl, season != null, "$vidsrcMeAPI/", callback)) return

        var emitted = false
        loadExtractor(embedUrl, "$vidsrcMeAPI/", subtitleCallback) { link ->
            emitted = true
            callback.invoke(link)
        }

        if (emitted) return

        invokeWebviewEmbedSource(
            "VidSrcMe",
            embedUrl,
            "$vidsrcMeAPI/",
            vidsrcMeAPI,
            callback,
            useOkhttp = false
        )
    }

    suspend fun invokeAzmovies(
        titleCandidates: List<String>,
        year: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val queries = titleCandidates.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (queries.isEmpty()) return

        val targetUrl = queries.firstNotNullOfOrNull { query ->
            val searchUrl =
                "$azmoviesAPI/search?q=${query.urlEncodeCompat()}&year_from=0&year_to=0&rating_from=0&rating_to=10&sort=featured"
            val searchDocument = requestAzMoviesPage(searchUrl).document
            searchDocument.select("#movies-container a.poster")
                .mapNotNull { element ->
                    val href = element.attr("href").let { fixUrl(it, azmoviesAPI) }
                    val candidateTitle = element.selectFirst("span.poster__title")?.text()?.trim()
                    val candidateYear = element.selectFirst("span.badge")?.text()?.trim()?.toIntOrNull()
                    SearchMatchCandidate(
                        url = href,
                        title = candidateTitle,
                        year = candidateYear,
                        sourceQuery = query,
                    )
                }.bestSearchMatch(queries, year)?.url
        } ?: return

        val response = requestAzMoviesPage(targetUrl)
        val seenSubtitles = linkedSetOf<String>()
        extractAzMoviesServerButtons(response.text, response.document).forEach { button ->
            val rawUrl = button.url.replace("&amp;", "&").trim()
            if (rawUrl.isBlank()) return@forEach

            extractInlineSubtitle(rawUrl)?.let { subtitle ->
                if (seenSubtitles.add(subtitle.url)) subtitleCallback(subtitle)
            }

            runCatching {
                if (!loadVidsrcXpass(rawUrl, false, "$azmoviesAPI/", callback)) {
                    loadExtractor(rawUrl, "$azmoviesAPI/", subtitleCallback, callback)
                }
            }.onFailure {
                loadExtractor(rawUrl, "$azmoviesAPI/", subtitleCallback, callback)
            }
        }
    }

    suspend fun invokeNoxx(
        titleCandidates: List<String>,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val queries = titleCandidates.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (queries.isEmpty()) return
        val targetSeason = season ?: return
        val targetEpisode = episode ?: return
        val showUrl = queries.firstNotNullOfOrNull { query ->
            val searchUrl = "$noxxAPI/browse?q=${query.urlEncodeCompat()}"
            val searchDocument = requestNoxxPage(searchUrl).document
            searchDocument.select("a.poster-card")
                .mapNotNull { card ->
                    val href = card.attr("href").let { fixUrl(it, noxxAPI) }
                    val candidateTitle = card.selectFirst("img")?.attr("alt")?.trim()
                        ?: card.selectFirst("span")?.text()?.trim()
                    SearchMatchCandidate(
                        url = href,
                        title = candidateTitle,
                        sourceQuery = query,
                    )
                }.bestSearchMatch(queries)?.url
        } ?: return

        val showDocument = requestNoxxPage(showUrl).document
        val episodeUrl = showDocument.select("a.episode-card")
            .mapNotNull { card ->
                val href = card.attr("href").let { fixUrl(it, noxxAPI) }
                val match = Regex("""/tv/[^/]+/(\d+)/(\d+)""").find(href) ?: return@mapNotNull null
                val cardSeason = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val cardEpisode = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                Triple(href, cardSeason, cardEpisode)
            }.firstOrNull { (_, cardSeason, cardEpisode) ->
                cardSeason == targetSeason && cardEpisode == targetEpisode
            }?.first
            ?: return

        val episodeResponse = requestNoxxPage(episodeUrl)
        val seenSubtitles = linkedSetOf<String>()
        episodeResponse.document.select("#serverselector button[value], button.sch[value]").forEach { button ->
            val rawUrl = button.attr("value").replace("&amp;", "&").trim()
            if (rawUrl.isBlank()) return@forEach

            extractInlineSubtitle(rawUrl)?.let { subtitle ->
                if (seenSubtitles.add(subtitle.url)) subtitleCallback(subtitle)
            }

            runCatching {
                if (!loadVidsrcXpass(rawUrl, true, "$noxxAPI/", callback)) {
                    loadExtractor(rawUrl, "$noxxAPI/", subtitleCallback, callback)
                }
            }.onFailure {
                loadExtractor(rawUrl, "$noxxAPI/", subtitleCallback, callback)
            }
        }
    }

    suspend fun invokeWatchsomuch(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val id = imdbId?.removePrefix("tt")
        val epsId = app.post(
            "${watchSomuchAPI}/Watch/ajMovieTorrents.aspx", data = mapOf(
                "index" to "0",
                "mid" to "$id",
                "wsk" to "30fb68aa-1c71-4b8c-b5d4-4ca9222cfb45",
                "lid" to "",
                "liu" to ""
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<WatchsomuchResponses>()?.movie?.torrents?.let { eps ->
            if (season == null) {
                eps.firstOrNull()?.id
            } else {
                eps.find { it.episode == episode && it.season == season }?.id
            }
        } ?: return

        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)

        val subUrl = if (season == null) {
            "${watchSomuchAPI}/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part="
        } else {
            "${watchSomuchAPI}/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part=S${seasonSlug}E${episodeSlug}"
        }

        app.get(subUrl).parsedSafe<WatchsomuchSubResponses>()?.subtitles?.map { sub ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    sub.label?.substringBefore("&nbsp")?.trim() ?: "",
                    fixUrl(sub.url ?: return@map null, watchSomuchAPI)
                )
            )
        }


    }

    suspend fun invokeVidlink(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val type = if (season == null) "movie" else "tv"
        val url = if (season == null) {
            "$vidlinkAPI/$type/$tmdbId"
        } else {
            "$vidlinkAPI/$type/$tmdbId/$season/$episode"
        }

        val videoLink = app.get(
            url, interceptor = WebViewResolver(
                Regex("""$vidlinkAPI/api/b/$type/A{32}"""), timeout = 15_000L
            )
        ).parsedSafe<VidlinkSources>()?.stream?.playlist

        callback.invoke(
            newExtractorLink(
                "Vidlink",
                "Vidlink",
                videoLink ?: return,
                ExtractorLinkType.M3U8
            ) {
                this.referer = "$vidlinkAPI/"
            }
        )

    }

    suspend fun invokeVidfast(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val module = "hezushon/bunafmin/1000098709565419/lu/40468dfa/de97f995ef83714e8ce88dc789c1c1acc4760231/y"
        val type = if (season == null) "movie" else "tv"
        val url = if (season == null) {
            "$vidfastAPI/$type/$tmdbId"
        } else {
            "$vidfastAPI/$type/$tmdbId/$season/$episode"
        }

        val res = app.get(
            url, interceptor = WebViewResolver(
                Regex("""$vidfastAPI/$module/LAk"""),
                timeout = 15_000L
            )
        ).text

        tryParseJson<ArrayList<VidFastServers>>(res)?.filter { it.description?.contains("Original audio") == true }
            ?.amapIndexed { index, server ->
                val source =
                    app.get("$vidfastAPI/$module/N8b-ENGCMKNz/${server.data}", referer = "$vidfastAPI/")
                        .parsedSafe<VidFastSources>()

                callback.invoke(
                    newExtractorLink(
                        "Vidfast",
                        "Vidfast [${server.name}]",
                        source?.url ?: return@amapIndexed,
                        INFER_TYPE
                    )
                )

                if (index == 1) {
                    source.tracks?.map { subtitle ->
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                subtitle.label ?: return@map,
                                subtitle.file ?: return@map
                            )
                        )
                    }
                }

            }


    }

    suspend fun invokeVideasy(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = tmdbId ?: return
        val url = if (season == null) {
            "$videasyAPI/movie/$id"
        } else {
            "$videasyAPI/tv/$id/$season/$episode"
        }

        invokeWebviewEmbedSource("Videasy", url, "$videasyAPI/", videasyAPI, callback, useOkhttp = false)
    }

    suspend fun invokeVidzen(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = tmdbId ?: return
        val url = if (season == null) {
            "$vidzenAPI/movie/$id"
        } else {
            "$vidzenAPI/tv/$id/$season/$episode"
        }

        invokeWebviewEmbedSource("VidZen", url, "$vidzenAPI/", vidzenAPI, callback, useOkhttp = false)
    }

    suspend fun invokeCinezo(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = tmdbId ?: return
        val url = if (season == null) {
            "$cinezoAPI/embed/movie/$id"
        } else {
            "$cinezoAPI/embed/tv/$id/$season/$episode"
        }

        invokeWebviewEmbedSource("Cinezo", url, "$cinezoAPI/", cinezoAPI, callback, useOkhttp = false)
    }

    suspend fun invokeHiAnime(
        malId: Int?,
        titleCandidates: List<String>,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val ep = episode ?: 1
        val urls = buildList {
            malId?.let { id ->
                val json = runCatching {
                    app.get(
                        "$hiAnimeAPI/ajax/mal?mal=$id&ep=$ep&ts=${System.currentTimeMillis() / 1000}",
                        referer = "$hiAnimeAPI/",
                        headers = animeBrowserHeaders(hiAnimeAPI)
                    ).text
                }.getOrNull()
                collectHiAnimeMalUrls(json).forEach { add(it) }
            }

            val match = findHiAnimeTitle(titleCandidates) ?: return@buildList
            val watchUrl = match.url.replace(Regex("""/ep-\d+/?$"""), "/ep-$ep")
            add(watchUrl)
        }.distinct()

        urls.forEach { url ->
            loadAnimeEmbed("HiAnime", url, "$hiAnimeAPI/", hiAnimeAPI, subtitleCallback, callback)
        }
    }

    suspend fun invokeGogoAnime(
        titleCandidates: List<String>,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val match = findWordPressAnimeTitle(
            gogoAnimeAPI,
            titleCandidates,
            "a[href*='/anime/'][title]",
            "title",
        ) ?: return
        val slug = match.url.trimEnd('/').substringAfterLast("/")
        val episodeUrl = "$gogoAnimeAPI/$slug-episode-${episode ?: 1}/"
        val urls = collectWordPressEmbedUrls(episodeUrl, gogoAnimeAPI)

        urls.forEach { url ->
            loadAnimeEmbed("GogoAnime", url, episodeUrl, gogoAnimeAPI, subtitleCallback, callback)
        }
    }

    suspend fun invokeAnimePahe(
        titleCandidates: List<String>,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val match = findWordPressAnimeTitle(
            animePaheAPI,
            titleCandidates,
            "a[href*='/series/'][title]",
            "title",
        ) ?: return
        val seriesDocument = app.get(match.url, headers = animeBrowserHeaders(animePaheAPI)).document
        val ep = episode ?: 1
        val episodeUrl = seriesDocument.select("a[href]")
            .firstOrNull { link ->
                val href = link.absUrl("href")
                href.contains("-episode-$ep-", true) ||
                    href.contains("-episode-$ep/", true) ||
                    link.selectFirst(".epl-num, .epcur")?.text()?.trim()?.equals(ep.toString(), true) == true
            }?.absUrl("href") ?: return

        val urls = collectWordPressEmbedUrls(episodeUrl, animePaheAPI)
        urls.forEach { url ->
            loadAnimeEmbed("AnimePahe", url, episodeUrl, animePaheAPI, subtitleCallback, callback)
        }
    }

    private data class AnimeTitleMatch(val title: String, val url: String)

    private suspend fun findHiAnimeTitle(titleCandidates: List<String>): AnimeTitleMatch? {
        titleCandidates.forEach { query ->
            val document = app.get(
                "$hiAnimeAPI/search?keyword=${query.urlEncodeCompat()}",
                referer = "$hiAnimeAPI/home",
                headers = animeBrowserHeaders(hiAnimeAPI)
            ).document
            document.select("a.d-title[href]").forEach { link ->
                val title = link.text().trim()
                val jpTitle = link.attr("data-jp").trim()
                if (animeTitleMatches(title, titleCandidates) || animeTitleMatches(jpTitle, titleCandidates)) {
                    return AnimeTitleMatch(title, fixUrl(link.attr("href"), hiAnimeAPI))
                }
            }
        }
        return null
    }

    private suspend fun findWordPressAnimeTitle(
        baseUrl: String,
        titleCandidates: List<String>,
        selector: String,
        titleAttribute: String,
    ): AnimeTitleMatch? {
        titleCandidates.forEach { query ->
            val document = app.get(
                "$baseUrl/?s=${query.urlEncodeCompat()}",
                referer = "$baseUrl/",
                headers = animeBrowserHeaders(baseUrl)
            ).document
            document.select(selector).forEach { link ->
                val title = link.attr(titleAttribute).ifBlank { link.text() }.trim()
                if (animeTitleMatches(title, titleCandidates)) {
                    return AnimeTitleMatch(title, link.absUrl("href").ifBlank { fixUrl(link.attr("href"), baseUrl) })
                }
            }
        }
        return null
    }

    private fun collectHiAnimeMalUrls(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        if (root.optInt("status") != 200) return emptyList()
        val result = root.optJSONObject("result") ?: root
        return buildList {
            result.keys().forEach { provider ->
                val item = result.optJSONObject(provider) ?: return@forEach
                listOf("sub", "dub", "raw").forEach { type ->
                    item.optJSONObject(type)?.optString("url")?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
    }

    private suspend fun collectWordPressEmbedUrls(episodeUrl: String, baseUrl: String): List<String> {
        val document = app.get(episodeUrl, referer = "$baseUrl/", headers = animeBrowserHeaders(baseUrl)).document
        return buildList {
            document.select("[data-video]").forEach { element ->
                decodeIframeUrl(element.attr("data-video"), baseUrl)?.let { add(it) }
            }
            document.select("iframe[src], iframe[data-src]").forEach { iframe ->
                iframe.absUrl("src").ifBlank { iframe.absUrl("data-src") }
                    .ifBlank { fixUrl(iframe.attr("src").ifBlank { iframe.attr("data-src") }, baseUrl) }
                    .takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }.distinct()
    }

    private suspend fun loadAnimeEmbed(
        sourceName: String,
        embedUrl: String,
        referer: String,
        origin: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val fixedUrl = fixUrl(embedUrl, origin)
        var emitted = false
        loadExtractor(fixedUrl, referer, subtitleCallback) { link ->
            emitted = true
            callback(link)
        }
        if (emitted) return

        val nestedUrl = runCatching {
            val document = app.get(fixedUrl, referer = referer, headers = animeBrowserHeaders(origin)).document
            document.selectFirst("iframe[src], iframe[data-src]")
                ?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }
        }.getOrNull()
        if (!nestedUrl.isNullOrBlank() && nestedUrl != fixedUrl) {
            loadExtractor(nestedUrl, fixedUrl, subtitleCallback) { link ->
                emitted = true
                callback(link)
            }
        }
        if (!emitted) {
            invokeWebviewEmbedSource(sourceName, nestedUrl ?: fixedUrl, referer, origin, callback, useOkhttp = false)
        }
    }

    private fun decodeIframeUrl(rawHtml: String, baseUrl: String): String? {
        val decoded = Jsoup.parse(rawHtml).text()
        return Jsoup.parse(decoded, baseUrl).selectFirst("iframe[src], iframe[data-src]")
            ?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }
            ?.takeIf { it.isNotBlank() }
    }

    private fun animeTitleMatches(title: String?, candidates: List<String>): Boolean {
        val normalizedTitle = normalizeAnimeTitle(title)
        if (normalizedTitle.isBlank()) return false
        return candidates.any { normalizeAnimeTitle(it) == normalizedTitle }
    }

    private fun normalizeAnimeTitle(title: String?): String {
        return title.orEmpty()
            .lowercase()
            .replace(Regex("""\[[^\]]*]|\([^)]*\)"""), " ")
            .replace(Regex("""\b(?:english|subbed|dubbed|sub|dub|hd)\b"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private fun animeBrowserHeaders(origin: String): Map<String, String> {
        return mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Origin" to origin,
            "Referer" to "$origin/",
            "User-Agent" to USER_AGENT,
        )
    }

    suspend fun invokeMapple(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = tmdbId ?: return
        val url = if (season == null) {
            "$mappleAPI/watch/movie/$id"
        } else {
            "$mappleAPI/watch/tv/$id-$season-$episode"
        }

        invokeWebviewEmbedSource("Mapple", url, "$mappleAPI/", mappleAPI, callback, useOkhttp = false)
    }

    suspend fun invokeCinemaos(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = tmdbId ?: return
        val url = if (season == null) {
            "$cinemaosAPI/player/$id"
        } else {
            "$cinemaosAPI/player/$id/$season/$episode"
        }

        invokeWebviewEmbedSource("CinemaOS", url, "$cinemaosAPI/", cinemaosAPI, callback, useOkhttp = false)
    }

    suspend fun invokeXprime(
        tmdbId: Int?,
        imdbId: String?,
        title: String?,
        year: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = tmdbId ?: return
        val token = app.get(
            "https://enc-dec.app/api/enc-xprime",
            headers = mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT),
        ).text.let { JSONObject(it).optString("result") }.takeIf { it.isNotBlank() } ?: return

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Origin" to "https://xprime.tv",
            "Referer" to "https://xprime.tv/",
            "Accept" to "application/json,text/plain,*/*",
        )
        val encodedToken = token.urlEncodeCompat()
        val servers = buildList {
            val query = buildString {
                append("name=${(title ?: "").urlEncodeCompat()}")
                year?.let { append("&fallback_year=$it") }
                if (season != null && episode != null) append("&season=$season&episode=$episode")
            }
            add("primebox" to "$xprimeAPI/primebox?$query&turnstile=$encodedToken")
            if (season == null || episode != null) {
                val rageQuery = if (season == null) {
                    "id=$id"
                } else {
                    "id=$id&season=$season&episode=$episode"
                }
                add("rage" to "$xprimeAPI/rage?$rageQuery&turnstile=$encodedToken")
            }
        }

        servers.forEach { (server, url) ->
            val responseText = runCatching { app.get(url, headers = headers).text }.getOrNull() ?: return@forEach
            val data = if (responseText.trim().startsWith("{")) {
                JSONObject(responseText)
            } else {
                decryptXprimeResponse(responseText) ?: return@forEach
            }
            emitXprimeLinks(server, data, headers, subtitleCallback, callback)
        }
    }

    private suspend fun decryptXprimeResponse(text: String): JSONObject? {
        val response = app.post(
            "https://enc-dec.app/api/dec-xprime",
            requestBody = JSONObject(mapOf("text" to text)).toString()
                .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull()),
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "User-Agent" to USER_AGENT,
            ),
        ).text
        val json = JSONObject(response)
        if (json.optInt("status") != 200) return null
        val result = json.opt("result") ?: return null
        return when (result) {
            is JSONObject -> result
            is String -> JSONObject(result)
            else -> null
        }
    }

    private suspend fun emitXprimeLinks(
        server: String,
        data: JSONObject,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val sourceName = "XPrime ${server.replaceFirstChar { it.uppercase() }}"
        data.optJSONArray("subtitles")?.let { subtitles ->
            for (index in 0 until subtitles.length()) {
                val subtitle = subtitles.optJSONObject(index) ?: continue
                val file = subtitle.optString("file").takeIf { it.isNotBlank() } ?: continue
                subtitleCallback.invoke(newSubtitleFile(subtitle.optString("label").ifBlank { "Unknown" }, file))
            }
        }

        if (server == "primebox") {
            val streams = data.optJSONObject("streams")
            val qualities = data.optJSONArray("available_qualities") ?: JSONArray()
            if (streams != null && qualities.length() > 0) {
                for (index in 0 until qualities.length()) {
                    val quality = qualities.optString(index)
                    emitXprimeLink(sourceName, quality, streams.optString(quality), headers, callback)
                }
                return
            }
        }

        data.optJSONArray("qualities")?.let { qualities ->
            for (index in 0 until qualities.length()) {
                val item = qualities.optJSONObject(index) ?: continue
                emitXprimeLink(sourceName, item.optString("quality"), item.optString("url"), headers, callback)
            }
        }

        emitXprimeLink(sourceName, data.optString("quality"), data.optString("url"), headers, callback)
    }

    private suspend fun emitXprimeLink(
        sourceName: String,
        qualityText: String?,
        url: String?,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
    ) {
        val streamUrl = url?.trim()?.takeIf { it.startsWith("http", true) } ?: return
        val quality = getIndexQuality(qualityText ?: streamUrl)
        if (streamUrl.contains(".m3u8", true)) {
            M3u8Helper.generateM3u8(sourceName, streamUrl, "https://xprime.tv/", headers = headers)
                .forEach(callback)
            return
        }

        callback.invoke(
            newExtractorLink(sourceName, sourceName, streamUrl, INFER_TYPE) {
                this.quality = quality
                this.referer = "https://xprime.tv/"
                this.headers = headers
            }
        )
    }

    suspend fun invokeWave(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = tmdbId ?: return
        val path = if (season == null) {
            "movie/$id"
        } else {
            "tv/$id/$season/$episode"
        }
        val servers = listOf(
            "1" to "Wave VidKing",
            "3" to "Wave Vidora",
            "4" to "Wave Vidrock",
            "5" to "Wave Vidnest",
            "6" to "Wave 111movies",
        )

        servers.forEach { (api, sourceName) ->
            val url = "$waveAPI/$path?api=$api"
            val page = runCatching {
                app.get(url, headers = mapOf("User-Agent" to USER_AGENT), timeout = 15)
            }.getOrNull()
            val iframe = page?.document?.selectFirst("iframe[src]")?.attr("abs:src")
                ?.takeIf { it.isNotBlank() && !it.contains("vidsrc", true) }

            if (!iframe.isNullOrBlank()) {
                var emitted = false
                loadExtractor(iframe, "$waveAPI/", { _: SubtitleFile -> }) { link ->
                    emitted = true
                    callback.invoke(link)
                }
                if (emitted) return@forEach

                invokeWebviewEmbedSource(
                    sourceName,
                    iframe,
                    "$waveAPI/",
                    getBaseUrl(iframe),
                    callback,
                    useOkhttp = false
                )
            } else {
                invokeWebviewEmbedSource(sourceName, url, "$waveAPI/", waveAPI, callback, useOkhttp = false)
            }
        }
    }

    suspend fun invokeUhdmovies(
        titleCandidates: List<String>,
        year: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val queries = titleCandidates.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (queries.isEmpty()) return

        val detailUrl = queries.firstNotNullOfOrNull { query ->
            val searchDocument = app.get("$uhdmoviesAPI/search/${query.urlEncodeCompat()}").document
            searchDocument.select("article.gridlove-post a[href*=/download-]")
                .mapNotNull { anchor ->
                    val href = anchor.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val candidateTitle = anchor.attr("title").ifBlank {
                        anchor.selectFirst("h1.sanket")?.text().orEmpty()
                    }.ifBlank { anchor.text() }
                    SearchMatchCandidate(
                        url = href,
                        title = candidateTitle.removePrefix("Download").trim(),
                        year = Regex("""\((\d{4})\)""").find(candidateTitle)?.groupValues?.get(1)?.toIntOrNull(),
                        sourceQuery = query,
                    )
                }.distinctBy { it.url }
                .bestSearchMatch(queries, year)?.url
        } ?: run {
            val slug = queries.first().createSlug() ?: return
            if (season == null && year != null) "$uhdmoviesAPI/download-$slug-$year" else "$uhdmoviesAPI/download-$slug"
        }

        val detailDocument = app.get(detailUrl).document
        val links = detailDocument.select("div.entry-content a[href*=cloud.unblockedgames.world]")
            .mapNotNull { anchor ->
                val href = anchor.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val ownText = anchor.text().trim()
                val context = listOfNotNull(
                    anchor.parent()?.text(),
                    anchor.parent()?.previousElementSibling()?.text(),
                    anchor.parent()?.previousElementSibling()?.previousElementSibling()?.text(),
                ).joinToString(" ").cleanUhdText()

                if (season == null && !ownText.contains("Download", true)) return@mapNotNull null
                if (season != null) {
                    val seasonSlug = season.toString().padStart(2, '0')
                    val episodeSlug = episode?.toString()?.padStart(2, '0')
                    val episodeMatch = episodeSlug == null ||
                        ownText.contains("Episode $episode", true) ||
                        ownText.contains("E$episodeSlug", true) ||
                        context.contains("E$episodeSlug", true)
                    val seasonMatch = context.contains("S$seasonSlug", true) ||
                        context.contains("Season $season", true) ||
                        context.contains("Season $seasonSlug", true)
                    if (!seasonMatch || !episodeMatch) return@mapNotNull null
                }
                if (!Regex("""(?i)(2160p|1080p|720p|4k)""").containsMatchIn(context)) return@mapNotNull null
                UhdMoviesCandidate(context, href)
            }
            .distinctBy { it.url }
            .take(3)

        links.forEach { candidate ->
            val directUrl = runCatching { resolveUhdmoviesDownload(candidate.url) }
                .onFailure { logError(it) }
                .getOrNull() ?: return@forEach
            val quality = getIndexQuality(candidate.context)
            val tags = getUhdTags(candidate.context)
            val size = getIndexSize(candidate.context)
            callback.invoke(
                newExtractorLink(
                    "UHDMovies",
                    "UHDMovies $tags [$size]",
                    directUrl,
                    if (directUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else INFER_TYPE
                ) {
                    this.quality = quality
                    this.referer = "$uhdmoviesAPI/"
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                }
            )
        }
    }

    private data class UhdMoviesCandidate(
        val context: String,
        val url: String,
    )

    private fun String.cleanUhdText(): String {
        return replace('\u00a0', ' ').replace(Regex("""\s+"""), " ").trim()
    }

    private suspend fun resolveUhdmoviesDownload(url: String): String? {
        val firstUrl = if (url.contains("cloud.unblockedgames.world", true)) {
            bypassUhdCloud(url)
        } else {
            url
        } ?: return null

        if (firstUrl.contains("uhdmovies.", true)) return null
        if (!firstUrl.contains("driveseed.", true)) return firstUrl

        val firstResponse = app.get(firstUrl, referer = "$uhdmoviesAPI/")
        val replacedPath = firstResponse.text.substringAfter("window.location.replace(\"", "")
            .substringBefore("\")", "")
        val fileUrl = if (replacedPath.isNotBlank()) {
            fixUrl(replacedPath, getBaseUrl(firstUrl))
        } else {
            firstUrl
        }

        val fileResponse = if (fileUrl == firstUrl) firstResponse else app.get(fileUrl, referer = firstUrl)
        return fileResponse.document
            .selectFirst("a.btn.btn-danger:contains(Instant Download), a.btn-danger:contains(Instant Download)")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?: fileUrl
    }

    private suspend fun bypassUhdCloud(url: String): String? {
        val sid = java.net.URI(url).rawQuery
            ?.split("&")
            ?.firstOrNull { it.startsWith("sid=") }
            ?.substringAfter("sid=")
            ?: return null
        val host = getBaseUrl(url)
        var document = app.post("$host/", data = mapOf("_wp_http" to sid), referer = "$uhdmoviesAPI/").document
        val form = document.selectFirst("form#landing") ?: return null
        val formUrl = form.attr("abs:action").ifBlank { fixUrl(form.attr("action"), host) }
        val formData = form.select("input[name]").associate { it.attr("name") to it.attr("value") }

        document = app.post(formUrl, data = formData, referer = "$host/").document
        val script = document.selectFirst("script:containsData(?go=)")?.data()
            ?: document.select("script").joinToString("\n") { it.data() }
        val go = Regex("""\?go=([^"'\s]+)""").find(script)?.groupValues?.get(1) ?: return null
        val cookieValue = Regex("""s_343\('$go',\s*'([^']+)'""").find(script)?.groupValues?.get(1)
            ?: formData["_wp_http2"]
            ?: return null

        val redirectDocument = app.get(
            "$host/?go=$go",
            cookies = mapOf(go to cookieValue),
            referer = formUrl
        ).document
        return redirectDocument.selectFirst("meta[http-equiv=refresh]")
            ?.attr("content")
            ?.substringAfter("url=", "")
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun invokeWyzie(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val url = if (season == null) {
            "$wyzieAPI/search?id=$tmdbId"
        } else {
            "$wyzieAPI/search?id=$tmdbId&season=$season&episode=$episode"
        }

        val res = app.get(url).text

        tryParseJson<ArrayList<WyzieSubtitle>>(res)?.map { subtitle ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    subtitle.display ?: return@map,
                    subtitle.url ?: return@map,
                )
            )
        }

    }

    suspend fun invokeCineSrc(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = if (season == null) {
            "$cinesrcAPI/embed/movie/$tmdbId"
        } else {
            "$cinesrcAPI/embed/tv/$tmdbId?s=$season&e=$episode"
        }

        val mediaRes = app.get(
            url,
            interceptor = WebViewResolver(
                Regex("""https?://[^"'\\s]+?\.(?:m3u8|mp4)(?:\?[^"'\\s]*)?"""),
                timeout = 20_000L
            )
        )

        val mediaUrl = mediaRes.url
        if (!isReliableCineSrcMediaUrl(mediaUrl)) return
        val mediaType = when {
            mediaUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            mediaUrl.contains(".mp4", ignoreCase = true) -> INFER_TYPE
            else -> return
        }

        callback.invoke(
            newExtractorLink(
                "CineSrc",
                "CineSrc",
                mediaUrl,
                mediaType
            ) {
                this.referer = "$cinesrcAPI/"
                this.headers = mapOf(
                    "Accept" to "*/*",
                    "Referer" to "$cinesrcAPI/",
                    "Origin" to cinesrcAPI,
                    "User-Agent" to USER_AGENT,
                )
            }
        )
    }

    private suspend fun invokeWebviewEmbedSource(
        sourceName: String,
        pageUrl: String,
        referer: String,
        origin: String,
        callback: (ExtractorLink) -> Unit,
        useOkhttp: Boolean = true,
    ): Boolean {
        var emitted = false
        val mediaRes = app.get(
            pageUrl,
            interceptor = WebViewResolver(
                Regex("""https?://[^"'\\s]+?\.(?:m3u8|mp4)(?:\?[^"'\\s]*)?"""),
                useOkhttp = useOkhttp,
                timeout = 20_000L
            )
        )

        val mediaUrl = mediaRes.url
        if (mediaUrl.contains("vidsrc", true)) return false
        val mediaType = when {
            mediaUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            mediaUrl.contains(".mp4", ignoreCase = true) -> INFER_TYPE
            else -> null
        }

        if (mediaType != null) {
            emitted = true
            callback.invoke(
                newExtractorLink(
                    sourceName,
                    sourceName,
                    mediaUrl,
                    mediaType
                ) {
                    this.referer = referer
                    this.headers = mapOf(
                        "Accept" to "*/*",
                        "Referer" to referer,
                        "Origin" to origin,
                        "User-Agent" to USER_AGENT,
                    )
                }
            )
            return true
        }

        extractPlayableUrlFromHtml(mediaRes.text, getBaseUrl(mediaRes.url))?.let { nestedUrl ->
            val normalizedUrl = nestedUrl.fixUrlBloat()
            if (normalizedUrl.contains("vidsrc", true)) return@let
            if (normalizedUrl.contains(".m3u8", true) || normalizedUrl.contains(".mp4", true)) {
                emitted = true
                callback.invoke(
                    newExtractorLink(
                        sourceName,
                        sourceName,
                        normalizedUrl,
                        if (normalizedUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        this.referer = referer
                        this.headers = mapOf(
                            "Accept" to "*/*",
                            "Referer" to referer,
                            "Origin" to origin,
                            "User-Agent" to USER_AGENT,
                        )
                    }
                )
            } else {
                loadExtractor(normalizedUrl, referer, { _: SubtitleFile -> }) { link ->
                    emitted = true
                    callback.invoke(link)
                }
            }
        }

        return emitted
    }

    suspend fun invokeMafiaEmbed(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = if (season == null) {
            "$mafiaEmbedAPI/embed/movie/$tmdbId"
        } else {
            "$mafiaEmbedAPI/embed/tv/$tmdbId/$season/$episode"
        }

        invokeWebviewEmbedSource(
            "MafiaEmbed",
            url,
            "$mafiaEmbedAPI/",
            mafiaEmbedAPI,
            callback
        )
    }

    suspend fun invokeAutoEmbed(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = tmdbId ?: return
        val referer = "$autoEmbedAPI/"
        val isTv = season != null
        val candidateUrls = if (!isTv) {
            listOf(
                "$autoEmbedAPI/movie/$id/watch",
                "$autoEmbedAPI/movie/$id",
                "$autoEmbedAPI/movie/tmdb/$id",
            )
        } else {
            val ep = episode ?: return
            buildList {
                add("$autoEmbedAPI/tv/$id/watch?season=$season&episode=$ep")
                add("$autoEmbedAPI/tv/$id/watch?s=$season&e=$ep")
                add("$autoEmbedAPI/tv/$id/$season/$ep/watch")
                add("$autoEmbedAPI/tv/$id/$season/$ep")
                add("$autoEmbedAPI/tv/$id/watch")
                add("$autoEmbedAPI/tv/tmdb/$id-$season-$ep")
            }
        }.distinct()

        var emitted = false
        candidateUrls.forEach route@{ url ->
            val response = runCatching { app.get(url, referer = referer) }.getOrNull() ?: return@route
            val html = response.text
            val serverUrls = (
                response.document.select(".servers [data-url], button.server-btn[data-url], [data-url]")
                    .mapNotNull { it.attr("data-url").takeIf { value -> value.isNotBlank() } } +
                Regex("""data-url=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .findAll(html)
                    .map { it.groupValues[1] }
                    .toList()
            ).map { fixUrl(it, autoEmbedAPI) }.distinct()

            serverUrls.filterNot { it.contains("vidsrc", true) }.forEach { serverUrl ->
                when {
                    loadVidsrcXpass(serverUrl, isTv, referer, callback) -> {
                        emitted = true
                    }
                    else -> {
                        loadExtractor(serverUrl, referer, subtitleCallback) { link ->
                            emitted = true
                            callback.invoke(link)
                        }
                    }
                }
            }

            if (emitted) return@route

            if (invokeWebviewEmbedSource(
                    "AutoEmbed",
                    url,
                    referer,
                    autoEmbedAPI,
                    callback,
                    useOkhttp = false
                )
            ) {
                emitted = true
            }
        }

        if (emitted) return
    }

    suspend fun invoke2Embed(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = if (season == null) {
            "$twoEmbedAPI/embed/movie/$tmdbId"
        } else {
            "$twoEmbedAPI/embed/tv/$tmdbId/$season/$episode"
        }
        val fallbackCcUrl = if (season == null) {
            "https://www.2embed.cc/embed/movie/$tmdbId"
        } else {
            "https://www.2embed.cc/embed/tv/$tmdbId/$season/$episode"
        }

        if (invokeWebviewEmbedSource(
            "2Embed",
            url,
            "$twoEmbedAPI/",
            twoEmbedAPI,
            callback
        )) {
            return
        }

        val fallbackHtml = app.get(
            fallbackCcUrl,
            headers = mapOf("User-Agent" to USER_AGENT)
        ).text

        val xpsUrl = Regex("""https://streamsrcs\.2embed\.cc/xps(?:-tv)?\?[^'"\s<]+""", RegexOption.IGNORE_CASE)
            .find(fallbackHtml)
            ?.value

        if (!xpsUrl.isNullOrBlank()) {
            if (loadVidsrcXpass(xpsUrl, season != null, fallbackCcUrl, callback)) return

            val xpsResponse = app.get(
                xpsUrl,
                referer = fallbackCcUrl,
                headers = mapOf("User-Agent" to USER_AGENT)
            )
            extractPlayableUrlFromHtml(xpsResponse.text, getBaseUrl(xpsResponse.url))?.let { resolved ->
                if (loadVidsrcXpass(resolved, season != null, xpsUrl, callback)) return
            }
        }

        val nestedUrl = Regex("""<iframe[^>]+id=["']iframesrc["'][^>]+data-src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(fallbackHtml)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { fixUrl(it, "https://www.2embed.cc") }

        if (!nestedUrl.isNullOrBlank()) {
            if (loadVidsrcXpass(nestedUrl, season != null, fallbackCcUrl, callback)) return
            invokeWebviewEmbedSource(
                "2Embed",
                nestedUrl,
                fallbackCcUrl,
                getBaseUrl(nestedUrl),
                callback,
                useOkhttp = false
            )
        }
    }

    suspend fun invokeMultiEmbed(
        tmdbId: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val videoIds = listOfNotNull(tmdbId?.toString(), imdbId).distinct()
        if (videoIds.isEmpty()) return

        val userAgent =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to multiEmbedAPI,
            "X-Requested-With" to "XMLHttpRequest",
        )
        videoIds.forEach { videoId ->
            val baseUrl = buildString {
                append("$multiEmbedAPI/?video_id=")
                append(videoId)
                if (season != null && episode != null) append("&s=$season&e=$episode")
            }
            val resolvedUrl = app.get(baseUrl, headers = headers).url
            if (
                invokeWebviewEmbedSource(
                    "MultiEmbed",
                    resolvedUrl,
                    "$multiEmbedAPI/",
                    getBaseUrl(resolvedUrl),
                    callback,
                    useOkhttp = false
                )
            ) {
                return
            }

            val pageHtml = app.post(
                resolvedUrl,
                data = mapOf(
                    "button-click" to "ZEhKMVpTLVF0LVBTLVF0LVAtMGs1TFMtUXpPREF0TC0wLVYzTi0wVS1RTi0wQTFORGN6TmprLTU=",
                    "button-referer" to ""
                ),
                headers = headers
            ).text
            val token = Regex("""load_sources\("([^"]+)"\)""").find(pageHtml)?.groupValues?.get(1) ?: return@forEach
            val sourcesHtml = app.post(
                "https://streamingnow.mov/response.php",
                data = mapOf("token" to token),
                headers = headers
            ).text

            Jsoup.parse(sourcesHtml).select("li").amap { server ->
                val serverId = server.attr("data-server")
                val sourceVideoId = server.attr("data-id")
                if (serverId.isBlank() || sourceVideoId.isBlank()) return@amap

                runCatching {
                    val playUrl = "https://streamingnow.mov/playvideo.php" +
                        "?video_id=${sourceVideoId.substringBefore("=")}&server_id=$serverId&token=$token&init=1"
                    val playHtml = app.get(playUrl, headers = headers).text
                    val iframeUrl = Jsoup.parse(playHtml).selectFirst("iframe.source-frame.show")?.attr("src")
                        ?: return@runCatching
                    val iframeHtml = app.get(iframeUrl, headers = headers).text
                    val fileUrl = Jsoup.parse(iframeHtml).selectFirst("iframe.source-frame.show")?.attr("src")
                        ?: Regex("""file:"(https?://[^"]+)"""").find(iframeHtml)?.groupValues?.get(1)
                        ?: extractPlayableUrlFromHtml(iframeHtml, getBaseUrl(iframeUrl))
                        ?: return@runCatching

                    when {
                        fileUrl.contains(".m3u8", true) || fileUrl.contains(".json", true) ->
                            M3u8Helper.generateM3u8("MultiEmbed", fileUrl, multiEmbedAPI).forEach(callback)

                        loadVidsrcXpass(fileUrl, season != null, multiEmbedAPI, callback) -> Unit

                        !fileUrl.contains("vidsrc", true) -> loadExtractor(fileUrl, multiEmbedAPI, subtitleCallback, callback)
                    }
                }
            }
        }
    }

    suspend fun invokeNinetv(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = if (season == null) {
            "$nineTvAPI/movie/$tmdbId"
        } else {
            "$nineTvAPI/tv/$tmdbId-$season-$episode"
        }
        val iframe = app.get(url, referer = "https://pressplay.top/").document.selectFirst("iframe")?.attr("src") ?: return
        loadExtractor(iframe, "$nineTvAPI/", subtitleCallback, callback)
    }

    suspend fun invokeRidomovies(
        tmdbId: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val searchResponse = app.get("$ridomoviesAPI/core/api/search?q=$imdbId", interceptor = wpRedisInterceptor)
        if (searchResponse.code != 200) return

        val mediaSlug = searchResponse.parsedSafe<RidoSearch>()
            ?.data?.items?.find { it.contentable?.tmdbId == tmdbId || it.contentable?.imdbId == imdbId }
            ?.slug ?: return

        val id = season?.let {
            val episodeUrl = "$ridomoviesAPI/tv/$mediaSlug/season-$it/episode-$episode"
            val episodeResponse = app.get(episodeUrl, interceptor = wpRedisInterceptor)
            if (episodeResponse.code != 200) return@let null
            episodeResponse.text.substringAfterLast("""postid\":\"""").substringBefore("\"")
        } ?: mediaSlug

        val url = "$ridomoviesAPI/core/api/${if (season == null) "movies" else "episodes"}/$id/videos"
        val videoResponse = app.get(url, interceptor = wpRedisInterceptor)
        if (videoResponse.code != 200) return

        videoResponse.parsedSafe<RidoResponses>()?.data?.amap { link ->
            val iframe = Jsoup.parse(link.url ?: return@amap).select("iframe").attr("data-src")
            if (iframe.startsWith("https://closeload.top")) {
                val unpacked = getAndUnpack(
                    app.get(iframe, referer = "$ridomoviesAPI/", interceptor = wpRedisInterceptor).text
                )
                val encodeHash = Regex("\\(\"([^\"]+)\"\\);").find(unpacked)?.groupValues?.get(1) ?: return@amap
                val video = base64Decode(base64Decode(encodeHash).reversed()).split("|").getOrNull(1) ?: return@amap
                callback.invoke(
                    newExtractorLink("Ridomovies", "Ridomovies", video, ExtractorLinkType.M3U8) {
                        this.referer = "${getBaseUrl(iframe)}/"
                        this.quality = Qualities.P1080.value
                    }
                )
            } else {
                loadExtractor(iframe, "$ridomoviesAPI/", subtitleCallback, callback)
            }
        }
    }

    suspend fun invokeSoapy(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        listOf("juliet", "romio").amap { player ->
            val url = if (season == null) {
                "$soapyAPI/embed/movies.php?tmdbid=$tmdbId&player=$player"
            } else {
                "$soapyAPI/embed/series.php?tmdbid=$tmdbId&season=$season&episode=$episode&player=$player"
            }
            val iframe = app.get(url).document.select("iframe").attr("src")
            if (iframe.isNotBlank()) loadExtractor(iframe, soapyAPI, subtitleCallback, callback)
        }
    }

    suspend fun invokeVidsrcMov(
        tmdbId: Int?,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = imdbId ?: tmdbId?.toString() ?: return
        val url = if (season == null) {
            "$vidsrcMovAPI/embed/movie/$id"
        } else {
            "$vidsrcMovAPI/embed/tv/$id/$season/$episode"
        }

        invokeWebviewEmbedSource(
            "VidSrcMov",
            url,
            "$vidsrcMovAPI/",
            vidsrcMovAPI,
            callback
        )
    }

    suspend fun invokeRiveStream(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = tmdbId ?: return
        val type = if (season == null) "movie" else "tv"
        val headers = mapOf("User-Agent" to USER_AGENT)

        suspend fun <T> riveRequest(block: suspend () -> T): T? {
            repeat(2) {
                runCatching { block() }.getOrNull()?.let { return it }
            }
            return runCatching { block() }.getOrNull()
        }

        val sourceList = riveRequest {
            app.get(
                "$riveStreamAPI/api/backendfetch?requestID=VideoProviderServices&secretKey=rive",
                headers = headers
            ).parsedSafe<RiveStreamSource>()
        }

        val secretKey = riveStreamSecretKey(id.toString())

        var emitted = false
        if (!secretKey.isNullOrBlank()) {
            sourceList?.data?.distinct()?.amap { service ->
                val streamUrl = if (season == null) {
                    "$riveStreamAPI/api/backendfetch?requestID=movieVideoProvider&id=$id&service=$service&secretKey=$secretKey"
                } else {
                    "$riveStreamAPI/api/backendfetch?requestID=tvVideoProvider&id=$id&season=$season&episode=$episode&service=$service&secretKey=$secretKey"
                }

                val response = riveRequest {
                    app.get(streamUrl, headers = headers, timeout = 10).parsedSafe<RiveStreamResponse>()
                } ?: return@amap

                response.data?.sources?.forEach { source ->
                    parseRiveStreamSource(source)?.let { link ->
                        emitted = true
                        callback(link)
                    }
                }
            }
        }

        if (emitted) return

        val watchUrl = if (season == null) {
            "$riveStreamAPI/watch?type=$type&id=$id"
        } else {
            "$riveStreamAPI/watch?type=$type&id=$id&season=$season&episode=$episode"
        }

        val externalRes = app.get(
            watchUrl,
            interceptor = WebViewResolver(
                Regex("""https?://(?!([^/]+\.)?rivestream\.(?:org|app)(?:/|$))[^"'\\s]+"""),
                timeout = 30_000L
            )
        )

        val externalUrl = externalRes.url
        if (!isRiveStreamUrl(externalUrl)) {
            if (loadVidsrcXpass(externalUrl, season != null, "$riveStreamAPI/", callback)) return

            var emitted = false
            loadExtractor(externalUrl, "$riveStreamAPI/", { _: SubtitleFile -> }) { link ->
                emitted = true
                callback.invoke(link)
            }
            if (emitted) return

            if (externalUrl.contains(".m3u8", true) || externalUrl.contains(".mp4", true)) {
                callback.invoke(
                    newExtractorLink(
                        "RiveStream",
                        "RiveStream",
                        externalUrl,
                        if (externalUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        this.referer = "$riveStreamAPI/"
                        this.headers = mapOf(
                            "Accept" to "*/*",
                            "Referer" to "$riveStreamAPI/",
                            "Origin" to riveStreamAPI,
                            "User-Agent" to USER_AGENT,
                        )
                    }
                )
                return
            }

            invokeWebviewEmbedSource(
                "RiveStream",
                externalUrl,
                "$riveStreamAPI/",
                getBaseUrl(externalUrl),
                callback
            )
            return
        }

        val aggUrl = if (season == null) {
            "$riveStreamAPI/embed/agg?type=$type&id=$id"
        } else {
            "$riveStreamAPI/embed/agg?type=$type&id=$id&season=$season&episode=$episode"
        }

        if (invokeWebviewEmbedSource(
            "RiveStream",
            aggUrl,
            "$riveStreamAPI/",
            riveStreamAPI,
            callback,
            useOkhttp = false
        )) {
            return
        }

        invokeWebviewEmbedSource(
            "RiveStream",
            watchUrl,
            "$riveStreamAPI/",
            riveStreamAPI,
            callback,
            useOkhttp = false
        )
    }

    private fun isRiveStreamUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "rivestream.org" || host.endsWith(".rivestream.org") ||
            host == "rivestream.app" || host.endsWith(".rivestream.app")
    }

    suspend fun invokeSmashyStream(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val cleanImdbId = imdbId?.takeIf { it.isNotBlank() } ?: return
        val url = if (season == null) {
            "$smashyStreamAPI/playere.php?imdb=$cleanImdbId"
        } else {
            "$smashyStreamAPI/playere.php?imdb=$cleanImdbId&season=$season&episode=$episode"
        }

        invokeWebviewEmbedSource(
            "SmashyStream",
            url,
            "https://smashystream.com/",
            "https://smashystream.com",
            callback
        )
    }

    suspend fun invokeVembed(
        tmdbId: Int?,
        imdbId: String?,
        season: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val id = imdbId ?: tmdbId?.toString() ?: return
        val vembedId = if (season == null) id else "${id}_s$season"

        invokeWebviewEmbedSource(
            "Vembed",
            "$vembedAPI/play/$vembedId",
            "$vembedAPI/",
            vembedAPI,
            callback
        )
    }

    suspend fun invokeVixsrc(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = if (season == null) {
            "$vixsrcAPI/movie/$tmdbId"
        } else {
            "$vixsrcAPI/tv/$tmdbId/$season/$episode"
        }
        invokeWebviewEmbedSource(
            "Vixsrc",
            url,
            "$vixsrcAPI/",
            vixsrcAPI,
            callback
        )

    }

    suspend fun invokeKisskh(
        titleCandidates: List<String>,
        year: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val mainUrl = "https://kisskh.ovh"
        val KISSKH_API = "https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id="
        val KISSKH_SUB_API = "https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id="

        try {
            val queries = titleCandidates.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (queries.isEmpty()) return
            val matched = queries.firstNotNullOfOrNull { query ->
                val searchRes = app.get("$mainUrl/api/DramaList/Search?q=${query.urlEncodeCompat()}&type=0").text
                val searchList = tryParseJson<ArrayList<KisskhMedia>>(searchRes) ?: return@firstNotNullOfOrNull null
                searchList
                    .map { media ->
                        SearchMatchCandidate(
                            url = media.id?.toString().orEmpty(),
                            title = media.title,
                            year = media.releaseDate?.substringBefore("-")?.toIntOrNull(),
                            sourceQuery = query,
                            payload = media,
                        )
                    }
                    .bestSearchMatch(queries, year)
                    ?.payload as? KisskhMedia
            } ?: return
            val dramaId = matched.id ?: return
            val detailRes = app.get("$mainUrl/api/DramaList/Drama/$dramaId?isq=false").parsedSafe<KisskhDetail>() ?: return
            val episodes = detailRes.episodes ?: return
            val targetEp = if (season == null) {
                episodes.lastOrNull()
            } else {
                episodes.find { it.number?.toInt() == episode }
            } ?: return
            val epsId = targetEp.id ?: return
            val kkeyVideo = app.get("$KISSKH_API$epsId&version=2.8.10").parsedSafe<KisskhKey>()?.key ?: ""
            val videoUrl = "$mainUrl/api/DramaList/Episode/$epsId.png?err=false&ts=&time=&kkey=$kkeyVideo"
            val sources = app.get(videoUrl).parsedSafe<KisskhSources>()

            val videoLink = sources?.video
            val videoTmp = sources?.videoTmp
            val thirdParty = sources?.thirdParty

            val episodeReferer =
                "$mainUrl/Drama/${matched.title?.let { getKisskhTitle(it) }.orEmpty()}/Episode-${targetEp.number?.toInt() ?: episode}?id=$dramaId&ep=$epsId&page=0&pageSize=100"

            listOfNotNull(videoLink, videoTmp, thirdParty)
                .mapNotNull { it.trim().takeIf { s -> s.isNotBlank() } }
                .forEach { link ->
                when {
                    link.contains(".m3u8", true) -> {
                        M3u8Helper.generateM3u8(
                            "Kisskh",
                            fixUrl(link),
                            referer = "$mainUrl/",
                            headers = mapOf("Origin" to mainUrl)
                        ).forEach(callback)
                    }
                    link.contains(".mp4", true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "Kisskh",
                                "Kisskh",
                                fixUrl(link),
                                ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                            }
                        )
                    }
                    link.contains(".txt", true) -> {
                        val txtContent = runCatching {
                            app.get(fixUrl(link), referer = episodeReferer).text
                        }.getOrNull() ?: return@forEach
                        val decrypted = kisskhDecryptTxt(txtContent)
                        val streamUrl = Regex("""https?://[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*""", RegexOption.IGNORE_CASE)
                            .find(decrypted)?.value
                        if (!streamUrl.isNullOrBlank()) {
                            if (streamUrl.contains(".m3u8", true)) {
                                M3u8Helper.generateM3u8("Kisskh", streamUrl, "$mainUrl/", headers = mapOf("Origin" to mainUrl)).forEach(callback)
                            } else {
                                callback.invoke(
                                    newExtractorLink("Kisskh", "Kisskh", streamUrl, ExtractorLinkType.VIDEO) {
                                        this.referer = mainUrl
                                    }
                                )
                            }
                        }
                    }
                    else -> {
                        val normalized = fixUrl(link)
                        val candidates = buildList {
                            add(normalized)
                            if (normalized.contains("=http", true)) add(normalized.substringBefore("=http"))
                        }.distinct()

                        candidates.forEach { candidate ->
                            safeApiCall {
                                loadExtractor(
                                    candidate,
                                    episodeReferer,
                                    subtitleCallback,
                                    callback
                                )
                            }
                        }
                    }
                }
            }

            val kkeySub = app.get("$KISSKH_SUB_API$epsId&version=2.8.10").parsedSafe<KisskhKey>()?.key ?: ""
            val subJson = app.get("$mainUrl/api/Sub/$epsId?kkey=$kkeySub").text
            tryParseJson<List<KisskhSubtitle>>(subJson)?.forEach { sub ->
                subtitleCallback.invoke(
                    newSubtitleFile(
                        sub.label ?: "Unknown",
                        sub.src ?: return@forEach
                    )
                )
            }

        } catch (e: Exception) {
            logError(e)
        }
    }

    suspend fun invokeWatch32(
        titleCandidates: List<String>,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val queries = titleCandidates.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (queries.isEmpty()) return

        val typeLabel = if (season == null) "Movie" else "TV"
        val matchedAnchor = queries.firstNotNullOfOrNull { query ->
            val doc = app.get("${watch32API}/search/${query.replace(" ", "-")}", timeout = 120L).document
            doc.select("div.flw-item").mapNotNull { item ->
                val anchor = item.selectFirst("h2.film-name a") ?: return@mapNotNull null
                val mediaType = item.selectFirst("span.fdi-type")?.text()?.trim().orEmpty()
                if (!mediaType.equals(typeLabel, true)) return@mapNotNull null
                SearchMatchCandidate(
                    url = fixUrl(anchor.attr("href"), watch32API),
                    title = anchor.text(),
                    sourceQuery = query,
                    payload = anchor.attr("href"),
                )
            }.bestSearchMatch(queries)?.payload as? String
        } ?: return

        val detailUrl = fixUrl(matchedAnchor, watch32API)
        val infoId = detailUrl.substringAfterLast("-")

        if (season != null) {
            val seasonLinks = app.get("${watch32API}/ajax/season/list/$infoId").document.select("div.dropdown-menu a")
            val matchedSeason = seasonLinks.firstOrNull { it.text().contains("Season $season", true) } ?: return
            val seasonId = matchedSeason.attr("data-id")
            val episodeLinks = app.get("${watch32API}/ajax/season/episodes/$seasonId").document.select("li.nav-item a")
            val matchedEpisode = episodeLinks.firstOrNull { it.text().contains("Eps $episode:", true) } ?: return
            val dataId = matchedEpisode.attr("data-id")
            val serverDoc = app.get("${watch32API}/ajax/episode/servers/$dataId").document
            serverDoc.select("li.nav-item a").amap { source ->
                val sourceId = source.attr("data-id")
                val iframeUrl = app.get("${watch32API}/ajax/episode/sources/$sourceId").parsedSafe<Watch32Source>()?.link ?: return@amap
                loadExtractor(iframeUrl, "", subtitleCallback, callback)
            }
        } else {
            val episodeLinks = app.get("${watch32API}/ajax/episode/list/$infoId").document.select("li.nav-item a")
            episodeLinks.amap { ep ->
                val dataId = ep.attr("data-id")
                if (dataId.isBlank()) return@amap
                val iframeUrl = app.get("${watch32API}/ajax/episode/sources/$dataId").parsedSafe<Watch32Source>()?.link ?: return@amap
                loadExtractor(iframeUrl, "", subtitleCallback, callback)
            }
        }
    }

    // Idlix helpers
    private fun solvePow(challenge: String, difficulty: Int): Int {
        val target = "0".repeat(difficulty)
        var nonce = 0
        while (true) {
            val hash = sha256(challenge + nonce)
            if (hash.startsWith(target)) return nonce
            nonce++
        }
    }

    private fun sha256(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun extractUrlFromSolveResponse(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("http", true)) return trimmed
        Regex("""""'(https?://[^""']+)""'""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""""'(/(?:embed|player|video|play|e|v|watch)[^""']*)""'""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        val normalized = trimmed.replace("\\/", "/")
        Regex("""https?://[^""'\s]+""", RegexOption.IGNORE_CASE).find(normalized)?.value?.let { return it }
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        fun findUrl(obj: Any?): String? {
            when (obj) {
                is String -> if (obj.startsWith("http", true) || obj.startsWith("/")) return obj
                is JSONObject -> {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key.equals("embedUrl", true) || key.equals("url", true) ||
                            key.equals("streamUrl", true) || key.equals("playbackUrl", true) ||
                            key.equals("src", true) || key.equals("file", true) || key.equals("link", true)) {
                            findUrl(obj.get(key))?.let { return it }
                        }
                    }
                    val keys2 = obj.keys()
                    while (keys2.hasNext()) { findUrl(obj.get(keys2.next()))?.let { return it } }
                }
            }
            return null
        }
        return findUrl(json)
    }

    // Kisskh .txt decryption helper
    private fun kisskhDecryptTxt(content: String): String {
        val chunkRegex = Regex("^\\d+$", RegexOption.MULTILINE)
        val chunks = content.split(chunkRegex).filter(String::isNotBlank).map(String::trim)
        return chunks.mapIndexed { index, chunk ->
            if (chunk.isBlank()) return@mapIndexed ""
            val parts = chunk.split("\n")
            if (parts.isEmpty()) return@mapIndexed ""
            val header = parts.first()
            val text = parts.drop(1)
            val d = text.joinToString("\n") { line ->
                runCatching { kisskhDecryptLine(line) }.getOrDefault(line)
            }
            listOf(index + 1, header, d).joinToString("\n")
        }.filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    private fun kisskhDecryptLine(encryptedB64: String): String {
        val keyIvPairs = listOf(
            Pair("AmSmZVcH93UQUezi".toByteArray(Charsets.UTF_8), intArrayOf(1382367819, 1465333859, 1902406224, 1164854838).toKisskhByteArray()),
            Pair("8056483646328763".toByteArray(Charsets.UTF_8), intArrayOf(909653298, 909193779, 925905208, 892483379).toKisskhByteArray()),
        )
        val encryptedBytes = try {
            android.util.Base64.decode(encryptedB64, android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            return encryptedB64
        }
        for ((keyBytes, ivBytes) in keyIvPairs) {
            try {
                val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(keyBytes, "AES"), javax.crypto.spec.IvParameterSpec(ivBytes))
                return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
            } catch (_: Exception) { }
        }
        return encryptedB64
    }

    private fun IntArray.toKisskhByteArray(): ByteArray {
        return ByteArray(size * 4).also { bytes ->
            forEachIndexed { index, value ->
                bytes[index * 4] = (value shr 24).toByte()
                bytes[index * 4 + 1] = (value shr 16).toByte()
                bytes[index * 4 + 2] = (value shr 8).toByte()
                bytes[index * 4 + 3] = value.toByte()
            }
        }
    }

    // Idlix data classes
    private data class IdlixSearchResponse(
        @param:JsonProperty("results") val results: ArrayList<IdlixSearchResult>? = arrayListOf(),
    )

    private data class IdlixSearchResult(
        @param:JsonProperty("id") val id: String? = null,
        @param:JsonProperty("contentType") val contentType: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("originalTitle") val originalTitle: String? = null,
        @param:JsonProperty("slug") val slug: String? = null,
        @param:JsonProperty("releaseDate") val releaseDate: String? = null,
        @param:JsonProperty("firstAirDate") val firstAirDate: String? = null,
    )

    private data class IdlixDetailResponse(
        @param:JsonProperty("id") val id: String? = null,
        @param:JsonProperty("slug") val slug: String? = null,
        @param:JsonProperty("contentType") val contentType: String? = null,
        @param:JsonProperty("seasons") val seasons: ArrayList<IdlixSeason>? = arrayListOf(),
        @param:JsonProperty("firstSeason") val firstSeason: IdlixSeason? = null,
    )

    private data class IdlixSeason(
        @param:JsonProperty("seasonNumber") val seasonNumber: Int? = null,
        @param:JsonProperty("episodes") val episodes: ArrayList<IdlixEpisode>? = arrayListOf(),
    )

    private data class IdlixEpisode(
        @param:JsonProperty("id") val id: String? = null,
        @param:JsonProperty("episodeNumber") val episodeNumber: Int? = null,
    )

    private data class IdlixChallengeResponse(
        @param:JsonProperty("challenge") val challenge: String,
        @param:JsonProperty("signature") val signature: String,
        @param:JsonProperty("difficulty") val difficulty: Int,
    )

    private data class IdlixPlayInfoResponse(
        @param:JsonProperty("claim") val claim: String? = null,
        @param:JsonProperty("redeemUrl") val redeemUrl: String? = null,
    )

    private data class IdlixIframeResponse(
        @param:JsonProperty("url") val url: String? = null,
        @param:JsonProperty("subtitles") val subtitles: List<IdlixSubtitle>? = emptyList(),
    )

    private data class IdlixSubtitle(
        @param:JsonProperty("lang") val lang: String? = null,
        @param:JsonProperty("label") val label: String? = null,
        @param:JsonProperty("path") val path: String? = null,
    )

    private data class KisskhMedia(
        @param:JsonProperty("id") val id: Int?,
        @param:JsonProperty("title") val title: String?,
        @param:JsonProperty("releaseDate") val releaseDate: String? = null,
    )
    private data class KisskhDetail(@param:JsonProperty("episodes") val episodes: ArrayList<KisskhEpisode>?)
    private data class KisskhEpisode(
        @param:JsonProperty("id") val id: Int?,
        @param:JsonProperty("number") val number: Double?
    )
    private data class KisskhKey(@param:JsonProperty("key") val key: String?)
    private data class KisskhSources(
        @param:JsonProperty("Video") val video: String?,
        @param:JsonProperty("Video_tmp") val videoTmp: String? = null,
        @param:JsonProperty("ThirdParty") val thirdParty: String?
    )
    private data class KisskhSubtitle(
        @param:JsonProperty("src") val src: String?,
        @param:JsonProperty("label") val label: String?
    )

    private data class Watch32Source(
        @param:JsonProperty("link") val link: String? = null,
    )

    private suspend fun loadVidsrcXpass(
        url: String,
        isTv: Boolean,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (isVidsrcDisabled()) return false
        if (!url.contains("vidsrc", true) && !url.contains("vsembed", true)) return false

        val embedUrl = if (url.contains("autoplay=", true)) url else {
            val joiner = if (url.contains("?")) "&" else "?"
            "$url${joiner}autoplay=1"
        }
        val browserHeaders = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "User-Agent" to USER_AGENT,
        )
        val embedResponse = app.get(embedUrl, referer = referer, headers = browserHeaders)
        val twoEmbedHash = Regex(
            """<div\s+class=["']server["'][^>]*data-hash=["']([^"']+)["'][^>]*>\s*2Embed\s*</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(embedResponse.text)?.groupValues?.getOrNull(1) ?: return false
        val rcpUrl = fixUrl("//cloudnestra.com/rcp/$twoEmbedHash", getBaseUrl(embedResponse.url))
        val rcpResponse = app.get(rcpUrl, referer = "${getBaseUrl(embedResponse.url)}/", headers = browserHeaders)
        val srcrcpUrl = Regex("""/srcrcp/[^'"\s<]+""")
            .find(rcpResponse.text)
            ?.value
            ?.let { fixUrl(it, getBaseUrl(rcpResponse.url)) }
            ?: return false
        val twoEmbedResponse = app.get(srcrcpUrl, referer = "${getBaseUrl(rcpResponse.url)}/", headers = browserHeaders)
        val xpsUrl = Regex("""https://streamsrcs\.2embed\.cc/xps(?:-tv)?\?[^'"\s<]+""", RegexOption.IGNORE_CASE)
            .find(twoEmbedResponse.text)
            ?.value
            ?: return loadCurrent2EmbedVidsrc(twoEmbedResponse, isTv, browserHeaders, callback)
        val xpsResponse = app.get(xpsUrl, referer = "${getBaseUrl(twoEmbedResponse.url)}/", headers = browserHeaders)
        val xpassSlug = xpsResponse.document.selectFirst("iframe#framesrc")?.attr("src")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return false
        val xpassBase = if (isTv || xpsUrl.contains("xps-tv", true)) {
            "https://play.xpass.top/e/tv/"
        } else {
            "https://play.xpass.top/e/movie/"
        }
        val xpassUrl = "$xpassBase${xpassSlug.removePrefix("/")}"
        val xpassResponse = app.get(xpassUrl, referer = "${getBaseUrl(xpsResponse.url)}/", headers = browserHeaders)
        val playlistUrl = Regex(""""playlist":"([^"]+/playlist\.json)"""")
            .find(xpassResponse.text)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.let { fixUrl(it, getBaseUrl(xpassResponse.url)) }
            ?: return false
        val playlistResponse = app.get(
            playlistUrl,
            referer = xpassUrl,
            headers = mapOf("Accept" to "application/json,text/plain,*/*", "User-Agent" to USER_AGENT),
        )
        val streamUrl = Regex(""""file"\s*:\s*"([^"]+)"""")
            .findAll(playlistResponse.text)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.replace("\\u0026", "&").replace("\\/", "/") }
            .firstOrNull { it.contains(".m3u8", true) }
            ?: return false
        val generatedLinks = M3u8Helper.generateM3u8(
            "VidSrc",
            streamUrl,
            "https://play.xpass.top/",
            headers = mapOf(
                "Accept" to "*/*",
                "Referer" to "https://play.xpass.top/",
                "Origin" to "https://play.xpass.top",
                "User-Agent" to USER_AGENT,
            ),
        )
        if (generatedLinks.isEmpty()) return false
        generatedLinks.forEach(callback)
        return true
    }

    private suspend fun loadCurrent2EmbedVidsrc(
        twoEmbedResponse: NiceResponse,
        isTv: Boolean,
        browserHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val streamsrcUrl = twoEmbedResponse.document.selectFirst("iframe#iframesrc")?.let { iframe ->
            iframe.attr("data-src").ifBlank { iframe.attr("src") }
        }?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("about:blank", ignoreCase = true) }
            ?.let { fixUrl(it, getBaseUrl(twoEmbedResponse.url)) }
            ?: Regex(
                """https://streamsrcs\.2embed\.cc/vsrcc(?:-tv)?\?[^'"\s<]+""",
                RegexOption.IGNORE_CASE,
            ).find(twoEmbedResponse.text)?.value
            ?: return false

        val streamsrcResponse = app.get(
            streamsrcUrl,
            referer = "${getBaseUrl(twoEmbedResponse.url)}/",
            headers = browserHeaders,
        )
        val frameSrc = streamsrcResponse.document.selectFirst("iframe#framesrc")?.attr("src")?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("about:blank", ignoreCase = true) }
            ?: return false
        val vidsrcCcUrl = when {
            frameSrc.startsWith("http", ignoreCase = true) -> frameSrc
            else -> "$vidsrcccAPI/v2/embed/${if (isTv) "tv" else "movie"}/${frameSrc.removePrefix("/")}"
        }

        return invokeWebviewEmbedSource(
            "VidSrc",
            vidsrcCcUrl,
            "$vidsrcccAPI/",
            vidsrcccAPI,
            callback,
            useOkhttp = false,
        )
    }

    private fun isVidsrcDisabled(): Boolean = true

    private fun extractPlayableUrlFromHtml(
        html: String,
        baseUrl: String,
    ): String? {
        val document = Jsoup.parse(html, baseUrl)
        return listOfNotNull(
            document.selectFirst("iframe.source-frame.show")?.attr("abs:src"),
            document.selectFirst("iframe[data-src]")?.attr("abs:data-src"),
            document.selectFirst("iframe[src]")?.attr("abs:src"),
            document.selectFirst("source[src]")?.attr("abs:src"),
            Regex("""file\s*:\s*"(https?://[^"]+)"""").find(html)?.groupValues?.getOrNull(1),
            Regex("""["'](https?://[^"'\\s]+?\.(?:m3u8|mp4|json)(?:\?[^"'\\s]*)?)["']""")
                .find(html)?.groupValues?.getOrNull(1),
        ).firstOrNull { !it.isNullOrBlank() }
    }

    private suspend fun requestAzMoviesPage(url: String): NiceResponse {
        val headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "User-Agent" to USER_AGENT,
        )
        val response = app.get(url, headers = headers, timeout = 30L)
        if (!response.text.contains("Verifying your browser", true) || !response.text.contains("var verifyToken")) {
            return response
        }
        val token = response.text.substringAfter("var verifyToken = \"", "").substringBefore("\"")
        if (token.isBlank()) return response
        val cookies = response.cookies
        app.post(
            "$azmoviesAPI/verified",
            headers = headers + mapOf(
                "Content-Type" to "application/json",
                "Origin" to azmoviesAPI,
                "Referer" to url,
                "X-Requested-With" to "XMLHttpRequest",
            ),
            cookies = cookies,
            requestBody = """{"token":"$token"}""".toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull()),
        )
        return app.get(url, headers = headers, cookies = cookies, timeout = 30L)
    }

    private suspend fun requestNoxxPage(url: String): NiceResponse {
        val headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "User-Agent" to USER_AGENT,
        )
        val response = app.get(url, headers = headers, timeout = 30L)
        if (!response.text.contains("Verifying your browser", true) || !response.text.contains("var token =")) {
            return response
        }
        val token = Regex("""var token = "([^"]+)"""").find(response.text)?.groupValues?.getOrNull(1)
            ?: return response
        val cookies = response.cookies
        val verifiedResponse = app.post(
            "$noxxAPI/verified",
            data = mapOf("token" to token),
            cookies = cookies,
            headers = mapOf(
                "Origin" to noxxAPI,
                "Referer" to url,
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json,text/plain,*/*",
                "X-Requested-With" to "XMLHttpRequest",
            ),
        )
        val verifiedCookies = if (verifiedResponse.cookies.isNotEmpty()) verifiedResponse.cookies else cookies
        return app.get(url, headers = headers, cookies = verifiedCookies, timeout = 30L)
    }

    private fun extractAzMoviesServerButtons(html: String, document: Document): List<SiteSourceButton> {
        val regexButtons = Regex(
            """<button[^>]*class=["'][^"']*server-btn[^"']*["'][^>]*data-url=["']([^"']+)["'][^>]*data-server=["']([^"']*)["'][^>]*data-quality=["']([^"']*)["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(html).map {
            SiteSourceButton(
                url = it.groupValues[1],
                server = it.groupValues[2],
                quality = it.groupValues[3],
            )
        }.toList()
        val domButtons = document.select("button.server-btn[data-url]").map {
            SiteSourceButton(
                url = it.attr("data-url"),
                server = it.attr("data-server"),
                quality = it.attr("data-quality"),
            )
        }
        return (regexButtons + domButtons).distinctBy { "${it.server}|${it.url}" }
    }

    private suspend fun extractInlineSubtitle(url: String): SubtitleFile? {
        val subtitleUrl = url.substringAfter("c1_file=", "").substringBefore("&").let {
            URLDecoder.decode(it, "UTF-8")
        }
        if (subtitleUrl.isBlank() || !subtitleUrl.contains(".vtt", true)) return null
        val label = URLDecoder.decode(url.substringAfter("c1_label=", "English").substringBefore("&"), "UTF-8")
        return newSubtitleFile(label.ifBlank { "English" }, subtitleUrl)
    }

    private fun titleMatches(candidate: String?, target: String): Boolean {
        return titleMatchScore(candidate, target) >= 85
    }

    private fun String?.normalizeSearchTitle(): String {
        return this.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    private fun titleMatchScore(candidate: String?, target: String): Int {
        val normalizedCandidate = candidate.normalizeSearchTitle()
        val normalizedTarget = target.normalizeSearchTitle()
        if (normalizedCandidate.isBlank() || normalizedTarget.isBlank()) return 0
        if (normalizedCandidate == normalizedTarget) return 100

        val candidateTokens = normalizedCandidate.split(" ").filter { it.isNotBlank() }
        val targetTokens = normalizedTarget.split(" ").filter { it.isNotBlank() }
        if (candidateTokens.isEmpty() || targetTokens.isEmpty()) return 0

        val commonTokens = candidateTokens.intersect(targetTokens.toSet())
        val targetCoverage = commonTokens.size.toDouble() / targetTokens.size
        val candidateCoverage = commonTokens.size.toDouble() / candidateTokens.size

        if (targetTokens.size >= 2 && targetCoverage == 1.0 && candidateTokens.size <= targetTokens.size + 1) {
            return 95
        }

        if (candidateTokens.size >= 2 && candidateCoverage == 1.0 && targetTokens.size <= candidateTokens.size + 1) {
            return 92
        }

        if (commonTokens.size >= 2 && targetCoverage >= 0.8 && candidateCoverage >= 0.6) {
            return 88
        }

        if (normalizedCandidate.contains(normalizedTarget) || normalizedTarget.contains(normalizedCandidate)) {
            return if (minOf(normalizedCandidate.length, normalizedTarget.length) >= 10) 80 else 0
        }

        return 0
    }

    private fun List<SearchMatchCandidate>.bestSearchMatch(
        targetTitles: List<String>,
        expectedYear: Int? = null,
    ): SearchMatchCandidate? {
        return this.mapNotNull { candidate ->
            val score = targetTitles.maxOfOrNull { title -> titleMatchScore(candidate.title, title) } ?: 0
            if (score < 85) return@mapNotNull null
            val yearBonus = when {
                expectedYear == null || candidate.year == null -> 0
                candidate.year == expectedYear -> 10
                else -> -15
            }
            candidate to (score + yearBonus)
        }.maxByOrNull { it.second }?.first
    }

    private fun String.urlEncodeCompat(): String = URLEncoder.encode(this, "UTF-8")

    private fun isReliableCineSrcMediaUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lowerUrl = url.lowercase()
        if (lowerUrl.contains(".m3u8")) return true
        if (!lowerUrl.contains(".mp4")) return false
        val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        if (host.contains("cinesrc.") || host.contains("cineflix.")) return false
        if (lowerUrl.contains("/intro") || lowerUrl.contains("intro.") || lowerUrl.contains("bumper")) return false
        return true
    }

    private fun riveStreamSecretKey(input: String?): String {
        if (input == null) return "rive"
        return runCatching {
            val value = input
            val seedSum = value.toDoubleOrNull()?.toInt() ?: value.sumOf { it.code }
            val insert = riveStreamKeyList[seedSum.floorMod(riveStreamKeyList.size)]
            val splitIndex = (seedSum.floorMod(value.length) / 2).coerceAtLeast(0)
            val mixed = value.substring(0, splitIndex) + insert + value.substring(splitIndex)
            val hash = riveHash2(riveHash1(mixed))
            android.util.Base64.encodeToString(hash.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        }.getOrDefault("topSecret")
    }

    private fun riveHash1(input: String): String {
        var t = 0L
        input.forEachIndexed { index, char ->
            val r = char.code.toLong()
            t = (r + (t shl 6) + (t shl 16) - t).uint32()
            val shift = index % 5
            val i = rotateLeft32(t, shift)
            t = (t xor (i xor rotateChar8(r, index % 7))).uint32()
            t = (t + ((t ushr 11) xor (t shl 3))).uint32()
        }
        t = (t xor (t ushr 15)).uint32()
        t = (((t and 0xffffL) * 49842L) + ((((t ushr 16) * 49842L) and 0xffffL) shl 16)).uint32()
        t = (t xor (t ushr 13)).uint32()
        t = (((t and 0xffffL) * 40503L) + ((((t ushr 16) * 40503L) and 0xffffL) shl 16)).uint32()
        t = (t xor (t ushr 16)).uint32()
        return t.toString(16).padStart(8, '0')
    }

    private fun riveHash2(input: String): String {
        var n = (0xDEADBEEFL xor input.length.toLong()).uint32()
        input.forEachIndexed { index, char ->
            var r = char.code.toLong()
            r = (r xor (((131L * index + 89L) xor (r shl (index % 5))) and 255L)).uint32()
            n = (rotateLeft32(n, 7) xor r).uint32()
            val i = (n and 0xffffL) * 60205L
            val o = ((n ushr 16) * 60205L) shl 16
            n = (i + o).uint32()
            n = (n xor (n ushr 11)).uint32()
        }
        n = (n xor (n ushr 15)).uint32()
        n = (((n and 0xffffL) * 49842L) + (((n ushr 16) * 49842L) shl 16)).uint32()
        n = (n xor (n ushr 13)).uint32()
        n = (((n and 0xffffL) * 40503L) + (((n ushr 16) * 40503L) shl 16)).uint32()
        n = (n xor (n ushr 16)).uint32()
        n = (((n and 0xffffL) * 10196L) + (((n ushr 16) * 10196L) shl 16)).uint32()
        n = (n xor (n ushr 15)).uint32()
        return n.toString(16).padStart(8, '0')
    }

    private fun rotateLeft32(value: Long, shift: Int): Long {
        val cleanShift = shift and 31
        return if (cleanShift == 0) {
            value.uint32()
        } else {
            ((value shl cleanShift) or (value.uint32() ushr (32 - cleanShift))).uint32()
        }
    }

    private fun rotateChar8(value: Long, shift: Int): Long {
        val cleanShift = shift and 7
        return if (cleanShift == 0) {
            value and 0xffL
        } else {
            ((value shl cleanShift) or ((value and 0xffL) ushr (8 - cleanShift))).uint32()
        }
    }

    private fun Long.uint32(): Long = this and 0xffffffffL

    private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod

    private val riveStreamKeyList = listOf(
        "4Z7lUo", "gwIVSMD", "PLmz2elE2v", "Z4OFV0", "SZ6RZq6Zc", "zhJEFYxrz8",
        "FOm7b0", "axHS3q4KDq", "o9zuXQ", "4Aebt", "wgjjWwKKx", "rY4VIxqSN",
        "kfjbnSo", "2DyrFA1M", "YUixDM9B", "JQvgEj0", "mcuFx6JIek", "eoTKe26gL",
        "qaI9EVO1rB", "0xl33btZL", "1fszuAU", "a7jnHzst6P", "wQuJkX", "cBNhTJlEOf",
        "KNcFWhDvgT", "XipDGjST", "PCZJlbHoyt", "2AYnMZkqd", "HIpJh", "KH0C3iztrG",
        "W81hjts92", "rJhAT", "NON7LKoMQ", "NMdY3nsKzI", "t4En5v", "Qq5cOQ9H",
        "Y9nwrp", "VX5FYVfsf", "cE5SJG", "x1vj1", "HegbLe", "zJ3nmt4OA",
        "gt7rxW57dq", "clIE9b", "jyJ9g", "B5jXjMCSx", "cOzZBZTV", "FTXGy",
        "Dfh1q1", "ny9jqZ2POI", "X2NnMn", "MBtoyD", "qz4Ilys7wB", "68lbOMye",
        "3YUJnmxp", "1fv5Imona", "PlfvvXD7mA", "ZarKfHCaPR", "owORnX", "dQP1YU",
        "dVdkx", "qgiK0E", "cx9wQ", "5F9bGa", "7UjkKrp", "Yvhrj", "wYXez5Dg3",
        "pG4GMU", "MwMAu", "rFRD5wlM"
    )

    private suspend fun parseRiveStreamSource(source: RiveStreamVideo): ExtractorLink? {
        val rawUrl = source.url?.takeIf { it.isNotBlank() } ?: return null
        val baseName = source.source?.takeIf { it.isNotBlank() } ?: "Source"
        val qualityLabel = source.quality?.toString()?.takeIf { it.isNotBlank() }
        val label = buildString {
            append("RiveStream ")
            append(baseName)
            if (!qualityLabel.isNullOrBlank() && !baseName.contains("AsiaCloud", true)) {
                append(" [")
                append(qualityLabel)
                append("]")
            }
        }

        if (rawUrl.contains("proxy?url=")) {
            val decoded = URLDecoder.decode(rawUrl, "UTF-8")
            val encodedUrl = decoded.substringAfter("proxy?url=", "").substringBefore("&headers=")
            if (encodedUrl.isBlank()) return null
            val mediaUrl = URLDecoder.decode(encodedUrl, "UTF-8")
            val headerPayload = decoded.substringAfter("&headers=", "")
            val headerJson = runCatching { URLDecoder.decode(headerPayload, "UTF-8") }.getOrDefault("")
            val headerMap = runCatching {
                JSONObject(headerJson).let { json ->
                    json.keys().asSequence().associateWith { json.getString(it) }
                }
            }.getOrDefault(emptyMap())
            val referer = headerMap["Referer"].orEmpty()
            val origin = headerMap["Origin"].orEmpty()

            return newExtractorLink(
                label,
                label,
                mediaUrl,
                if (mediaUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else INFER_TYPE
            ) {
                this.quality = Qualities.P1080.value
                this.referer = referer
                this.headers = buildMap {
                    if (referer.isNotBlank()) put("Referer", referer)
                    if (origin.isNotBlank()) put("Origin", origin)
                    put("User-Agent", USER_AGENT)
                }
            }
        }

        return newExtractorLink(
            "$label (VLC)",
            "$label (VLC)",
            rawUrl,
            if (rawUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else INFER_TYPE
        ) {
            this.quality = Qualities.P1080.value
        }
    }

    private data class SearchMatchCandidate(
        val url: String,
        val title: String?,
        val year: Int? = null,
        val sourceQuery: String,
        val payload: Any? = null,
    )

    private data class SiteSourceButton(
        val url: String,
        val server: String,
        val quality: String,
    )


}
