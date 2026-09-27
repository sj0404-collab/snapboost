package com.sj0404.snapboost.core.system

import android.util.Log

/**
 * Точка выбора исполнителя команд + кэш «дорогих» дампов.
 *
 * Приоритет: Shizuku (UID shell) → приложение (свой UID). Выбор происходит
 * один раз на вызов, потому что Shizuku может быть запущен прямо во время
 * работы приложения, и закешированный "недоступен" не должен быть вечным.
 */
object Privileged {

    private val direct = DirectRunner()
    private val shizuku = ShizukuRunner()

    private const val TAG = "SnapBoost/Privileged"

    /** UID, под которым реально выполняются привилегированные команды. */
    val effectiveUid: Int
        get() = runner().run(listOf("id", "-u"), 2000).stdout.trim().toIntOrNull() ?: android.os.Process.myUid()

    val hasShell: Boolean get() = effectiveUid == 2000 || effectiveUid == 0

    val sourceName: String
        get() = when {
            shizuku.available -> "shell (Shizuku)"
            else -> "app"
        }

    fun runner(): CommandRunner = if (shizuku.available) shizuku else direct

    fun shizukuAvailable(): Boolean = shizuku.available

    // ---------------------------------------------------------------- dumpsys

    private data class Entry(val text: String, val at: Long, val ok: Boolean)

    private val cache = HashMap<String, Entry>()

    /**
     * `dumpsys` стоит десятки миллисекунд, поэтому держим короткий TTL.
     * Отказ по правам кэшируется заметно дольше успеха: если доступа нет,
     * повторные попытки всё равно ничего не дадут, а попытка дорогая.
     */
    fun dumpsys(service: String, ttlMs: Long = 2500L): String? {
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[service]?.let { e ->
                val limit = if (e.ok) ttlMs else 15_000L
                if (now - e.at < limit) return if (e.ok) e.text else null
            }
        }
        val res = runner().run(listOf("dumpsys", service), 6000)
        val ok = res.ok && res.stdout.isNotBlank() && !res.denied
        if (!ok) {
            Log.d(TAG, "dumpsys $service недоступен: exit=${res.exitCode} denied=${res.denied} notFound=${res.notFound}")
        }
        synchronized(cache) {
            cache[service] = Entry(res.stdout, now, ok)
        }
        return if (ok) res.stdout else null
    }

    fun dumpsys(service: String, args: List<String>, ttlMs: Long = 2500L): String? {
        val now = System.currentTimeMillis()
        val key = service + args.joinToString(" ", prefix = ":")
        synchronized(cache) {
            cache[key]?.let { e ->
                if (e.ok && now - e.at < ttlMs) return e.text
            }
        }
        val res = runner().run(listOf("dumpsys", service) + args, 6000)
        val ok = res.ok && res.stdout.isNotBlank() && !res.denied
        if (ok) synchronized(cache) { cache[key] = Entry(res.stdout, now, true) }
        return if (ok) res.stdout else null
    }

    // ------------------------------------------------------------------ getprop

    private val propCache = HashMap<String, Entry>()

    fun prop(key: String, ttlMs: Long = 10_000L): String? {
        val now = System.currentTimeMillis()
        synchronized(propCache) {
            propCache[key]?.let { if (now - it.at < ttlMs && it.ok) return it.text }
        }
        val res = runner().run(listOf("getprop", key), 2000)
        val value = res.stdout.trim().takeIf { it.isNotEmpty() && res.ok }
        synchronized(propCache) {
            propCache[key] = Entry(value ?: "", now, value != null)
        }
        return value
    }

    fun invalidateAll() = synchronized(cache) { cache.clear() }

    fun invalidate(service: String) = synchronized(cache) { cache.remove(service) }
}
