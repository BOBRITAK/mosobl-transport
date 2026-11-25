package ru.podmoskovye.transport.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Кэш тайлов подложки.
 *
 * Тайлы больше не являются Compose-компонентами: раньше на каждом кадре жеста
 * пересоздавались десятки AsyncImage, и это давало рывки. Теперь растровые
 * картинки лежат в LruCache и рисуются обычным drawImage внутри Canvas.
 */
class TileCache(
    private val scope: CoroutineScope,
    maxTiles: Int = 90,
) {

    private val cache = object : LruCache<String, ImageBitmap>(maxTiles) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = 1
    }

    private val inFlight = HashSet<String>()
    private val pending = ArrayDeque<String>()

    /** Счётчик изменений: читается в draw-фазе, поэтому обновление вызывает только перерисовку. */
    val version = mutableIntStateOf(0)

    private val worker = AtomicInteger(0)

    fun get(key: String): ImageBitmap? = cache.get(key)

    fun isPending(key: String): Boolean = key in inFlight

    /** Ставит тайлы в очередь; одновременно грузится не больше трёх. */
    fun request(keys: List<String>) {
        var queued = false
        for (key in keys) {
            if (cache.get(key) != null || inFlight.contains(key)) continue
            if (pending.size > 64) break
            pending.addLast(key)
            queued = true
        }
        if (!queued) return
        while (worker.get() < 3 && pending.isNotEmpty()) {
            val key = pending.removeFirst()
            inFlight.add(key)
            worker.incrementAndGet()
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { download(key) }
                if (bitmap != null) cache.put(key, bitmap)
                inFlight.remove(key)
                worker.decrementAndGet()
                version.intValue = version.intValue + 1
            }
        }
    }

    private fun download(key: String): ImageBitmap? {
        val parts = key.split('/')
        if (parts.size != 3) return null
        val z = parts[0].toIntOrNull() ?: return null
        val x = parts[1].toIntOrNull() ?: return null
        val y = parts[2].toIntOrNull() ?: return null
        val url = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/$z/$y/$x"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "TransportPodmoskovye/1.1 (Android)")
            conn.inputStream.use { stream ->
                val bmp: Bitmap = BitmapFactory.decodeStream(stream)
                bmp?.asImageBitmap()
            }
        } catch (e: Exception) {
            null
        }
    }
}
