package com.hexated

import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonProperty
import com.hexated.SoraExtractor.invokeKisskh
import com.hexated.SoraExtractor.invokeIdlix
import com.hexated.SoraExtractor.invokeYflix
import com.hexated.SoraExtractor.invokeVidfast
import com.hexated.SoraExtractor.invokeVidlink
import com.hexated.SoraExtractor.invokeVixsrc
import com.hexated.SoraExtractor.invokeVideasy
import com.hexated.SoraExtractor.invokeVidzen
import com.hexated.SoraExtractor.invokeCinezo
import com.hexated.SoraExtractor.invokeXprime
import com.hexated.SoraExtractor.invokeMapple
import com.hexated.SoraExtractor.invokeCinemaos
import com.hexated.SoraExtractor.invokeWave
import com.hexated.SoraExtractor.invokeUhdmovies
import com.hexated.SoraExtractor.invokeMultimovies
import com.hexated.SoraExtractor.invokeWatchsomuch
import com.hexated.SoraExtractor.invokeWyzie
import com.hexated.SoraExtractor.invokeCineSrc
import com.hexated.SoraExtractor.invokeMafiaEmbed
import com.hexated.SoraExtractor.invokeAutoEmbed
import com.hexated.SoraExtractor.invoke2Embed
import com.hexated.SoraExtractor.invokeMultiEmbed
import com.hexated.SoraExtractor.invokeNinetv
import com.hexated.SoraExtractor.invokeRidomovies
import com.hexated.SoraExtractor.invokeSoapy
import com.hexated.SoraExtractor.invokeVembed
import com.hexated.SoraExtractor.invokeAzmovies
import com.hexated.SoraExtractor.invokeNoxx
import com.hexated.SoraExtractor.invokeWatch32
import com.hexated.SoraExtractor.invokeSmashyStream
import com.hexated.SoraExtractor.invokeRiveStream
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.math.roundToInt


