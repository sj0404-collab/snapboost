package com.sj0404.snapboost.core.perf

import android.view.Choreographer
import com.sj0404.snapboost.core.FpsState
import com.sj0404.snapboost.core.system.Privileged
import kotlin.math.abs

/**
 * Настоящий FPS поверх произвольной игры.
 *
 * Важное ограничение, которое не обходится: `SurfaceFlinger --latency`
 * требует android.permission.DUMP, поэтому реальный FPS доступен только при
 * наличии shell-привилегий (Shizuku). Без них здесь честно возвращается
 * valid=false, и HUD показывает N/A, а не частоту дисплея.
 *
 * Частоту дисплея измеряем отдельно через Choreographer — она доступна всегда
 * и нужна как база: 120 Гц-дисплее с 45 FPS это принципиально другая
 * картина, чем 60 Гц с 45 FPS.
 */
class FpsTracker {

    private var layerName: String? = null
    private var displayHz: Int? = null
    private var lastGfxResetAt = 0L

    // Choreographer привязан к Looper потока, в котором он создан, поэтому
    // он берётся на том же (главном) потоке, что и сервис.
    private val choreographer: Choreographer? by lazy { runCatching { Choreographer.getInstance() }.getOrNull() }

    @Volatile
    private var hzCount = 0

    @Volatile
    private var hzStop = false

