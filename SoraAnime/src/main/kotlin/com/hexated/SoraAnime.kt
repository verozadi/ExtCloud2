package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Calendar

class SoraAnime : MainAPI() {
    override var name = "SoraAnime🐇"
    override var mainUrl = "https://anilist.co/search/anime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val instantLinkLoading = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "TRENDING_DESC" to "Trending Anime",
        "POPULARITY_DESC" to "Popular Anime",
        "SCORE_DESC" to "Top Rated Anime",
        "CURRENT" to "Season Ini",
        "NEXT" to "Season Berikutnya",
        "MOVIE" to "Anime Movie",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val season = currentAniSeason()
        val variables = when (request.data) {
            "CURRENT" -> mapOf(
                "page" to page,
                "season" to season.first,
                "seasonYear" to season.second,
                "sort" to listOf("POPULARITY_DESC")
            )
            "NEXT" -> {
                val next = nextAniSeason()
                mapOf(
                    "page" to page,
                    "season" to next.first,
                    "seasonYear" to next.second,
                    "sort" to listOf("POPULARITY_DESC")
                )
            }
            "MOVIE" -> mapOf(
                "page" to page,
                "format" to listOf("MOVIE"),
                "sort" to listOf("POPULARITY_DESC", "SCORE_DESC")
            )
            else -> mapOf("page" to page, "sort" to listOf(request.data))
        }
        val response = anilistPage(variables)
        return newHomePageResponse(
            request.name,
            response.data?.Page?.media.orEmpty().mapNotNull { it.toSearchResponse() },
            response.data?.Page?.pageInfo?.hasNextPage == true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return anilistPage(
            mapOf(
                "page" to 1,
                "search" to query.trim(),
                "sort" to listOf("SEARCH_MATCH", "POPULARITY_DESC")
            )
        ).data?.Page?.media.orEmpty().mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val initial = parseJson<LinkData>(url)
        val media = anilistMedia(initial.id)
        val data = media.toLinkData()
        val type = media.type()
        val title = media.bestTitle()
        val poster = media.coverImage?.extraLarge ?: media.coverImage?.large
        val year = media.startDate?.year
        val description = media.description?.replace(Regex("<[^>]+>"), "")?.trim()
        val tags = media.genres?.filterNotNull().orEmpty()
        val status = when (media.status) {
            "RELEASING" -> ShowStatus.Ongoing
            "FINISHED" -> ShowStatus.Completed
            else -> null
        }

        if (type == TvType.AnimeMovie) {
            return newMovieLoadResponse(title, url, type, data.toJson()) {
                posterUrl = poster
                backgroundPosterUrl = media.bannerImage
                this.year = year
                plot = description
                this.tags = tags
            }
        }

        val totalEpisodes = media.knownEpisodeCount()
        val episodes = (1..totalEpisodes).map { ep ->
            newEpisode(data.copy(episode = ep).toJson()) {
                name = "Episode $ep"
                episode = ep
            }
        }

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = poster
            backgroundPosterUrl = media.bannerImage
            this.year = year
            plot = description
            this.tags = tags
            showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseJson<LinkData>(data)
        val titles = linkData.titleCandidates()
        val episode = linkData.episode
        val providers = installedAnimeProviders()
        val semaphore = Semaphore(6)

        supervisorScope {
            providers.map { provider ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            val result = provider.search(titles.firstOrNull().orEmpty()).orEmpty()
                                .firstOrNull { it.type?.isAnimeType() == true && titleMatches(it.name, titles) }
                                ?: return@runCatching
                            val response = provider.load(result.url) ?: return@runCatching
                            providerEpisodeData(response, episode).forEach { episodeData ->
                                provider.loadLinks(episodeData, false, subtitleCallback, callback)
                            }
                        }.onFailure {
                            if (it is CancellationException) throw it
                            logError(it)
                        }
                    }
                }
            }.awaitAll()
        }
        return true
    }

    private suspend fun anilistPage(variables: Map<String, Any?>): AnilistResponse {
        val query = """
            query (
              ${'$'}page: Int,
              ${'$'}search: String,
              ${'$'}season: MediaSeason,
              ${'$'}seasonYear: Int,
              ${'$'}format: [MediaFormat],
              ${'$'}sort: [MediaSort]
            ) {
              Page(page: ${'$'}page, perPage: 20) {
                pageInfo { hasNextPage }
                media(
                  type: ANIME,
                  search: ${'$'}search,
                  season: ${'$'}season,
                  seasonYear: ${'$'}seasonYear,
                  format_in: ${'$'}format,
                  sort: ${'$'}sort
                ) {
                  ${mediaFields()}
                }
              }
            }
        """.trimIndent()
        return anilistPost(query, variables.filterValues { it != null })
    }

    private suspend fun anilistMedia(id: Int?): AnilistMedia {
        val query = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                ${mediaFields()}
              }
            }
        """.trimIndent()
        return anilistPost(query, mapOf("id" to id)).data?.Media
            ?: throw ErrorLoadingException("AniList media not found")
    }

    private suspend fun anilistPost(query: String, variables: Map<String, Any?>): AnilistResponse {
        val body = mapOf("query" to query, "variables" to variables)
            .toJson()
            .toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        return app.post(ANILIST_API, requestBody = body).parsed()
    }

    private fun mediaFields(): String {
        return """
            id
            idMal
            format
            status
            episodes
            duration
            season
            seasonYear
            description(asHtml: false)
            bannerImage
            genres
            title { romaji english native }
            coverImage { extraLarge large }
            startDate { year month day }
            nextAiringEpisode { episode }
        """.trimIndent()
    }

    private fun AnilistMedia.toSearchResponse(): SearchResponse? {
        val title = bestTitle().takeIf { it.isNotBlank() } ?: return null
        return newAnimeSearchResponse(title, toLinkData().toJson(), type()) {
            posterUrl = coverImage?.extraLarge ?: coverImage?.large
        }
    }

    private fun AnilistMedia.toLinkData(): LinkData {
        return LinkData(
            id = id,
            malId = idMal,
            title = title?.romaji ?: title?.english,
            englishTitle = title?.english,
            nativeTitle = title?.native,
            format = format,
            episode = null
        )
    }

    private fun AnilistMedia.bestTitle(): String {
        return title?.english ?: title?.romaji ?: title?.native ?: "Anime"
    }

    private fun AnilistMedia.type(): TvType {
        return when (format) {
            "MOVIE" -> TvType.AnimeMovie
            "OVA", "ONA", "SPECIAL" -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun AnilistMedia.knownEpisodeCount(): Int {
        val known = episodes ?: nextAiringEpisode?.episode?.minus(1)
        return known?.coerceAtLeast(1) ?: 1
    }

    private fun LinkData.titleCandidates(): List<String> {
        return listOfNotNull(title, englishTitle, nativeTitle)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun installedAnimeProviders(): List<MainAPI> {
        val allowedNames = setOf(
            "animasu",
            "animesail",
            "animexin",
            "anixcafe",
            "samehadaku",
            "otakudesu",
            "anoboy",
            "kuramanime",
            "kuronime",
            "nekokun",
            "nimegami",
            "oploverz",
            "anichin",
            "auratail",
            "gojodesu",
            "hidoristream",
            "nontonanimeid",
            "zoronime",
            "winbu",
            "kazefuri",
        )
        val apiHolder = APIHolder::class.java
        return listOf("apis", "allProviders", "allApis", "apiProviders")
            .firstNotNullOfOrNull { fieldName ->
                runCatching {
                    val field = apiHolder.getDeclaredField(fieldName)
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    (field.get(APIHolder) as? List<MainAPI>)?.filter { api ->
                        api.name != name &&
                            api.supportedTypes.any { it.isAnimeType() } &&
                            allowedNames.any { allowed -> normalizeProviderName(api.name).contains(allowed) }
                    }
                }.getOrNull()
            }.orEmpty()
    }

    private fun providerEpisodeData(response: LoadResponse, episode: Int?): List<String> {
        val episodes = reflectEpisodes(response)
        if (episodes.isNotEmpty()) {
            val selected = if (episode == null) {
                episodes.take(1)
            } else {
                episodes.filter { getReflectInt(it, "episode") == episode }
                    .ifEmpty {
                        episodes.filter {
                            getReflectString(it, "name")?.contains(Regex("""\b0*${episode}\b""")) == true
                        }
                    }
            }
            return selected.mapNotNull { getReflectString(it, "data") }.distinct()
        }

        return listOfNotNull(
            getReflectString(response, "dataUrl"),
            getReflectString(response, "url")
        ).distinct()
    }

    private fun reflectEpisodes(response: LoadResponse): List<Any> {
        val raw = getReflectValue(response, "episodes") ?: return emptyList()
        return when (raw) {
            is Map<*, *> -> raw.values.flatMap { value ->
                when (value) {
                    is Iterable<*> -> value.filterNotNull()
                    else -> listOfNotNull(value)
                }
            }
            is Iterable<*> -> raw.filterNotNull()
            is Array<*> -> raw.filterNotNull()
            else -> emptyList()
        }
    }

    private fun getReflectValue(target: Any, fieldName: String): Any? {
        return runCatching {
            val field = target.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(target)
        }.getOrNull() ?: runCatching {
            target.javaClass.methods.firstOrNull { method ->
                method.parameterTypes.isEmpty() &&
                    method.name.equals("get${fieldName.replaceFirstChar { it.uppercase() }}", true)
            }?.invoke(target)
        }.getOrNull()
    }

    private fun getReflectString(target: Any, fieldName: String): String? {
        return getReflectValue(target, fieldName)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun getReflectInt(target: Any, fieldName: String): Int? {
        return when (val value = getReflectValue(target, fieldName)) {
            is Int -> value
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun titleMatches(title: String?, candidates: List<String>): Boolean {
        val normalizedTitle = normalizeTitle(title)
        if (normalizedTitle.isBlank()) return false
        return candidates.any { candidate ->
            val normalizedCandidate = normalizeTitle(candidate)
            normalizedTitle == normalizedCandidate ||
                normalizedTitle == stripSeason(normalizedCandidate) ||
                stripSeason(normalizedTitle) == normalizedCandidate
        }
    }

    private fun normalizeTitle(value: String?): String {
        return value.orEmpty()
            .lowercase()
            .replace(Regex("""\[[^\]]*]|\([^)]*\)"""), " ")
            .replace(Regex("""\b(?:sub|subbed|dub|dubbed|subtitle|indonesia|batch|bd|ova|ona|special)\b"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private fun stripSeason(value: String): String {
        return value.replace(Regex("""\b(?:season|s)\s*\d+\b.*$"""), "").trim()
    }

    private fun normalizeProviderName(value: String): String {
        return value.lowercase().replace(Regex("""[^a-z0-9]+"""), "")
    }

    private fun TvType.isAnimeType(): Boolean {
        return this == TvType.Anime || this == TvType.AnimeMovie || this == TvType.OVA
    }

    private fun currentAniSeason(): Pair<String, Int> {
        val calendar = Calendar.getInstance()
        return aniSeason(calendar.get(Calendar.MONTH) + 1) to calendar.get(Calendar.YEAR)
    }

    private fun nextAniSeason(): Pair<String, Int> {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MONTH, 3)
        return aniSeason(calendar.get(Calendar.MONTH) + 1) to calendar.get(Calendar.YEAR)
    }

    private fun aniSeason(month: Int): String {
        return when (month) {
            12, 1, 2 -> "WINTER"
            3, 4, 5 -> "SPRING"
            6, 7, 8 -> "SUMMER"
            else -> "FALL"
        }
    }

    data class LinkData(
        val id: Int? = null,
        val malId: Int? = null,
        val title: String? = null,
        val englishTitle: String? = null,
        val nativeTitle: String? = null,
        val format: String? = null,
        val episode: Int? = null,
    )

    data class AnilistResponse(
        @param:JsonProperty("data") val data: AnilistData? = null,
    )

    data class AnilistData(
        @param:JsonProperty("Page") val Page: AnilistPage? = null,
        @param:JsonProperty("Media") val Media: AnilistMedia? = null,
    )

    data class AnilistPage(
        @param:JsonProperty("pageInfo") val pageInfo: PageInfo? = null,
        @param:JsonProperty("media") val media: List<AnilistMedia>? = null,
    )

    data class PageInfo(
        @param:JsonProperty("hasNextPage") val hasNextPage: Boolean? = null,
    )

    data class AnilistMedia(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("idMal") val idMal: Int? = null,
        @param:JsonProperty("format") val format: String? = null,
        @param:JsonProperty("status") val status: String? = null,
        @param:JsonProperty("episodes") val episodes: Int? = null,
        @param:JsonProperty("duration") val duration: Int? = null,
        @param:JsonProperty("season") val season: String? = null,
        @param:JsonProperty("seasonYear") val seasonYear: Int? = null,
        @param:JsonProperty("description") val description: String? = null,
        @param:JsonProperty("bannerImage") val bannerImage: String? = null,
        @param:JsonProperty("genres") val genres: List<String?>? = null,
        @param:JsonProperty("title") val title: AnilistTitle? = null,
        @param:JsonProperty("coverImage") val coverImage: AnilistCover? = null,
        @param:JsonProperty("startDate") val startDate: AnilistDate? = null,
        @param:JsonProperty("nextAiringEpisode") val nextAiringEpisode: NextAiringEpisode? = null,
    )

    data class AnilistTitle(
        @param:JsonProperty("romaji") val romaji: String? = null,
        @param:JsonProperty("english") val english: String? = null,
        @param:JsonProperty("native") val native: String? = null,
    )

    data class AnilistCover(
        @param:JsonProperty("extraLarge") val extraLarge: String? = null,
        @param:JsonProperty("large") val large: String? = null,
    )

    data class AnilistDate(
        @param:JsonProperty("year") val year: Int? = null,
        @param:JsonProperty("month") val month: Int? = null,
        @param:JsonProperty("day") val day: Int? = null,
    )

    data class NextAiringEpisode(
        @param:JsonProperty("episode") val episode: Int? = null,
    )

    companion object {
        private const val ANILIST_API = "https://graphql.anilist.co"
    }
}
