package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

open class Jeniusplay2 : ExtractorApi() {
    override val name = "Jeniusplay"
    override val mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true
    private val cloudflareInterceptor by lazy { CloudflareKiller() }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalizedUrl = if (url.startsWith("//")) "https:$url" else url
        val pageRef = referer?.takeIf { it.isNotBlank() }
            ?: normalizedUrl.substringBefore("#").takeIf { it.contains("jeniusplay", true) }
            ?: "$mainUrl/"
        val document = app.get(
            normalizedUrl,
            referer = pageRef,
            interceptor = cloudflareInterceptor
        ).document
        val hash = Regex("""[?&]data=([^&#]+)""").find(normalizedUrl)?.groupValues?.getOrNull(1)
            ?: normalizedUrl.split("/").lastOrNull()?.substringAfter("data=")
            ?: return

        val response = app.post(
            url = "$mainUrl/player/index.php?data=$hash&do=getVideo",
            data = mapOf("hash" to hash, "r" to pageRef),
            referer = pageRef,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl,
                "Referer" to pageRef
            ),
            interceptor = cloudflareInterceptor
        ).parsed<ResponseSource>()
            ?: app.post(
                url = "$mainUrl/player/index.php?data=$hash&do=getVideo",
                data = mapOf("hash" to hash, "r" to pageRef),
                referer = pageRef,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                interceptor = cloudflareInterceptor
            ).parsed<ResponseSource>()
            ?: return

        val streamUrl = (response.securedLink ?: response.videoSource)
            ?.replace(".txt", ".m3u8")
            ?.takeIf { it.isNotBlank() }
            ?: return
        val streamHeaders = mapOf(
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/",
            "Accept" to "*/*"
        )

        if (streamUrl.contains(".m3u8", true)) {
            M3u8Helper.generateM3u8(
                name,
                streamUrl,
                "$mainUrl/",
                headers = streamHeaders
            ).forEach(callback)
        } else {
            callback.invoke(
                newExtractorLink(
                    name = "Jenius AUTO",
                    source = this.name,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = pageRef
                    this.headers = streamHeaders
                }
            )
        }


        document.select("script").map { script ->
            if (script.data().contains("eval(function(p,a,c,k,e,d)")) {
                val subData =
                    getAndUnpack(script.data()).substringAfter("\"tracks\":[").substringBefore("],")
                tryParseJson<List<Tracks>>("[$subData]")?.map { subtitle ->
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            getLanguage(subtitle.label ?: ""),
                            subtitle.file
                        )
                    )
                }
            }
        }
    }

    private fun getLanguage(str: String): String {
        return when {
            str.contains("indonesia", true) || str
                .contains("bahasa", true) -> "Indonesian"
            else -> str
        }
    }

    data class ResponseSource(
        @JsonProperty("hls") val hls: Boolean,
        @JsonProperty("videoSource") val videoSource: String,
        @JsonProperty("securedLink") val securedLink: String?,
    )

    data class Tracks(
        @JsonProperty("kind") val kind: String?,
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String?,
    )
}

open class Majorplay2 : ExtractorApi() {
    override val name = "Majorplay"
    override val mainUrl = "https://majorplay.net"
    override val requiresReferer = true
    private val cloudflareInterceptor by lazy { CloudflareKiller() }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val mediaRegex = Regex(
            """https?://[^"'\s]+(?:\.m3u8|\.mp4)[^"'\s]*""",
            RegexOption.IGNORE_CASE
        )

        val resolvedUrl = runCatching {
            app.get(
                url,
                referer = referer,
                interceptor = WebViewResolver(
                    interceptUrl = mediaRegex,
                    additionalUrls = listOf(
                        Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""", RegexOption.IGNORE_CASE),
                        Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""", RegexOption.IGNORE_CASE),
                        Regex("""https?://[^"'\s]+\.txt[^"'\s]*""", RegexOption.IGNORE_CASE),
                    ),
                    useOkhttp = false,
                    timeout = 20_000L
                )
            ).url.substringBefore('#')
        }.getOrNull() ?: run {
            val doc = runCatching { app.get(url, referer = referer, interceptor = cloudflareInterceptor).document }.getOrNull()
            val src = doc?.selectFirst("video source[src], source[src]")?.attr("abs:src")?.trim().orEmpty()
            val videoSrc = doc?.selectFirst("video[src]")?.attr("abs:src")?.trim().orEmpty()
            val scriptData = doc?.select("script")?.joinToString("\n") { it.data() }.orEmpty()

            val patterns = listOf(
                Regex("""["']hlsUrl["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""["']file["']\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""["']src["']\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""["']url["']\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""sources\s*:\s*\[\s*\{[^}]*["']file["']\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""", RegexOption.IGNORE_CASE),
                Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""", RegexOption.IGNORE_CASE),
            )

            val scriptUrl = patterns.firstNotNullOfOrNull { regex ->
                regex.find(scriptData)?.groupValues?.getOrNull(1)?.trim()
            } ?: ""

            // Extract subtitles from packed script (same pattern as Jeniusplay)
            doc?.select("script")?.forEach { script ->
                if (script.data().contains("eval(function(p,a,c,k,e,d)")) {
                    val unpacked = getAndUnpack(script.data())
                    val subData = unpacked.substringAfter("\"tracks\":[", "").substringBefore("],", "")
                    if (subData.isNotBlank()) {
                        tryParseJson<List<Tracks>>("[$subData]")?.map { subtitle ->
                            subtitleCallback.invoke(
                                newSubtitleFile(
                                    getLanguage(subtitle.label ?: ""),
                                    subtitle.file
                                )
                            )
                        }
                    }
                }
            }

            // Extract subtitles from HTML track elements
            doc?.select("track[kind=captions], track[kind=subtitles]")?.forEach { track ->
                val subSrc = track.attr("abs:src").trim().takeIf { it.isNotBlank() } ?: return@forEach
                val subLabel = track.attr("label").trim().takeIf { it.isNotBlank() } ?: track.attr("srclang").trim()
                subtitleCallback.invoke(
                    newSubtitleFile(
                        getLanguage(subLabel),
                        subSrc
                    )
                )
            }

            // Extract subtitles from script direct URLs (.vtt, .srt, .ass)
            Regex("""https?://[^"'\s]+\.(?:vtt|srt|ass)(?:\?[^"'\s]*)?""", RegexOption.IGNORE_CASE)
                .findAll(scriptData)
                .map { it.value }
                .distinct()
                .forEach { subUrl ->
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            getLanguage(""),
                            subUrl
                        )
                    )
                }

            listOf(src, videoSrc, scriptUrl).firstOrNull { it.startsWith("http", ignoreCase = true) }
        } ?: return

        if (resolvedUrl.contains(".m3u8", ignoreCase = true)) {
            M3u8Helper.generateM3u8(name, resolvedUrl, referer ?: url).forEach(callback)
            return
        }

        callback.invoke(
            newExtractorLink(name, name, resolvedUrl, ExtractorLinkType.VIDEO) {
                this.referer = referer ?: url
            }
        )
    }

    data class Tracks(
        @JsonProperty("kind") val kind: String?,
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String?,
    )

    private fun getLanguage(str: String): String {
        return when {
            str.contains("indonesia", true) || str.contains("bahasa", true) || str.contains("indonesian", true) -> "Indonesian"
            str.contains("english", true) -> "English"
            else -> str.ifBlank { "Unknown" }
        }
    }
}

