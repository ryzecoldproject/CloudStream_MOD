package com.KlikXXI

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class Strp2p : ExtractorApi() {
    override val name            = "KlikXXI Vite SPA Unpacker"
    override val mainUrl         = "https://klikxxi.shop"
    override val requiresReferer = true

    private val TAG = "Strp2p_SPA_Core"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "══════════ PILOT EKSTRAKSI SPA STRP2P START ══════════")
        val baseDomain = Regex("""^(https?://[^/#?]+)""").find(url)?.groupValues?.get(1) ?: "https://klikxxi.strp2p.site"
        
        // 1. Ambil shell HTML awal dari URL Iframe
        val htmlContent = fetchPage(url)
        if (htmlContent.isBlank()) {
            Log.e(TAG, "[-] Halaman shell HTML kosong atau gagal diakses.")
            return
        }

        // 2. Ekstrak dan unduh isi kode dari bundle JavaScript utama Vite secara statis
        val jsContent = extractPlayerScript(htmlContent, baseDomain)
        
        // Gabungkan konteks pencarian: jika download JS gagal, gunakan HTML sebagai fallback
        val searchContext = jsContent.ifEmpty { htmlContent }

        // 3. Bongkar manifes HLS stream (.m3u8) dari teks kode
        val rawCandidates = extractM3u8Urls(searchContext)
        Log.d(TAG, "[+] Menemukan sebanyak ${rawCandidates.size} kandidat URL potensial.")

        // 4. Lakukan validasi skema dan buang duplikasi data
        val tervalidasi = rawCandidates.mapNotNull { candidate ->
            validateCandidate(candidate, url)
        }.distinct()

        // 5. Daftarkan tautan video manifest yang valid langsung ke basis sistem Cloudstream
        tervalidasi.forEach { streamUrl ->
            val authParams = extractAuthParameters(streamUrl)
            val qualityLabel = if (streamUrl.contains("hls4")) Qualities.P1080.value else Qualities.P720.value
            val currentServerName = if (streamUrl.contains("brightcrest")) "Strp2p Premium" else "Strp2p Standar"

            Log.d(TAG, "[+] Sukses mengunci aliran video hulu: $streamUrl")
            
            callback(newExtractorLink(
                source = this.name,
                name = currentServerName,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.quality = qualityLabel
                this.headers = buildHeaders(url)
            })
        }
        Log.d(TAG, "══════════ PILOT EKSTRAKSI SPA STRP2P END ══════════")
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  PIPELINE LOGIC IMPLEMENTATION
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun fetchPage(url: String): String {
        return try {
            Log.d(TAG, "[+] Membuka sambungan HTTP GET ke: $url")
            val response = app.get(url, headers = buildHeaders(url)).text
            response
        } catch (e: Exception) {
            Log.e(TAG, "[-] Aliran request gagal dieksekusi: ${e.message}")
            ""
        }
    }

    suspend fun extractPlayerScript(html: String, baseDomain: String): String {
        Log.d(TAG, "[+] Mendeteksi aset JavaScript utama dari bundler Vite...")
        
        // Regex untuk menangkap jalur file index-xxx.js dari struktur script type="module"
        val viteJsRegex = Regex("""src=["'](/assets/index-[A-Za-z0-9_-]+\.js)["']""")
        val match = viteJsRegex.find(html)
        
        if (match != null) {
            val relativeJsPath = match.groupValues[1]
            val absoluteJsUrl = "$baseDomain$relativeJsPath"
            Log.d(TAG, "[+] Aset JS Ditemukan: $absoluteJsUrl. Mengunduh kode mentah secara statis...")
            
            return try {
                app.get(absoluteJsUrl, headers = buildHeaders(baseDomain)).text
            } catch (e: Exception) {
                Log.e(TAG, "[-] Gagal mengunduh file berkas JS: ${e.message}")
                ""
            }
        }
        Log.w(TAG, "[-] Tidak dapat menemukan aset kompilasi berkas index.js milik Vite di DOM HTML.")
        return ""
    }

    fun extractM3u8Urls(script: String): List<String> {
        Log.d(TAG, "[+] Memindai pola teks manifest stream di dalam kode...")
        val candidates = mutableListOf<String>()
        
        // Regex menangkap string literal .m3u8 absolut maupun relatif
        val m3u8Pattern = Regex("""["']((?:https?://[^"']+|/[^"']+)\.m3u8(?:\?[^"']+)?)["']""")
        val matches = m3u8Pattern.findAll(script)
        
        for (match in matches) {
            candidates.add(match.groupValues[1])
        }
        
        // NOT VERIFIED: Jika JS menggunakan teknik penyusunan string terpisah (string concatenation), regex literal di atas akan menghasilkan list kosong.
        return candidates
    }

    fun extractAuthParameters(url: String): Map<String, String> {
        val paramMap = mutableMapOf<String, String>()
        val authPattern = Regex("""[?&](t|s|e)=([^&#"']+)""")
        val matches = authPattern.findAll(url)
        
        for (match in matches) {
            paramMap[match.groupValues[1]] = match.groupValues[2]
        }
        return paramMap
    }

    fun validateCandidate(url: String, parentUrl: String): String? {
        if (url.contains("googletagmanager") || url.contains("google-analytics")) {
            return null
        }

        var cleanUrl = url.replace("\\/", "/") 

        if (cleanUrl.startsWith("1d://")) {
            cleanUrl = cleanUrl.replace("1d://", "https://")
        }

        if (cleanUrl.startsWith("/")) {
            val baseDomain = Regex("""^(https?://[^/#?]+)""").find(parentUrl)?.groupValues?.get(1) ?: "https://klikxxi.strp2p.site"
            cleanUrl = "$baseDomain$cleanUrl"
        }

        return if (cleanUrl.contains(".m3u8")) cleanUrl else null
    }

    fun buildHeaders(currentUrl: String): Map<String, String> {
        val baseDomain = Regex("""^(https?://[^/#?]+)""").find(currentUrl)?.groupValues?.get(1) ?: "https://klikxxi.strp2p.site"
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
            "Referer" to "https://klikxxi.shop/",
            "Origin" to baseDomain,
            "Accept" to "*/*"
        )
    }
}