open class SoraAnime(val sharedPref: SharedPreferences? = null) : TmdbProvider() {
    override var name = "SoraAnime"
    override val hasMainPage = true
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
    override val hasChromecastSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
    )

    val token: String? = sharedPref?.getString("token", null)
    val langCode = sharedPref?.getString("tmdb_language_code", "en-US")

    val wpRedisInterceptor by lazy { CloudflareKiller() }

    /** AUTHOR : Hexated & Sora */
    companion object {
        /** TOOLS */
        var context: android.content.Context? = null

        private const val OFFICIAL_TMDB_URL = "https://api.themoviedb.org/3"
        private var currentBaseUrl: String? = null
        private val apiMutex = Mutex()

        const val gdbot = "https://gdtot.pro"
        const val anilistAPI = "https://graphql.anilist.co"
        const val malsyncAPI = "https://api.malsync.moe"
        const val jikanAPI = "https://api.jikan.moe/v4"

        const val apiKey = "b030404650f279792a8d3287232358e3"
        private const val sourceConcurrency = 8

        /** ALL SOURCES */
        const val idlixAPI = "https://z1.idlixku.com"
        const val yflixAPI = "https://yflix.to"
        const val azmoviesAPI = "https://azmovies.to"
        const val noxxAPI = "https://noxx.to"
        const val vidsrcccAPI = "https://vidsrc.cc"
        const val vidSrcAPI = "https://vidsrc.net"
        const val vidsrcMeAPI = "https://vidsrcme.ru"
        const val watchSomuchAPI = "https://watchsomuch.tv"
        const val vidlinkAPI = "https://vidlink.pro"
        const val vidfastAPI = "https://vidfast.pro"
        const val videasyAPI = "https://player.videasy.net"
        const val vidzenAPI = "https://vidzen.fun"
        const val cinezoAPI = "https://player.cinezo.live"
        const val xprimeAPI = "https://backend.xprime.tv"
        const val mappleAPI = "https://mapple.uk"
        const val cinemaosAPI = "https://cinemaos.tech"
        const val waveAPI = "https://wavembed.lol"
        const val uhdmoviesAPI = "https://uhdmovies.pink"
        const val multimoviesAPI = "https://multimovies.fyi"
        const val wyzieAPI = "https://sub.wyzie.ru"
        const val vixsrcAPI = "https://vixsrc.to"
        const val cinesrcAPI = "https://cinesrc.st"
        const val mafiaEmbedAPI = "https://embed.streammafia.to"
        const val autoEmbedAPI = "https://watch-v2.autoembed.app"
        const val twoEmbedAPI = "https://www.2embedstream.xyz"
        const val vidsrcMovAPI = "https://vidsrc.mov"
        const val multiEmbedAPI = "https://multiembed.mov"
        const val nineTvAPI = "https://moviesapi.club"
        const val ridomoviesAPI = "https://ridomovies.tv"
        const val soapyAPI = "https://soapy.to"
        const val watch32API = "https://watch32.sx"
        const val vembedAPI = "https://vembed.stream"
        const val smashyStreamAPI = "https://embed.smashystream.com"
        const val riveStreamAPI = "https://www.rivestream.app"

        enum class SourceGroup {
            CORE,
            EMBED,
            SUBTITLE,
            FALLBACK,
        }

        data class SourceDescriptor(
            val key: String,
            val group: SourceGroup,
            val priority: Int,
            val movie: Boolean = true,
            val tv: Boolean = true,
            val subtitleOnly: Boolean = false,
            val timeoutMs: Long = 20_000L,
        )

        val sourceRegistry = listOf(
            SourceDescriptor("vidlink", SourceGroup.CORE, 30),
            SourceDescriptor("vidfast", SourceGroup.CORE, 40),
            SourceDescriptor("videasy", SourceGroup.CORE, 45),
            SourceDescriptor("vixsrc", SourceGroup.EMBED, 50),
            SourceDescriptor("vidzen", SourceGroup.EMBED, 55),
            SourceDescriptor("cinezo", SourceGroup.EMBED, 58),
            SourceDescriptor("xprime", SourceGroup.EMBED, 59),
            SourceDescriptor("cinesrc", SourceGroup.EMBED, 60),
            SourceDescriptor("mapple", SourceGroup.EMBED, 62),
            SourceDescriptor("cinemaos", SourceGroup.EMBED, 64),
            SourceDescriptor("mafiaembed", SourceGroup.EMBED, 70),
            SourceDescriptor("autoembed", SourceGroup.EMBED, 80),
            SourceDescriptor("2embed", SourceGroup.EMBED, 90),
            SourceDescriptor("wave", SourceGroup.EMBED, 100),
            SourceDescriptor("multiembed", SourceGroup.EMBED, 110),
            SourceDescriptor("ninetv", SourceGroup.EMBED, 120),
            SourceDescriptor("ridomovies", SourceGroup.EMBED, 130),
            SourceDescriptor("soapy", SourceGroup.EMBED, 140),
            SourceDescriptor("rivestream", SourceGroup.EMBED, 150),
            SourceDescriptor("smashystream", SourceGroup.EMBED, 160),
            SourceDescriptor("vembed", SourceGroup.EMBED, 170, tv = false),
            SourceDescriptor("yflix", SourceGroup.EMBED, 175),
            SourceDescriptor("idlix", SourceGroup.FALLBACK, 200),
            SourceDescriptor("azmovies", SourceGroup.FALLBACK, 210, tv = false),
            SourceDescriptor("noxx", SourceGroup.FALLBACK, 220, movie = false),
            SourceDescriptor("watch32", SourceGroup.FALLBACK, 230),
            SourceDescriptor("uhdmovies", SourceGroup.FALLBACK, 235),
            SourceDescriptor("multimovies", SourceGroup.FALLBACK, 238),
            SourceDescriptor("kisskh", SourceGroup.FALLBACK, 240),
            SourceDescriptor("wyzie", SourceGroup.SUBTITLE, 260, subtitleOnly = true, timeoutMs = 10_000L),
            SourceDescriptor("watchsomuch", SourceGroup.SUBTITLE, 270, subtitleOnly = true, timeoutMs = 10_000L),
        ).sortedBy { it.priority }

        suspend fun getApiBase(): String {
            currentBaseUrl?.let { return it }

            return apiMutex.withLock {
                currentBaseUrl?.let { return it }
                currentBaseUrl = if (checkConnectivity(OFFICIAL_TMDB_URL)) {
                    OFFICIAL_TMDB_URL
                } else {
                    OFFICIAL_TMDB_URL
                }
                currentBaseUrl ?: OFFICIAL_TMDB_URL
            }
        }

        private suspend fun checkConnectivity(url: String): Boolean {
            return try {
                val response = app.get(
                    "$url/configuration?api_key=$apiKey",
                    timeout = 10
                )
                response.code == 200 || response.code == 304
            } catch (_: Exception) {
                false
            }
        }

        fun getType(t: String?): TvType {
            return when (t) {
                "movie" -> TvType.Movie
                else -> TvType.TvSeries
            }
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Returning Series" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

    }

    override val mainPage = mainPageOf(
        "/discover/tv?api_key=$apiKey&with_keywords=210024|222243&with_original_language=ja&sort_by=popularity.desc" to "Popular Anime",
        "/discover/tv?api_key=$apiKey&with_keywords=210024|222243&with_original_language=ja&sort_by=first_air_date.desc" to "Latest Anime",
        "/discover/tv?api_key=$apiKey&with_keywords=210024|222243&with_original_language=ja&air_date.lte=${getDate().today}&air_date.gte=${getDate().today}&sort_by=popularity.desc" to "Airing Today",
        "/discover/tv?api_key=$apiKey&with_keywords=210024|222243&with_original_language=ja&air_date.lte=${getDate().nextWeek}&air_date.gte=${getDate().today}&sort_by=popularity.desc" to "On The Air",
        "/discover/movie?api_key=$apiKey&with_keywords=210024|222243&with_original_language=ja&sort_by=popularity.desc" to "Anime Movies",
        "/discover/tv?api_key=$apiKey&with_keywords=210024|222243&with_original_language=zh&sort_by=popularity.desc" to "Donghua",
    )

    private fun getImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w500/$link" else link
    }

    private fun getOriImageUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/original/$link" else link
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val apiBase = getApiBase()
        val adultQuery =
            if (settingsForProvider.enableAdult) "" else "&without_keywords=190370|13059|226161|195669"
        val type = if (request.data.contains("/movie")) "movie" else "tv"
        val home = app.get("$apiBase${request.data}$adultQuery&page=$page")
            .parsedSafe<Results>()?.results?.mapNotNull { media ->
                media.toSearchResponse(type)
            } ?: throw ErrorLoadingException("Invalid Json reponse")
        return newHomePageResponse(request.name, home)
    }

    private fun Media.toSearchResponse(type: String? = null): SearchResponse? {
        val mediaType = mediaType ?: type
        return newAnimeSearchResponse(
            title ?: name ?: originalTitle ?: return null,
            Data(id = id, type = mediaType).toJson(),
            if (mediaType == "movie") TvType.AnimeMovie else TvType.Anime,
        ) {
            this.posterUrl = getImageUrl(posterPath)
            this.score= Score.from10(voteAverage)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        val apiBase = getApiBase()
        return app.get("$apiBase/search/multi?api_key=$apiKey&language=${langCode ?: "en-US"}&query=$query&page=1&include_adult=${settingsForProvider.enableAdult}")
            .parsedSafe<Results>()?.results
            ?.filter { media ->
                media.mediaType == "movie" || media.mediaType == "tv" || media.mediaType == null
            }
            ?.filter { media ->
                media.originalLanguage == "ja" || media.originalLanguage == "zh" ||
                    media.title?.contains("anime", true) == true ||
                    media.name?.contains("anime", true) == true
            }
            ?.mapNotNull { media -> media.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
    val data: Data? = try {
        parseJson<Data>(url)
    } catch (e: Exception) {
        // fallback kalau ternyata string url (misalnya tmdb.org/...)
        if (url.startsWith("http")) {
            val id = url.substringAfterLast("/").toIntOrNull()
            val type = if (url.contains("/movie/")) "movie" else "tv"
            Data(id, type)
        } else {
            null
        }
    }

    if (data == null || data.id == null) {
        throw ErrorLoadingException("Invalid Data Format: $url")
    }
        val apiBase = getApiBase()
        val type = getType(data.type)
        val append = "alternative_titles,credits,external_ids,keywords,videos,recommendations"
        val resUrl = if (type == TvType.Movie) {
            "$apiBase/movie/${data.id}?api_key=$apiKey&append_to_response=$append"
        } else {
            "$apiBase/tv/${data.id}?api_key=$apiKey&append_to_response=$append"
        }
        val res = app.get(resUrl).parsedSafe<MediaDetail>()
            ?: throw ErrorLoadingException("Invalid Json Response")

        val title = res.title ?: res.name ?: return null
        val poster = getOriImageUrl(res.posterPath)
        val bgPoster = getOriImageUrl(res.backdropPath)
        val orgTitle = res.originalTitle ?: res.originalName ?: return null
        val releaseDate = res.releaseDate ?: res.firstAirDate
        val year = releaseDate?.split("-")?.first()?.toIntOrNull()
        val score = Score.from10(res.vote_average?.toString())
        val genres = res.genres?.mapNotNull { it.name }

        val isCartoon = genres?.contains("Animation") ?: false
        val isAnime = isCartoon && (res.original_language == "zh" || res.original_language == "ja")
        if (!isAnime) return null
        val isAsian = !isAnime && (res.original_language == "zh" || res.original_language == "ko")
        val isBollywood = res.production_countries?.any { it.name == "India" } ?: false

        val keywords = res.keywords?.results?.mapNotNull { it.name }.orEmpty()
            .ifEmpty { res.keywords?.keywords?.mapNotNull { it.name } }

        val actors = res.credits?.cast?.mapNotNull { cast ->
            ActorData(
                Actor(
                    cast.name ?: cast.originalName
                    ?: return@mapNotNull null, getImageUrl(cast.profilePath)
                ), roleString = cast.character
            )
        } ?: return null
        val recommendations =
            res.recommendations?.results?.mapNotNull { media -> media.toSearchResponse() }

        val trailer = res.videos?.results?.map { "https://www.youtube.com/watch?v=${it.key}" }

        return if (type == TvType.TvSeries) {
            val lastSeason = res.last_episode_to_air?.season_number
            val episodes = res.seasons?.mapNotNull { season ->
                app.get("$apiBase/${data.type}/${data.id}/season/${season.seasonNumber}?api_key=$apiKey")
                    .parsedSafe<MediaDetailEpisodes>()?.episodes?.map { eps ->
                        newEpisode(
                            data = LinkData(
                                data.id,
                                res.external_ids?.imdb_id,
                                res.external_ids?.tvdb_id,
                                data.type,
                                eps.seasonNumber,
                                eps.episodeNumber,
                                title = title,
                                year = season.airDate?.split("-")?.first()?.toIntOrNull(),
                                orgTitle = orgTitle,
                                isAnime = isAnime,
                                airedYear = year,
                                lastSeason = lastSeason,
                                epsTitle = eps.name,
                                jpTitle = res.alternative_titles?.results?.find { it.iso_3166_1 == "JP" }?.title,
                                date = season.airDate,
                                airedDate = res.releaseDate
                                    ?: res.firstAirDate,
                                isAsian = isAsian,
                                isBollywood = isBollywood,
                                isCartoon = isCartoon
                            ).toJson()
                        ) {
                            this.name =
                                eps.name + if (isUpcoming(eps.airDate)) " • [UPCOMING]" else ""
                            this.season = eps.seasonNumber
                            this.episode = eps.episodeNumber
                            this.posterUrl = getImageUrl(eps.stillPath)
                            this.score = Score.from10(eps.voteAverage)
                            this.description = eps.overview
                        }.apply {
                            this.addDate(eps.airDate)
                        }
                    }
            }?.flatten() ?: listOf()
            newTvSeriesLoadResponse(
                title,
                url,
                if (isAnime) TvType.Anime else TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.year = year
                this.plot = res.overview
                this.tags = keywords.takeIf { !it.isNullOrEmpty() } ?: genres
                this.score = score
                this.showStatus = getStatus(res.status)
                this.recommendations = recommendations
                this.actors = actors
                this.contentRating = fetchContentRating(data.id, "US")
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.external_ids?.imdb_id)
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                if (isAnime) TvType.AnimeMovie else TvType.Movie,
                LinkData(
                    data.id,
                    res.external_ids?.imdb_id,
                    res.external_ids?.tvdb_id,
                    data.type,
                    title = title,
                    year = year,
                    orgTitle = orgTitle,
                    isAnime = isAnime,
                    jpTitle = res.alternative_titles?.results?.find { it.iso_3166_1 == "JP" }?.title,
                    airedDate = res.releaseDate
                        ?: res.firstAirDate,
                    isAsian = isAsian,
                    isBollywood = isBollywood
                ).toJson(),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.comingSoon = isUpcoming(releaseDate)
                this.year = year
                this.plot = res.overview
                this.duration = res.runtime
                this.tags = keywords.takeIf { !it.isNullOrEmpty() } ?: genres
                this.score = score
                this.recommendations = recommendations
                this.actors = actors
                this.contentRating = fetchContentRating(data.id, "US")
                addTrailer(trailer)
                addTMDbId(data.id.toString())
                addImdbId(res.external_ids?.imdb_id)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = parseJson<LinkData>(data)
        val isMovie = res.season == null
        val activeSources = sourceRegistry.filter { source ->
            when {
                isMovie && !source.movie -> false
                !isMovie && !source.tv -> false
                else -> true
            }
        }
        val semaphore = Semaphore(sourceConcurrency)

        supervisorScope {
            activeSources.map { source ->
                async {
                    semaphore.withPermit {
                        try {
                            withTimeout(source.timeoutMs) {
                                invokeSource(source, res, subtitleCallback, callback)
                            }
                        } catch (e: TimeoutCancellationException) {
                            logError(e)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }
                }
            }.awaitAll()
        }

        return true
    }

    private suspend fun invokeSource(
        source: SourceDescriptor,
        res: LinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        when (source.key) {
            "vixsrc" -> invokeVixsrc(res.id, res.season, res.episode, callback)
            "vidlink" -> invokeVidlink(res.id, res.season, res.episode, callback)
            "vidfast" -> invokeVidfast(res.id, res.season, res.episode, subtitleCallback, callback)
            "videasy" -> invokeVideasy(res.id, res.season, res.episode, callback)
            "vidzen" -> invokeVidzen(res.id, res.season, res.episode, callback)
            "cinezo" -> invokeCinezo(res.id, res.season, res.episode, callback)
            "xprime" -> invokeXprime(res.id, res.imdbId, res.title, res.year, res.season, res.episode, subtitleCallback, callback)
            "mapple" -> invokeMapple(res.id, res.season, res.episode, callback)
            "cinemaos" -> invokeCinemaos(res.id, res.season, res.episode, callback)
            "cinesrc" -> invokeCineSrc(res.id, res.season, res.episode, callback)
            "mafiaembed" -> invokeMafiaEmbed(res.id, res.season, res.episode, callback)
            "autoembed" -> invokeAutoEmbed(res.id, res.season, res.episode, subtitleCallback, callback)
            "2embed" -> invoke2Embed(res.id, res.season, res.episode, callback)
            "wave" -> invokeWave(res.id, res.season, res.episode, callback)
            "multiembed" -> invokeMultiEmbed(
                res.id,
                res.imdbId,
                res.season,
                res.episode,
                subtitleCallback,
                callback
            )
            "ninetv" -> invokeNinetv(res.id, res.season, res.episode, subtitleCallback, callback)
            "ridomovies" -> invokeRidomovies(res.id, res.imdbId, res.season, res.episode, subtitleCallback, callback)
            "soapy" -> invokeSoapy(res.id, res.season, res.episode, subtitleCallback, callback)
            "rivestream" -> invokeRiveStream(res.id, res.season, res.episode, callback)
            "smashystream" -> invokeSmashyStream(res.imdbId, res.season, res.episode, callback)
            "vembed" -> invokeVembed(res.id, res.imdbId, res.season, callback)
            "wyzie" -> invokeWyzie(res.id, res.season, res.episode, subtitleCallback)
            "watchsomuch" -> invokeWatchsomuch(res.imdbId, res.season, res.episode, subtitleCallback)
            "idlix" -> invokeIdlix(
                res.title,
                res.year,
                res.season,
                res.episode,
                subtitleCallback,
                callback
            )

            "azmovies" -> invokeAzmovies(
                res.titleCandidates(),
                res.year,
                subtitleCallback,
                callback
            )

            "noxx" -> invokeNoxx(
                res.titleCandidates(),
                res.season,
                res.episode,
                subtitleCallback,
                callback
            )
            "watch32" -> invokeWatch32(
                res.titleCandidates(),
                res.season,
                res.episode,
                subtitleCallback,
                callback
            )

            "uhdmovies" -> invokeUhdmovies(
                res.titleCandidates(),
                res.year,
                res.season,
                res.episode,
                callback
            )

            "multimovies" -> invokeMultimovies(
                res.titleCandidates(),
                res.year,
                res.season,
                res.episode,
                subtitleCallback,
                callback
            )

            "kisskh" -> invokeKisskh(
                res.titleCandidates(),
                res.year,
                res.season,
                res.episode,
                subtitleCallback,
                callback
            )

            "yflix" -> invokeYflix(
                res.id,
                res.imdbId,
                res.season,
                res.episode,
                subtitleCallback,
                callback
            )
        }
    }

    private fun LinkData.titleCandidates(): List<String> {
        return listOfNotNull(title, orgTitle)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    data class LinkData(
        val id: Int? = null,
        val imdbId: String? = null,
        val tvdbId: Int? = null,
        val type: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val aniId: String? = null,
        val animeId: String? = null,
        val title: String? = null,
        val year: Int? = null,
        val orgTitle: String? = null,
        val isAnime: Boolean = false,
        val airedYear: Int? = null,
        val lastSeason: Int? = null,
        val epsTitle: String? = null,
        val jpTitle: String? = null,
        val date: String? = null,
        val airedDate: String? = null,
        val isAsian: Boolean = false,
        val isBollywood: Boolean = false,
        val isCartoon: Boolean = false,
    )

    data class Data(
        val id: Int? = null,
        val type: String? = null,
        val aniId: String? = null,
        val malId: Int? = null,
    )

    data class Results(
        @param:JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class Media(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("original_title") val originalTitle: String? = null,
        @param:JsonProperty("original_language") val originalLanguage: String? = null,
        @param:JsonProperty("media_type") val mediaType: String? = null,
        @param:JsonProperty("poster_path") val posterPath: String? = null,
        @param:JsonProperty("vote_average") val voteAverage: Double? = null,
    )

    data class Genres(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
    )

    data class Keywords(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
    )

    data class KeywordResults(
        @param:JsonProperty("results") val results: ArrayList<Keywords>? = arrayListOf(),
        @param:JsonProperty("keywords") val keywords: ArrayList<Keywords>? = arrayListOf(),
    )

    data class Seasons(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("season_number") val seasonNumber: Int? = null,
        @param:JsonProperty("air_date") val airDate: String? = null,
    )

    data class Cast(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("original_name") val originalName: String? = null,
        @param:JsonProperty("character") val character: String? = null,
        @param:JsonProperty("known_for_department") val knownForDepartment: String? = null,
        @param:JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class Episodes(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("overview") val overview: String? = null,
        @param:JsonProperty("air_date") val airDate: String? = null,
        @param:JsonProperty("still_path") val stillPath: String? = null,
        @param:JsonProperty("vote_average") val voteAverage: Double? = null,
        @param:JsonProperty("episode_number") val episodeNumber: Int? = null,
        @param:JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class MediaDetailEpisodes(
        @param:JsonProperty("episodes") val episodes: ArrayList<Episodes>? = arrayListOf(),
    )

    data class Trailers(
        @param:JsonProperty("key") val key: String? = null,
    )

    data class ResultsTrailer(
        @param:JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf(),
    )

    data class AltTitles(
        @param:JsonProperty("iso_3166_1") val iso_3166_1: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("type") val type: String? = null,
    )

    data class ResultsAltTitles(
        @param:JsonProperty("results") val results: ArrayList<AltTitles>? = arrayListOf(),
    )

    data class ExternalIds(
        @param:JsonProperty("imdb_id") val imdb_id: String? = null,
        @param:JsonProperty("tvdb_id") val tvdb_id: Int? = null,
    )

    data class Credits(
        @param:JsonProperty("cast") val cast: ArrayList<Cast>? = arrayListOf(),
    )

    data class ResultsRecommendations(
        @param:JsonProperty("results") val results: ArrayList<Media>? = arrayListOf(),
    )

    data class LastEpisodeToAir(
        @param:JsonProperty("episode_number") val episode_number: Int? = null,
        @param:JsonProperty("season_number") val season_number: Int? = null,
    )

    data class ProductionCountries(
        @param:JsonProperty("name") val name: String? = null,
    )

    data class MediaDetail(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("imdb_id") val imdbId: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("original_title") val originalTitle: String? = null,
        @param:JsonProperty("original_name") val originalName: String? = null,
        @param:JsonProperty("poster_path") val posterPath: String? = null,
        @param:JsonProperty("backdrop_path") val backdropPath: String? = null,
        @param:JsonProperty("release_date") val releaseDate: String? = null,
        @param:JsonProperty("first_air_date") val firstAirDate: String? = null,
        @param:JsonProperty("overview") val overview: String? = null,
        @param:JsonProperty("runtime") val runtime: Int? = null,
        @param:JsonProperty("vote_average") val vote_average: Any? = null,
        @param:JsonProperty("original_language") val original_language: String? = null,
        @param:JsonProperty("status") val status: String? = null,
        @param:JsonProperty("genres") val genres: ArrayList<Genres>? = arrayListOf(),
        @param:JsonProperty("keywords") val keywords: KeywordResults? = null,
        @param:JsonProperty("last_episode_to_air") val last_episode_to_air: LastEpisodeToAir? = null,
        @param:JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
        @param:JsonProperty("videos") val videos: ResultsTrailer? = null,
        @param:JsonProperty("external_ids") val external_ids: ExternalIds? = null,
        @param:JsonProperty("credits") val credits: Credits? = null,
        @param:JsonProperty("recommendations") val recommendations: ResultsRecommendations? = null,
        @param:JsonProperty("alternative_titles") val alternative_titles: ResultsAltTitles? = null,
        @param:JsonProperty("production_countries") val production_countries: ArrayList<ProductionCountries>? = arrayListOf(),
    )

}
