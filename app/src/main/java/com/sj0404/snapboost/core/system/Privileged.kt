package com.sj0404.snapboost.core.system

import android.util.Log

/**
 * Единственный исполнитель системных команд + кэш «дорогих» дампов.
 *
 * Приложение работает от своего UID и сознательно не требует ни root, ни
 * внешних сервисов с правами shell. Отсюда важное ограничение: `dumpsys`
 * требует android.permission.DUMP, поэтому всё, что берётся из него
 * (реальный FPS, jank, счётчик audio underrun, стек аудио-фокуса), обычному
 * приложению недоступно и остаётся N/A. Подменять эти поля правдоподобными
 * догадками нельзя — по ним принимают решения про настройку железа.
 *
 * Зато sysfs, procfs и `getprop` читаются полноценно, и на них построены
 * реальные метрики CPU, GPU, температуры и PSI.
 */
object Privileged {

    private val direct = DirectRunner()

    private const val TAG = "SnapBoost/Privileged"

    /** UID, под которым выполняются команды. Всегда собственный UID приложения. */
    val effectiveUid: Int
        get() = runner().run(listOf("id", "-u"), 2000).stdout.trim().toIntOrNull()
            ?: android.os.Process.myUid()

    fun runner(): CommandRunner = direct

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

    fun invalidateAll() {
        synchronized(cache) { cache.clear() }
    }

    fun invalidate(service: String) {
        synchronized(cache) { cache.remove(service) }
    }
}
