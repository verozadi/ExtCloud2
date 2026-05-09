package com.hidoristream

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import java.net.URI
import com.lagradost.api.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import com.lagradost.cloudstream3.utils.HlsPlaylistParser
import com.lagradost.cloudstream3.utils.INFER_TYPE
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

class HidoristreamPlayer : ExtractorApi() {
    override val name = "Hidoristream"
    override val mainUrl = "https://stream.hidoristream.my.id"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = normalizeEmbedUrl(url)
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
        )
        val page = app.get(embedUrl, referer = referer ?: "$mainUrl/", headers = mapOf("User-Agent" to USER_AGENT)).text
        val vars = extractPlayerVars(page) ?: return
        val apiBase = vars.baseUrl.ifBlank { "$mainUrl/" }
        val apiUrl = "${apiBase.trimEnd('/')}/api/?p=${vars.ps}"
        val encryptedSources = app.post(
            apiUrl,
            referer = embedUrl,
            headers = headers,
            requestBody = vars.kaken.toRequestBody("text/plain".toMediaTypeOrNull()),
        ).text

        val cryptoJs = runCatching {
            app.get("$mainUrl/assets/vendor/crypto-js/4.2.0/crypto-js.min.js", headers = mapOf("User-Agent" to USER_AGENT)).text
        }.getOrNull() ?: return
        val sourceJson = decryptPlayerResponse(encryptedSources, vars.pd, cryptoJs) ?: return
        val sources = runCatching { JSONObject(sourceJson).optJSONArray("sources") }.getOrNull() ?: return

        for (i in 0 until sources.length()) {
            val item = sources.optJSONObject(i) ?: continue
            val file = item.optString("file").trim().takeIf { it.isNotBlank() } ?: continue
            val label = item.optString("label").trim().ifBlank { "Stream" }
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name $label",
                    url = file,
                    type = INFER_TYPE,
                ) {
                    this.referer = embedUrl
                    this.quality = getQualityFromName(label)
                    this.headers = mapOf("Referer" to embedUrl, "User-Agent" to USER_AGENT)
                }
            )
        }
    }

    private fun normalizeEmbedUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http", true) -> url
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun extractPlayerVars(html: String): PlayerVars? {
        val script = Regex("""<script[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1] }
            .firstOrNull { it.length > 10_000 }
            ?: return null

        var result: PlayerVars? = null
        val runner = Runnable {
            val rhino = Context.enter()
            rhino.optimizationLevel = -1
            val scope = rhino.initSafeStandardObjects()
            scope.put("window", scope, scope)
            try {
                rhino.evaluateString(scope, script, "HidoristreamPlayerVars", 1, null)
                fun getVar(key: String): String {
                    val value = scope.get(key, scope)
                    return if (value == Scriptable.NOT_FOUND) "" else Context.toString(value)
                }

                val baseUrl = getVar("baseURL")
                val apx = getVar("apx")
                val kaken = getVar("kaken")
                val ps = getVar("ps")
                val pd = getVar("pd")
                if (kaken.isNotBlank() && ps.isNotBlank() && pd.isNotBlank()) {
                    result = PlayerVars(
                        baseUrl = baseUrl,
                        apiConfigBase = runCatching { base64Decode(apx.replace(',', '=')) }.getOrDefault(""),
                        kaken = kaken,
                        ps = ps,
                        pd = pd,
                    )
                }
            } catch (e: Exception) {
                Log.e("HidoristreamPlayer", "Failed to decode player vars: ${e.message}")
            } finally {
                Context.exit()
            }
        }
        val thread = Thread(ThreadGroup("HidoristreamPlayer"), runner, "thread_hidoristream_vars", 8 * 1024 * 1024)
        thread.start()
        thread.join()
        thread.interrupt()
        return result
    }

    private fun decryptPlayerResponse(encrypted: String, pd: String, cryptoJs: String): String? {
        if (encrypted.trim().startsWith("{")) return encrypted

        var result: String? = null
        val runner = Runnable {
            val rhino = Context.enter()
            rhino.optimizationLevel = -1
            val scope = rhino.initSafeStandardObjects()
            scope.put("window", scope, scope)
            scope.put("encryptedResponse", scope, encrypted)
            scope.put("pd", scope, pd)
            try {
                rhino.evaluateString(scope, cryptoJs, "CryptoJS", 1, null)
                rhino.evaluateString(scope, DCX_SCRIPT, "HidoristreamDcx", 1, null)
                result = Context.toString(rhino.evaluateString(scope, "dcx(encryptedResponse);", "HidoristreamDecrypt", 1, null))
            } catch (e: Exception) {
                Log.e("HidoristreamPlayer", "Failed to decrypt player response: ${e.message}")
            } finally {
                Context.exit()
            }
        }
        val thread = Thread(ThreadGroup("HidoristreamPlayer"), runner, "thread_hidoristream_decrypt", 8 * 1024 * 1024)
        thread.start()
        thread.join()
        thread.interrupt()
        return result
    }

    data class PlayerVars(
        val baseUrl: String,
        val apiConfigBase: String,
        val kaken: String,
        val ps: String,
        val pd: String,
    )

    companion object {
        private val DCX_SCRIPT = """
            function dcx(text) {
                try {
                    if (text.indexOf("{") === 0) return text;
                    var parsed = CryptoJS.enc.Base64.parse(text);
                    var salt = CryptoJS.lib.WordArray.create(parsed.words.slice(0, 4));
                    var ciphertext = CryptoJS.lib.WordArray.create(parsed.words.slice(4));
                    var keyData = CryptoJS.PBKDF2(pd, salt, {
                        keySize: 12,
                        iterations: 10000,
                        hasher: CryptoJS.algo.SHA512
                    });
                    var key = CryptoJS.lib.WordArray.create(keyData.words.slice(0, 8));
                    var iv = CryptoJS.lib.WordArray.create(keyData.words.slice(8, 12));
                    return CryptoJS.AES.decrypt(
                        { ciphertext: ciphertext },
                        key,
                        {
                            iv: iv,
                            mode: CryptoJS.mode.CBC,
                            padding: CryptoJS.pad.Pkcs7
                        }
                    ).toString(CryptoJS.enc.Utf8);
                } catch (e) {
                    return "";
                }
            }
        """.trimIndent()
    }
}

class Movearnpre : Dingtezuni() {
    override var name = "Movearnpre"
    override var mainUrl = "https://movearnpre.com"
}

class Minochinos : Dingtezuni() {
    override var name = "Earnvids"
    override var mainUrl = "https://minochinos.com"
}

class Mivalyo : Dingtezuni() {
    override var name = "Earnvids"
    override var mainUrl = "https://mivalyo.com"
}

class Ryderjet : Dingtezuni() {
    override var name = "Ryderjet"
    override var mainUrl = "https://ryderjet.com"
}

class Bingezove : Dingtezuni() {
    override var name = "Earnvids"
    override var mainUrl = "https://bingezove.com"
}

open class Dingtezuni : ExtractorApi() {
    override val name = "Earnvids"
    override val mainUrl = "https://dingtezuni.com"
    override val requiresReferer = true

 override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
	        "User-Agent" to USER_AGENT,
        )
        
        val response = app.get(getEmbedUrl(url), referer = referer)
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            var result = getAndUnpack(response.text)
            if(result.contains("var links")){
                result = result.substringAfter("var links")
            }
            result
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        } ?: return

        // m3u8 urls could be prefixed by 'file:', 'hls2:' or 'hls4:', so we just match ':'
        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { m3u8Match ->
            generateM3u8(
                name,
                fixUrl(m3u8Match.groupValues[1]),
                referer = "$mainUrl/",
                headers = headers
            ).forEach(callback)
        }
    }

    private fun getEmbedUrl(url: String): String {
		return when {
			url.contains("/d/") -> url.replace("/d/", "/v/")
			url.contains("/download/") -> url.replace("/download/", "/v/")
			url.contains("/file/") -> url.replace("/file/", "/v/")
			else -> url.replace("/f/", "/v/")
		}
	}

}



class Hglink : StreamWishExtractor() {
    override val name = "Hglink"
    override val mainUrl = "https://hglink.to"
}

class Ghbrisk : StreamWishExtractor() {
    override val name = "Ghbrisk"
    override val mainUrl = "https://ghbrisk.com"
}

class Dhcplay: StreamWishExtractor() {
    override var name = "DHC Play"
    override var mainUrl = "https://dhcplay.com"
}

class Streamcasthub : VidStack() {
    override var name = "Streamcasthub"
    override var mainUrl = "https://live.streamcasthub.store"
    override var requiresReferer = true
}

class Dm21embed : VidStack() {
    override var name = "Dm21embed"
    override var mainUrl = "https://dm21.embed4me.vip"
    override var requiresReferer = true
}

class Serhmeplayer : VidStack() {
    override var name = "Serhmeplayer"
    override var mainUrl = "https://serh.4meplayer.online"
    override var requiresReferer = true
}

class Dm21upns : VidStack() {
    override var name = "Dm21upns"
    override var mainUrl = "https://dm21.upns.live"
    override var requiresReferer = true
}

class Pm21p2p : VidStack() {
    override var name = "Pm21p2p"
    override var mainUrl = "https://pm21.p2pplay.pro"
    override var requiresReferer = true
}

class Dm21 : VidStack() {
    override var name = "Dm21"
    override var mainUrl = "https://dm21.embed4me.vip"
    override var requiresReferer = true
}

class Meplayer : VidStack() {
    override var name = "Meplayer"
    override var mainUrl = "https://video.4meplayer.com"
    override var requiresReferer = true
}

open class Dintezuvio : ExtractorApi() {
    override val name = "Earnvids"
    override val mainUrl = "https://dintezuvio.com"
    override val requiresReferer = true

 override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Origin" to mainUrl,
	        "User-Agent" to USER_AGENT,
        )
        
        val response = app.get(getEmbedUrl(url), referer = referer)
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            var result = getAndUnpack(response.text)
            if(result.contains("var links")){
                result = result.substringAfter("var links")
            }
            result
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        } ?: return

        // m3u8 urls could be prefixed by 'file:', 'hls2:' or 'hls4:', so we just match ':'
        Regex(":\\s*\"(.*?m3u8.*?)\"").findAll(script).forEach { m3u8Match ->
            generateM3u8(
                name,
                fixUrl(m3u8Match.groupValues[1]),
                referer = "$mainUrl/",
                headers = headers
            ).forEach(callback)
        }
    }

    private fun getEmbedUrl(url: String): String {
		return when {
			url.contains("/d/") -> url.replace("/d/", "/v/")
			url.contains("/download/") -> url.replace("/download/", "/v/")
			url.contains("/file/") -> url.replace("/file/", "/v/")
			else -> url.replace("/f/", "/v/")
		}
	}

}

class Veev : ExtractorApi() {
    override val name = "Veev"
    override val mainUrl = "https://veev.to"
    override val requiresReferer = false

    private val pattern =
        Regex("""(?://|\.)(?:veev|kinoger|poophq|doods)\.(?:to|pw|com)/[ed]/([0-9A-Za-z]+)""")

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val mediaId = pattern.find(url)?.groupValues?.get(1)
            ?: return
        val pageUrl = "$mainUrl/e/$mediaId"
        val html = app.get(
            pageUrl,
            headers = mapOf("User-Agent" to DEFAULT_UA)
        ).text

        val encRegex = Regex("""[.\s'](?:fc|_vvto\[[^]]*)(?:['\]]*)?\s*[:=]\s*['"]([^'"]+)""")
        val foundValues = encRegex.findAll(html).map { it.groupValues[1] }.toList()

        if (foundValues.isEmpty()) return

        for (f in foundValues.reversed()) {
            val ch = veevDecode(f)
            if (ch == f) continue

            val dlUrl = "$mainUrl/dl?op=player_api&cmd=gi&file_code=$mediaId&r=$mainUrl&ch=$ch&ie=1"
            val responseText = app.get(dlUrl, headers = mapOf("User-Agent" to DEFAULT_UA)).text

            val json = try {
                JSONObject(responseText)
            } catch (_: Exception) {
                continue
            }
            val file = json.optJSONObject("file") ?: continue

            if (file.optString("file_status") != "OK") continue

            val dv = file.getJSONArray("dv").getJSONObject(0).getString("s")
            val decoded = decodeUrl(veevDecode(dv), buildArray(ch)[0])

            val fileMimeType = file.optString("file_mime_type", "")

            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    decoded,
                    INFER_TYPE
                )
                {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }
    }

    fun String.toExoPlayerMimeType(): String {
        return when (this.lowercase()) {
            "video/x-matroska", "video/webm" -> HlsPlaylistParser.MimeTypes.VIDEO_MATROSKA
            "video/mp4" -> HlsPlaylistParser.MimeTypes.VIDEO_MP4
            "application/x-mpegurl", "application/vnd.apple.mpegurl" -> HlsPlaylistParser.MimeTypes.APPLICATION_M3U8
            "video/avi" -> HlsPlaylistParser.MimeTypes.VIDEO_AVI
            else -> ""
        }
    }

    private fun veevDecode(etext: String): String {
        val result = StringBuilder()
        val lut = HashMap<Int, String>()
        var n = 256
        var c = etext[0].toString()
        result.append(c)

        for (char in etext.drop(1)) {
            val code = char.code
            val nc = if (code < 256) char.toString() else lut[code] ?: (c + c[0])
            result.append(nc)
            lut[n++] = c + nc[0]
            c = nc
        }
        return result.toString()
    }

    private fun jsInt(x: Char): Int = x.digitToIntOrNull() ?: 0

    private fun buildArray(encoded: String): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        val it = encoded.iterator()
        fun nextIntOrZero(): Int = if (it.hasNext()) jsInt(it.nextChar()) else 0
        var count = nextIntOrZero()
        while (count != 0) {
            val row = mutableListOf<Int>()
            repeat(count) {
                row.add(nextIntOrZero())
            }
            result.add(row.reversed())
            count = nextIntOrZero()
        }
        return result
    }


    private fun decodeUrl(encoded: String, rules: List<Int>): String {
        var text = encoded
        for (r in rules) {
            if (r == 1) text = text.reversed()
            val arr = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            text = arr.toString(Charsets.UTF_8).replace("dXRmOA==", "")
        }
        return text
    }
}

class BuzzServer : ExtractorApi() {
    override val name = "BuzzServer"
    override val mainUrl = "https://buzzheavier.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val qualityText = app.get(url).documentLarge.selectFirst("div.max-w-2xl > span")?.text()
            val quality = getQualityFromName(qualityText)
            val response = app.get("$url/download", referer = url, allowRedirects = false)
            val redirectUrl = response.headers["hx-redirect"] ?: ""

            if (redirectUrl.isNotEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        "BuzzServer",
                        "BuzzServer",
                        redirectUrl,
                    ) {
                        this.quality = quality
                    }
                )
            } else {
                Log.w("BuzzServer", "No redirect URL found in headers.")
            }
        } catch (e: Exception) {
            Log.e("BuzzServer", "Exception occurred: ${e.message}")
        }
    }
}
