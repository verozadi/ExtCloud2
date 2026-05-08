package com.pusatfilm21

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.Gdriveplayer
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URLDecoder

class PusatHydrax : VidHidePro() {
    override var name = "Hydrax"
    override var mainUrl = "https://playhydrax.com"
}

class PusatGdriveplayer : Gdriveplayer() {
    override val mainUrl: String = "https://gdriveplayer.to"
}

open class PusatEmturbovid : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val page = app.get(url, referer = referer ?: "$mainUrl/")
        val embedReferer = page.url
        val origin = originOf(embedReferer)

        var masterUrl =
            page.document.selectFirst("#video_player[data-hash]")?.attr("data-hash")?.trim().orEmpty()

        if (masterUrl.isBlank()) {
            val playerScript = page.document.select("script").joinToString("\n") { it.data() }
            masterUrl =
                Regex("""var\s+urlPlay\s*=\s*['"]([^'"]+)['"]""")
                    .find(playerScript)
                    ?.groupValues
                    ?.getOrNull(1)
                    .orEmpty()
                    .trim()
        }

        if (masterUrl.isBlank()) return null

        masterUrl = when {
            masterUrl.startsWith("//") -> "https:$masterUrl"
            masterUrl.startsWith("/") -> "$origin$masterUrl"
            else -> masterUrl
        }

        val playbackHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
        )
        val generated =
            generateM3u8(
                source = name,
                name = name,
                streamUrl = masterUrl,
                referer = "",
                headers = playbackHeaders,
            ).distinctBy { it.url }

        if (generated.isNotEmpty()) return generated

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = masterUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.headers = playbackHeaders
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun originOf(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}

class PusatTurbovidHls : PusatEmturbovid() {
    override var mainUrl = "https://turbovidhls.com"
}

class KotakAjaib : ExtractorApi() {
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
        val document = app.get(embedUrl, referer = referer).document

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
