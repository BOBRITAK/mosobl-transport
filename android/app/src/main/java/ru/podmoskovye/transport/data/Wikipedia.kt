package ru.podmoskovye.transport.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

/**
 * Фото и краткое описание станции из Википедии.
 * Данные не выдумываются: если статьи или фото нет — возвращается null,
 * и интерфейс показывает оформленную заглушку.
 */
object Wikipedia {

    data class Info(val photoUrl: String?, val extract: String?, val pageUrl: String?)

    private val cache = HashMap<String, Info?>()

    suspend fun stationInfo(stationName: String): Info? = withContext(Dispatchers.IO) {
        cache[stationName]?.let { return@withContext it }
        val result = runCatching {
            val query = URLEncoder.encode("$stationName станция метро", "UTF-8")
            val url = "https://ru.wikipedia.org/w/api.php?action=query&format=json&prop=pageimages|extracts" +
                "&piprop=thumbnail&pithumbsize=640&exintro=1&explaintext=1&exsentences=2" +
                "&generator=search&gsrsearch=$query&gsrlimit=1"
            val text = URL(url).openConnection().apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "TransportPodmoskovye/1.0 (Android)")
            }.getInputStream().bufferedReader().use { it.readText() }

            val pages = JSONObject(text).optJSONObject("query")?.optJSONObject("pages")
            if (pages == null || pages.length() == 0) return@runCatching null
            val firstKey = pages.keys().next()
            val page = pages.optJSONObject(firstKey) ?: return@runCatching null
            Info(
                photoUrl = page.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.startsWith("http") },
                extract = page.optString("extract").takeIf { it.isNotBlank() },
                pageUrl = page.optString("fullurl").takeIf { it.startsWith("http") }
                    ?: ("https://ru.wikipedia.org/?curid=" + page.optString("pageid")),
            )
        }.getOrNull()

        cache[stationName] = result
        result
    }
}