    private val hzCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            hzCount++
            if (hzStop) return
            choreographer?.postFrameCallback(this)
        }
    }

    // Инициализация обязательно после объявления hzCallback: Kotlin выполняет
    // инициализаторы в порядке объявления.
    init {
        choreographer?.postFrameCallback(hzCallback)
    }

    /** Реальная частота обновления панели, а не FPS игры. */
    fun measureDisplayHz(): Int? {
        hzStop = false
        hzCount = 0
        Thread.sleep(1000)
        val count = hzCount
        hzStop = true
        if (count <= 0) return null
        // Choreographer может пропустить кадры, поэтому округляем к типовым значениям.
        val hz = when {
            count >= 100 -> 120
            count >= 55 -> 60
            else -> count
        }
        displayHz = hz
        return hz
    }

    fun stop() {
        hzStop = true
        choreographer?.removeFrameCallback(hzCallback)
    }

    /**
     * Снимает окно кадров SurfaceFlinger.
     *
     * Порядок обязателен: сначала сброс, потом пауза на окно, потом чтение.
     * Иначе в выдачу попадут кадры предыдущего окна и FPS будет вычислен
     * по чужому интервалу.
     */
    fun sampleFrameTimes(windowMs: Long = 1000L): List<Long> {
        val target = resolveLayer() ?: return emptyList()

        Privileged.invalidate("SurfaceFlinger")
        runCatching {
            Privileged.runner().run(listOf("dumpsys", "SurfaceFlinger", "--latency-clear"), 2500)
        }
        Thread.sleep(windowMs)
        // Пустое имя слоя означает "слой по умолчанию" — в этом случае лишний
        // аргумент передавать нельзя, иначе SurfaceFlinger его не примет.
        val args = if (target.isEmpty()) listOf("--latency") else listOf("--latency", target)
        val dump = Privileged.dumpsys("SurfaceFlinger", args, ttlMs = 0L) ?: return emptyList()
        return parseLatency(dump)
    }

    /**
     * Выбирает слой для измерения. Слой запоминается, чтобы не перебирать
     * список на каждом окне.
     */
    private fun resolveLayer(): String? {
        layerName?.let { return it }

        // Сначала пробуем ключ слоя по имени игры, если оно известно.
        val dump = Privileged.dumpsys("SurfaceFlinger", listOf("--latency"), ttlMs = 200L)
        if (dump != null && parseLatency(dump).isNotEmpty()) {
            layerName = ""
            return ""
        }

        val list = Privileged.dumpsys("SurfaceFlinger", listOf("--list"), ttlMs = 4000L) ?: return null
        val candidates = list.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("SurfaceView[") }
            .filter { it.contains("SurfaceView") || it.contains("BLAST") || it.contains("ActivityRecord") }
            .take(4)
            .toList()
        for (c in candidates) {
            val d = Privileged.dumpsys("SurfaceFlinger", listOf("--latency", c), ttlMs = 0L)
            if (d != null && parseLatency(d).isNotEmpty()) {
                layerName = c
                return c
            }
        }
        return null
    }

    /**
     * Парсит вывод `dumpsys SurfaceFlinger --latency`.
     *
     * Формат: строка с периодом обновления, затем по строке на кадр:
     *   <номер кадра> <момент вывода> <длительность>
     * Нулевые моменты — кадры без данных, их отбрасываем.
     */
    private fun parseLatency(dump: String): List<Long> {
        val out = ArrayList<Long>(256)
        var seenHeader = false
        for (line in dump.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("Refresh period")) {
                seenHeader = true
                continue
            }
            val parts = t.split(Regex("\\s+"))
            if (parts.size < 2) continue
            val ts = parts[1].toLongOrNull() ?: continue
            if (ts <= 0L) continue
            out += ts
        }
        return if (seenHeader || out.isNotEmpty()) out.sorted() else emptyList()
    }

    fun sample(gamePackage: String?, windowMs: Long = 1000L): FpsState {
        val hz = displayHz
        val frames = sampleFrameTimes(windowMs)
        if (frames.size < 3) {
            return FpsState(displayHz = hz, valid = false)
        }

        val deltas = ArrayList<Long>(frames.size)
        for (i in 1 until frames.size) {
            val d = frames[i] - frames[i - 1]
            if (d in 1..1_000_000_000L) deltas += d
        }
        if (deltas.isEmpty()) return FpsState(displayHz = hz, valid = false)

        val span = frames.last() - frames.first()
        val fps = if (span > 0) 1_000_000_000.0 * (frames.size - 1) / span else null

        val sorted = deltas.sorted()
        val p50 = sorted[sorted.size / 2] / 1_000_000f
        val p99 = sorted[((sorted.size - 1) * 0.99).toInt().coerceIn(0, sorted.size - 1)] / 1_000_000f

        // Кадр длиннее 1.5 периода — признак двойного интервала, то есть дропа.
        val periodNs = if (hz != null && hz > 0) 1_000_000_000.0 / hz else 16_666_666.0
        val dropped = deltas.count { abs(it - periodNs) > periodNs * 0.5 }
        val jank = (dropped * 100.0 / deltas.size).toInt()

        return FpsState(
            fps = fps?.toFloat()?.takeIf { it.isFinite() && it in 1f..1000f },
            frameTimeP50 = p50,
            frameTimeP99 = p99,
            jankPct = jank,
            displayHz = hz,
            valid = true
        )
    }

    /**
     * Джиттер из `dumpsys gfxinfo`: окно сбрасывается и пересчитывается,
     * поэтому процент jank относится к измеренному интервалу, а не ко всей
     * сессии игры с момента запуска.
     */
    fun sampleJankFromGfx(gamePackage: String?, windowMs: Long): Pair<Int, Float?>? {
        val pkg = gamePackage ?: return null
        val now = System.currentTimeMillis()
        if (now - lastGfxResetAt < windowMs) return null
        lastGfxResetAt = now

        val reset = Privileged.runner().run(listOf("dumpsys", "gfxinfo", pkg, "reset"), 3000)
        if (!reset.ok) return null
        Thread.sleep(windowMs)
        val dump = Privileged.dumpsys("gfxinfo", listOf(pkg), ttlMs = 0L) ?: return null

        val total = Regex("Total frames rendered:\\s*(\\d+)").find(dump)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val janky = Regex("Janky frames:\\s*(\\d+)\\s*\\(([0-9.]+)%\\)")
            ?.find(dump)
            ?.groupValues?.get(2)?.toFloatOrNull()
        if (total < 3) return null
        val fps = total * 1000f / windowMs
        return (janky?.toInt() ?: -1) to fps
    }

    fun reset() {
        layerName = null
        lastGfxResetAt = 0L
    }
}
