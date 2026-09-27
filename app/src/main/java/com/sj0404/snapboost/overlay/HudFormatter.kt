package com.sj0404.snapboost.overlay

import com.sj0404.snapboost.core.AudioRoute
import com.sj0404.snapboost.core.Snapshot
import com.sj0404.snapboost.core.Settings

/**
 * Превращает снимок метрик в строки HUD.
 *
 * Считается в фоновом потоке сэмплера: главный поток получает готовые строки
 * и только рисует. Это заметно дешевле, чем собирать строки в onDraw, и заодно
 * гарантирует, что оверлей не занимает кадровый бюджет игры на форматирование.
 *
 * Каждая строка печатает либо реальное значение, либо N/A. Значения по
 * умолчанию и заглушки не выводятся нигде.
 */
object HudFormatter {

    const val OK = 0xFF7BE495.toInt()
    const val WARN = 0xFFF2C14E.toInt()
    const val CRIT = 0xFFF16A5A.toInt()
    const val DIM = 0xFF8FA3B0.toInt()
    const val KEY = 0xFFE8EEF2.toInt()

    data class HudLine(val text: String, val color: Int)

    fun format(s: Snapshot, mode: Settings.OverlayMode, gameName: String): List<HudLine> {
        val lines = ArrayList<HudLine>(6)
        when (mode) {
            Settings.OverlayMode.OFF -> return lines
            Settings.OverlayMode.MINIMAL -> {
                lines += fpsLine(s)
                lines += audioLine(s)
                return lines
            }
            Settings.OverlayMode.FULL -> {
                if (gameName.isNotEmpty() && gameName != "?") {
                    lines += HudLine(gameName, DIM)
                }
                lines += fpsLine(s)
                lines += audioLine(s)
                lines += cpuLine(s)
                lines += gpuLine(s)
                lines += thermalLine(s)
            }
        }
        return lines
    }

    private fun fpsLine(s: Snapshot): HudLine {
        val f = s.fps
        if (!f.valid || f.fps == null) {
            return HudLine("FPS: N/A - нет доступа к SurfaceFlinger", DIM)
        }
        val jank = f.jankPct
        val color = when {
            (jank ?: 0) >= 15 -> CRIT
            (jank ?: 0) >= 6 -> WARN
            else -> OK
        }
        val jankTxt = if (jank == null) "" else "  jank $jank%"
        val p99 = f.frameTimeP99?.let { "  p99 ${"%.1f".format(it)}мс" } ?: ""
        return HudLine("FPS ${"%.0f".format(f.fps)}$jankTxt$p99", color)
    }

    /**
     * Строка аудио — самая важная. Показывает и маршрут, и потери, и задержку,
     * потому что по симптому «рвётся» это первое, что нужно отличить.
     */
    private fun audioLine(s: Snapshot): HudLine {
        val a = s.audio
        val rate = a.underrunsPerSec
        val parts = ArrayList<String>(4)

        if (rate != null && rate >= 0.5f) {
            parts += "UNDERRUN ${"%.1f".format(rate)}/с"
        } else if (a.underrunsTotal != null) {
            parts += "аудио OK"
        } else {
            parts += "аудио: счётчик недоступен"
        }

        a.sampleRate?.let { parts += "${it / 1000}k" }
        a.outputLatencyMs?.let { parts += "задержка ${it}мс" }
        a.framesPerBuffer?.let { parts += "буфер $it" }

        val color = when {
            rate != null && rate >= 2f -> CRIT
            rate != null && rate >= 0.5f -> WARN
            a.route == AudioRoute.BLUETOOTH_A2DP || a.route == AudioRoute.BLUETOOTH_LE -> WARN
            a.sampleRateMismatch == true -> WARN
            a.focusHolders.isNotEmpty() -> WARN
            else -> OK
        }
        return HudLine(parts.joinToString("  "), color)
    }

    private fun cpuLine(s: Snapshot): HudLine {
        val c = s.cpu
        if (c.curKhz == null) return HudLine("CPU: cpufreq недоступен", DIM)
        val cur = c.curMhz ?: 0
        val max = c.hwMaxKhz?.div(1000)
        val head = if (max != null) "CPU $cur/$max МГц" else "CPU $cur МГц"
        val tail = when (c.throttled) {
            true -> "  ТРОТТЛИНГ"
            false -> ""
            null -> ""
        }
        val psi = s.pressure.cpuSomeAvg10
        val psiTxt = psi?.let { "  простои ${"%.0f".format(it)}%" } ?: ""
        return HudLine(
            head + tail + psiTxt,
            if (c.throttled == true) CRIT else if ((psi ?: 0f) >= 15f) WARN else OK
        )
    }

    private fun gpuLine(s: Snapshot): HudLine {
        val g = s.gpu
        if (g.vendorPath == null) return HudLine("GPU: KGSL нет (не Snapdragon)", DIM)
        val busy = g.busyPct?.let { "$it%" } ?: "н/д"
        val clk = g.clockMhz?.let { "$it" } ?: "?"
        val max = g.maxClockMhz?.let { "/$it" } ?: ""
        val tail = if (g.throttled == true) "  ТРОТТЛИНГ" else ""
        return HudLine(
            "GPU $busy $clk${max} МГц$tail",
            if (g.throttled == true) CRIT else OK
        )
    }

    private fun thermalLine(s: Snapshot): HudLine {
        val t = s.thermal
        val hot = t.hottest
        val zone = t.zones.firstOrNull()?.type?.take(6)?.uppercase()
        if (hot == null && t.status == null) return HudLine("Температура: N/A", DIM)
        val temp = hot?.let { "${"%.1f".format(it)}°C" } ?: "?"
        val status = t.statusName?.let { " $it" } ?: ""
        val color = when {
            t.status != null && t.status >= 3 -> CRIT
            (hot ?: 0f) >= 45f -> WARN
            (t.headroom ?: 0f) >= 0.8f -> WARN
            else -> OK
        }
        return HudLine("Тепло $temp$status${zone?.let { " [$it]" } ?: ""}", color)
    }
}
