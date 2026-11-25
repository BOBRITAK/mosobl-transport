package ru.podmoskovye.transport.data

import android.util.Log

/**
 * Замеры производительности собственного кода.
 *
 * Пишем в logcat только в отладочной сборке: это нужно, чтобы проверять
 * реальные числа на устройстве, а не заявлять о скорости «на глаз».
 */
object PerfLog {

    private const val TAG = "TransportPerf"
    private const val ENABLED = true

    private val marks = LinkedHashMap<String, Long>()

    fun record(name: String, ms: Long) {
        if (!ENABLED) return
        synchronized(marks) {
            marks[name] = ms
            Log.i(TAG, "$name=$ms ms")
        }
    }

    fun start(name: String) {
        if (!ENABLED) return
        synchronized(marks) { marks["$name.start"] = System.nanoTime() }
    }

    fun end(name: String) {
        if (!ENABLED) return
        val start = synchronized(marks) { marks["$name.start"] } ?: return
        record(name, (System.nanoTime() - start) / 1_000_000)
    }

    /** Сводка: удобно прочитать одной строкой из logcat. */
    fun dump(): String = synchronized(marks) {
        marks.entries.filter { !it.key.endsWith(".start") }
            .joinToString("; ") { "${it.key}=${it.value}ms" }
    }
}
