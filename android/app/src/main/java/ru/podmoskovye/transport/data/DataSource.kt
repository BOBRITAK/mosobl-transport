package ru.podmoskovye.transport.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.podmoskovye.transport.core.Model
import ru.podmoskovye.transport.core.Network

/** Загрузка проверенных данных сайта из assets и локальное хранилище. */
object DataSource {

    private fun readAsset(context: Context, name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    fun loadNetwork(context: Context): Network {
        val t0 = System.nanoTime()
        val base = JSONObject(readAsset(context, "lines.json"))
        val project = JSONObject(readAsset(context, "project_lines.json"))
        val cities = JSONObject(readAsset(context, "cities.json"))
        val network = Model.buildNetwork(base, project, cities)
        PerfLog.record("network_load_ms", (System.nanoTime() - t0) / 1_000_000)
        return network
    }

    fun loadScheme(context: Context): JSONObject = JSONObject(readAsset(context, "scheme_layout.json"))
}

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("transport_prefs", Context.MODE_PRIVATE)

    var favorites: List<String>
        get() = read("favorites")
        set(value) = write("favorites", value)

    var history: List<String>
        get() = read("history")
        set(value) = write("history", value)

    var layers: Set<String>
        get() {
            val raw = sp.getString("layers", null) ?: return setOf("metro", "mcc", "mcd", "project")
            return raw.split(",").filter { it.isNotBlank() }.toSet()
        }
        set(value) { sp.edit().putString("layers", value.joinToString(",")).apply() }

    private fun read(key: String): List<String> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(key: String, value: List<String>) {
        val arr = JSONArray()
        value.forEach { arr.put(it) }
        sp.edit().putString(key, arr.toString()).apply()
    }
}
