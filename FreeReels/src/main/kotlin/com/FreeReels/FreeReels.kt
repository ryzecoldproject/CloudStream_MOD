package com.FreeReels

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom

class FreeReels : MainAPI() {
    override var mainUrl = "https://m.mydramawave.com"
    private val nativeApiUrl = "https://apiv2.free-reels.com/frv2-api"
    
    override var name = "FreeReels"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Anime)

    private val secureRandom = SecureRandom()
    private val deviceId = (1..32).map { "0123456789abcdef"[secureRandom.nextInt(16)] }.joinToString("")
    private val sessionId = java.util.UUID.randomUUID().toString()
    
    private val authSalt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv&"
    private val nativeLoginSalt = "8IAcbWyCsVhYv82S2eofRqK1DF3nNDAv"
    
    private var sessionToken: String? = null
    private var sessionSecret: String? = null
    private val sessionLock = Mutex()

    // FORMAT: TabID_PositionIndex_RecommendModuleKey
    override val mainPage = mainPageOf(
        "993_10000_2381" to "Populer",
        "995_10000_2385" to "New",
        "1002_10000_2392" to "Dubbing",
        "994_10000_2384" to "Perempuan",
        "996_10000_2386" to "Laki-Laki",
        "1005_10001_2395" to "Anime"
    )

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // HEADER ASLI VERSI 2.2.40 (Super Ketat Anti-Blokir)
    private fun getNativeHeaders(isVip: Boolean = false): MutableMap<String, String> {
        val ts = System.currentTimeMillis()
        val signature = md5(authSalt + (sessionSecret ?: ""))
        
        val headers = mutableMapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "OpCountryCode" to "ID",
            "X-AppEngine-Country" to "ID",
            "app-language" to "id",
            "prefer_country" to "ID",
            "locale" to "id-ID",
            "language" to "id-ID",
            "country" to "ID",
            "X-Timezone" to "Asia/Jakarta",
            "timezone" to "7",
            "X-Timezone-offset" to "7",
            "network-type" to "WIFI",
            "screen-width" to "411",
            "screen-height" to "891",
            "is-mainland" to "false",
            "device-memory" to "8.00",
            "device-country" to "ID",
            "device-language" to "id-ID",
            "x-device-model" to "23090RA98G",
            "x-device-manufacturer" to "Xiaomi",
            "x-device-brand" to "Redmi",
            "x-device-product" to "sky",
            "x-device-fingerprint" to "Redmi/sky_global/sky:14/UKQ1.231003.002/V816.0.11.0.UMWMIXM:user/release-keys",
            "session-id" to sessionId,
            "app-name" to "com.freereels.app",
            "app-version" to "2.2.40",
            "device-id" to deviceId,
            "device-version" to "34",
            "device" to "android",
            "Authorization" to "oauth_signature=$signature,oauth_token=${sessionToken ?: "undefined"},ts=$ts"
        )
        
        // Jimat VIP hanya dipasang saat mengambil video
        if (isVip) {
            headers["internal-user-code"] = "666666" 
        }
        
        return headers
    }

    private suspend fun ensureSession() {
        if (sessionToken != null) return
        sessionLock.withLock {
            if (sessionToken != null) return@withLock 
            
            val loginSig = md5(nativeLoginSalt + deviceId)
            val reqBody = mapOf(
                "device_id" to deviceId,
                "device_name" to "Redmi 23090RA98G",
                "device_sign" to loginSig
            ).toJson().toRequestBody("application/json".toMediaTypeOrNull())
            
            val res = app.post("$nativeApiUrl/anonymous/login", headers = getNativeHeaders(), requestBody = reqBody).text
            val authData = tryParseJson<NativeAuthResponse>(res)
            
            sessionToken = authData?.data?.authKey ?: authData?.data?.token
            sessionSecret = authData?.data?.authSecret ?: ""
        }
    }

    // MESIN PENYEDOT JSON (Anti-Crash)
    private fun extractMovies(dataObj: Map<*, *>?): List<Map<*, *>> {
        if (dataObj == null) return emptyList()
        val movies = mutableListOf<Map<*, *>>()

        (dataObj["items"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.let { movies.addAll(it) }
        (dataObj["list"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.let { movies.addAll(it) }

        listOf("components", "modules").forEach { key ->
            (dataObj[key] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { comp ->
                (comp["items"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.let { movies.addAll(it) }
                (comp["list"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.let { movies.addAll(it) }
            }
        }

        val uniqueMovies = mutableMapOf<String, Map<*, *>>()
        movies.forEach { m ->
            val title = (m["title"] as? String) ?: (m["name"] as? String)
            val mId = m["id"]?.toString() ?: m["key"]?.toString() ?: m["series_id"]?.toString()
            if (!title.isNullOrBlank() && !mId.isNullOrBlank() && !title.equals("Ranking", true) && !title.equals("Peringkat", true) && !title.equals("Top", true)) {
                uniqueMovies[mId] = m
            }
        }
        return uniqueMovies.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureSession()
        
        val keys = request.data.split("_")
        val tabId = keys[0]
        val posIndex = keys.getOrNull(1) ?: "10000"
        val recommendKey = keys.getOrNull(2) ?: tabId
        
        var hasMore = false
        val rawItems = if (page == 1) {
            // PAGE 1: Rute Beranda Normal
            val url = "$nativeApiUrl/homepage/v2/tab/index?tab_key=$tabId&position_index=$posIndex&first="
            val res = app.get(url, headers = getNativeHeaders(isVip = false)).text
            
            val parsedData = tryParseJson<Map<String, Any>>(res)
            val dataObj = parsedData?.get("data") as? Map<*, *>
            val pageInfo = dataObj?.get("page_info") as? Map<*, *>
            
            // Cek apakah ada halaman selanjutnya
            hasMore = pageInfo?.get("has_more") as? Boolean ?: (pageInfo?.get("next") != null)
            
            extractMovies(dataObj)
        } else {
            // PAGE 2+: Oper Gigi Infinite Scroll
            val offset = (page - 1) * 10
            val reqBody = mapOf(
                "module_key" to recommendKey,
                "next" to "offset=$offset&position_index=$posIndex"
            ).toJson().toRequestBody("application/json".toMediaTypeOrNull())
            
            val url = "$nativeApiUrl/homepage/v2/tab/feed"
            val res = app.post(url, headers = getNativeHeaders(isVip = false), requestBody = reqBody).text
            
            val parsedData = tryParseJson<Map<String, Any>>(res)
            val dataObj = parsedData?.get("data") as? Map<*, *>
            val pageInfo = dataObj?.get("page_info") as? Map<*, *>
            
            hasMore = pageInfo?.get("has_more") as? Boolean ?: (pageInfo?.get("next") != null)
            
            extractMovies(dataObj)
        }

        val items = rawItems.mapNotNull { item -> 
            val title = (item["title"] as? String) ?: (item["name"] as? String) ?: return@mapNotNull null
            val idStr = item["id"]?.toString() ?: item["key"]?.toString() ?: item["series_id"]?.toString() ?: return@mapNotNull null
            val cover = (item["cover"] as? String) ?: (item["vertical_cover"] as? String)
            
            val epInfo = item["episode_info"] as? Map<*, *>
            val audioList = epInfo?.get("audio") as? List<*>
            val hasIndoAudio = audioList?.any { it?.toString()?.contains("id-ID") == true } == true
            val isDubbed = hasIndoAudio || title.contains("Dubbed", true) || title.contains("Sulih Suara", true)

            newAnimeSearchResponse(title, idStr, TvType.AsianDrama) { 
                this.posterUrl = fixUrlNull(cover)
            }.apply { 
                if (isDubbed) addDubStatus(DubStatus.Dubbed) 
            }
        }

        return newHomePageResponse(request.name, items, hasNext = hasMore)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        ensureSession()
        val nextToken = if (page == 1) "" else "offset=${(page - 1) * 20}&page_size=20"
        val reqBody = mapOf("keyword" to query, "next" to nextToken).toJson().toRequestBody("application/json".toMediaTypeOrNull())
        val res = app.post("$nativeApiUrl/search/drama", headers = getNativeHeaders(isVip = false), requestBody = reqBody).text
        
        val parsedData = tryParseJson<Map<String, Any>>(res)
        val dataObj = parsedData?.get("data") as? Map<*, *>
        val hasMore = (dataObj?.get("page_info") as? Map<*, *>)?.get("has_more") as? Boolean ?: false
        
        val rawItems = extractMovies(dataObj)
        
        val list = rawItems.mapNotNull { item ->
            val title = (item["title"] as? String) ?: (item["name"] as? String) ?: return@mapNotNull null
            val idStr = item["id"]?.toString() ?: item["key"]?.toString() ?: item["series_id"]?.toString() ?: return@mapNotNull null
            val cover = (item["cover"] as? String) ?: (item["vertical_cover"] as? String)
            
            val epInfo = item["episode_info"] as? Map<*, *>
            val audioList = epInfo?.get("audio") as? List<*>
            val hasIndoAudio = audioList?.any { it?.toString()?.contains("id-ID") == true } == true
            val isDubbed = hasIndoAudio || title.contains("Dubbed", true) || title.contains("Sulih Suara", true)
            
            newAnimeSearchResponse(title, idStr, TvType.AsianDrama) { 
                this.posterUrl = fixUrlNull(cover)
            }.apply { 
                if (isDubbed) addDubStatus(DubStatus.Dubbed) 
            }
        }
        
        return newSearchResponseList(list, hasNext = hasMore)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        ensureSession()
        
        val seriesId = url.split("/").last()
        
        // MENGELUARKAN JIMAT VIP SAAT LOAD EPISODE AGAR BISA NONTON GRATIS!
        var res = app.get("$nativeApiUrl/drama/info_v2?series_id=$seriesId", headers = getNativeHeaders(isVip = true)).text
        var info = tryParseJson<NativeDetailResponse>(res)?.data?.info
        
        if (info == null || info.episodeList.isNullOrEmpty()) {
            res = app.get("$nativeApiUrl/drama/info?series_id=$seriesId", headers = getNativeHeaders(isVip = true)).text
            info = tryParseJson<NativeDetailResponse>(res)?.data?.info ?: throw ErrorLoadingException("Film tidak ditemukan / Belum rilis")
        }

        val episodeList = info.episodeList?.mapNotNull { ep -> 
            newEpisode(ep.toJson()) {
                this.name = ep.name ?: "Episode ${ep.index}"
                this.episode = ep.index
            } 
        } ?: emptyList()

        return newTvSeriesLoadResponse(info.name ?: "Drama", url, TvType.AsianDrama, episodeList) {
            this.posterUrl = fixUrlNull(info.cover ?: info.verticalCover)
            this.plot = info.desc
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val ep = tryParseJson<NativeEpisode>(data) ?: return false
        
        val videoUrl = ep.externalAudioH264 ?: ep.externalAudioH265 ?: ep.m3u8Url ?: ep.videoUrl
        
        if (!videoUrl.isNullOrBlank()) {
            val isM3u8 = videoUrl.contains(".m3u8")
            callback.invoke(newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.headers = mapOf("Origin" to "https://m.mydramawave.com", "Referer" to "https://m.mydramawave.com/")
            })
        }

        ep.subtitleList?.forEach { sub ->
            val subUrl = sub.vtt ?: sub.subtitle
            if (!subUrl.isNullOrBlank()) {
                subtitleCallback.invoke(
                    newSubtitleFile(sub.language ?: "id", fixUrl(subUrl))
                )
            }
        }
        return true
    }
}

// ==========================================
// DATA MODELS PURE NATIVE 
// (Hanya untuk Auth, Detail, dan Video)
// ==========================================
data class NativeAuthResponse(@JsonProperty("data") val data: AuthData?)
data class AuthData(@JsonProperty("auth_key") val authKey: String?, @JsonProperty("auth_secret") val authSecret: String?, @JsonProperty("token") val token: String?)

data class NativeDetailResponse(@JsonProperty("data") val data: DramaInfoData?)
data class DramaInfoData(@JsonProperty("info") val info: DramaInfo?)
data class DramaInfo(
    @JsonProperty("name") val name: String?, 
    @JsonProperty("cover") val cover: String?, 
    @JsonProperty("vertical_cover") val verticalCover: String?,
    @JsonProperty("desc") val desc: String?, 
    @JsonProperty("episode_list") val episodeList: List<NativeEpisode>?
)

data class NativeEpisode(
    @JsonProperty("index") val index: Int?, 
    @JsonProperty("name") val name: String?,
    @JsonProperty("external_audio_h264_m3u8") val externalAudioH264: String?,
    @JsonProperty("external_audio_h265_m3u8") val externalAudioH265: String?,
    @JsonProperty("m3u8_url") val m3u8Url: String?, 
    @JsonProperty("video_url") val videoUrl: String?,
    @JsonProperty("subtitle_list") val subtitleList: List<NativeSubtitle>?
)

data class NativeSubtitle(
    @JsonProperty("language") val language: String?, 
    @JsonProperty("subtitle") val subtitle: String?, 
    @JsonProperty("vtt") val vtt: String?, 
    @JsonProperty("display_name") val displayName: String?
)
