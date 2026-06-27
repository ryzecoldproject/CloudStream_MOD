package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dipakai di AdiFilmSemi.load() untuk cek tanggal rilis film yang akan datang.
 * Override dari CloudStream (yang @Prerelease) supaya bisa jalan di build stable.
 */
fun isUpcoming(dateString: String?): Boolean {
    return try {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateTime = dateString?.let { format.parse(it)?.time } ?: return false
        unixTimeMS < dateTime
    } catch (t: Throwable) {
        logError(t)
        false
    }
}

/**
 * Helper fixUrl(String, String) - signature berbeda dari MainAPI.fixUrl(url: String).
 * Dipakai untuk menggabungkan path relatif ke domain (cadangan, tidak dipakai oleh
 * 4 sumber aktif Adicinemax21 tapi dipertahankan untuk konsistensi).
 */
fun fixUrl(url: String, domain: String): String {
    if (url.startsWith("http")) {
        return url
    }
    if (url.isEmpty()) {
        return ""
    }

    val startsWithNoHttp = url.startsWith("//")
    if (startsWithNoHttp) {
        return "https:$url"
    } else {
        if (url.startsWith('/')) {
            return domain + url
        }
        return "$domain/$url"
    }
}

// Catatan: getQualityFromName, base64Decode, base64Encode, base64DecodeArray
// tidak didefinisikan ulang di sini agar tidak konflik dengan wildcard import
// `com.lagradost.cloudstream3.*` di Extractor. Implementasi CloudStream sudah
// identik untuk semua kebutuhan Adimoviebox2Helper.
