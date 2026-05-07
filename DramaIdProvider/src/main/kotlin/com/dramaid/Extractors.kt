package com.dramaid

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

class DramaIdHalahgan : ExtractorApi() {
    override val name = "Halahgan"
    override val mainUrl = "https://stordl.halahgan.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = normalizeUrl(url, mainUrl) ?: return
        val id = fixedUrl.substringAfterLast("/").substringBefore("?").trim()
        if (id.isBlank()) return

        val pageReferer = "$mainUrl/"
        val quality = qualityFromUrl(fixedUrl)
        val stream = resolveApi("$mainUrl/streaming//$id?action=stream-url&id=$id", pageReferer)
            ?: resolveFromStreamingPage("$mainUrl/streaming/$id")
        if (stream != null) {
            emit(stream, "Stream", pageReferer, quality, callback)
        }

        val nameParam = fixedUrl.substringAfter("?", "")
            .split("&")
            .firstOrNull { it.substringBefore("=") == "name" }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
        val fileApi = buildString {
            append("$mainUrl/$id?action=file-url&id=$id")
            if (nameParam != null) append("&name=").append(nameParam)
        }
        val download = resolveApi(fileApi, fixedUrl)
        if (download != null && download != stream) {
            emit(download, "Download", pageReferer, quality, callback)
        }
    }

    private suspend fun resolveApi(apiUrl: String, referer: String): String? {
        val response = runCatching {
            app.get(
                apiUrl,
                referer = referer,
                headers = mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "User-Agent" to USER_AGENT,
                )
            ).text
        }.getOrNull() ?: return null

        return (tryParseJson<Source>(response)?.url
            ?: Regex(""""url"\s*:\s*"([^"]+)"""")
                .find(response)
                ?.groupValues
                ?.getOrNull(1))
            ?.jsonUrlDecode()
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun resolveFromStreamingPage(streamingUrl: String): String? {
        val document = runCatching {
            app.get(streamingUrl, referer = "$mainUrl/", headers = mapOf("User-Agent" to USER_AGENT)).document
        }.getOrNull() ?: return null

        document.selectFirst("video source[src], video[src]")?.let { source ->
            val sourceUrl = source.attr("abs:src").ifBlank { source.attr("src") }
            if (sourceUrl.isNotBlank()) return sourceUrl
        }

        val api = Regex("""STREAM_URL_API\s*=\s*["']([^"']+)["']""")
            .find(document.html())
            ?.groupValues
            ?.getOrNull(1)
            ?.let { normalizeUrl(it, streamingUrl) }
            ?: return null
        return resolveApi(api, streamingUrl)
    }

    private suspend fun emit(
        url: String,
        label: String,
        refererUrl: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = "$name $label",
                url = url,
                type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else INFER_TYPE,
            ) {
                referer = refererUrl
                this.quality = quality
                headers = mapOf(
                    "Referer" to refererUrl,
                    "Range" to "bytes=0-",
                    "User-Agent" to USER_AGENT,
                )
            }
        )
    }

    private data class Source(val url: String? = null)
}

class DramaIdBerkasDrive : ExtractorApi() {
    override val name = "BerkasDrive"
    override val mainUrl = "https://dl.berkasdrive.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = normalizeUrl(url, mainUrl) ?: return
        val document = app.get(
            fixedUrl,
            referer = referer ?: "https://drama-id.com/",
            headers = mapOf("User-Agent" to USER_AGENT)
        ).document

        val emitted = linkedSetOf<String>()
        document.select("video source[src], video[src], .daftar_server li[data-url]")
            .forEach { element ->
                val sourceUrl = element.attr("abs:src")
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("data-url") }
                    .takeIf { it.isNotBlank() }
                    ?.let { normalizeUrl(it, fixedUrl) }
                    ?: return@forEach
                if (!emitted.add(sourceUrl)) return@forEach
                emitDirect(sourceUrl, element.text().ifBlank { "Server" }, fixedUrl, callback)
            }

        decodeBerkasDriveId(fixedUrl)?.let { resolverUrl ->
            loadExtractor(resolverUrl, fixedUrl, subtitleCallback, callback)
        }
    }

    private suspend fun emitDirect(
        url: String,
        label: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink(
                source = name,
                name = "$name ${label.cleanLabel()}",
                url = url,
                type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                referer = refererUrl
                quality = qualityFromUrl(url)
                headers = mapOf(
                    "Referer" to refererUrl,
                    "Range" to "bytes=0-",
                    "User-Agent" to USER_AGENT,
                )
            }
        )
    }
}

private fun decodeBerkasDriveId(url: String): String? {
    if (!url.contains("dl.berkasdrive.com/streaming", true)) return null
    return url.substringAfter("?", "")
        .split("&")
        .firstOrNull { it.substringBefore("=") == "id" }
        ?.substringAfter("=")
        ?.let { URLDecoder.decode(it, "UTF-8") }
        ?.let(::decodeBase64)
        ?.let { normalizeUrl(it, "https://drama-id.com/") }
}

private fun decodeBase64(value: String): String? {
    val clean = value.trim().replace("\\s".toRegex(), "")
    if (clean.isBlank()) return null
    val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
    return runCatching { String(Base64.getDecoder().decode(padded)) }.getOrNull()
}

private fun normalizeUrl(raw: String, baseUrl: String): String? {
    val clean = Jsoup.parse(raw).text()
        .trim()
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

private fun String.jsonUrlDecode(): String {
    return replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003f", "?")
        .replace("\\u002F", "/")
}

private fun qualityFromUrl(value: String): Int {
    return Regex("""\b(2160|1440|1080|720|480|360|240)p?\b""", RegexOption.IGNORE_CASE)
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: Qualities.Unknown.value
}

private fun String.cleanLabel(): String {
    return replace(Regex("""\s+"""), " ").trim().ifBlank { "Server" }
}
