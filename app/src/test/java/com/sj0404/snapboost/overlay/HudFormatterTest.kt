package com.sj0404.snapboost.overlay

import com.sj0404.snapboost.core.AudioRoute
import com.sj0404.snapboost.core.AudioRouteInfo
import com.sj0404.snapboost.core.AudioState
import com.sj0404.snapboost.core.FpsState
import com.sj0404.snapboost.core.Settings
import com.sj0404.snapboost.core.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Форматтер HUD проверяется на главном требовании: недоступные метрики
 * печатаются как N/A, а не подменяются правдоподобными числами.
 */
class HudFormatterTest {

    private fun snapshot(
        fps: FpsState = FpsState(),
        audio: AudioState = AudioState()
    ) = Snapshot(fps = fps, audio = audio)

    @Test
    fun `без доступа к SurfaceFinger fps печатается как N A`() {
        val lines = HudFormatter.format(
            snapshot(fps = FpsState(valid = false)),
            Settings.OverlayMode.FULL,
            "Genshin Impact"
        )
        val fpsLine = lines.first { it.text.startsWith("FPS") }
        assertTrue("Ожидался N/A, получено: ${fpsLine.text}", fpsLine.text.contains("N/A"))
    }

    @Test
    fun `реальный fps печатается числом`() {
        val lines = HudFormatter.format(
            snapshot(fps = FpsState(fps = 57.4f, jankPct = 4, valid = true)),
            Settings.OverlayMode.MINIMAL,
            ""
        )
        val fpsLine = lines.first { it.text.startsWith("FPS") }
        assertTrue(fpsLine.text.contains("57"))
        assertTrue(fpsLine.text.contains("jank 4%"))
    }

    @Test
    fun `без счётчика underrun аудио не выдаётся за норму`() {
        val lines = HudFormatter.format(
            snapshot(audio = AudioState(underrunsTotal = null)),
            Settings.OverlayMode.MINIMAL,
            ""
        )
        val audioLine = lines.first { it.text.contains("аудио") }
        assertTrue(
            "Ожидалось сообщение о недоступности счётчика, получено: ${audioLine.text}",
            audioLine.text.contains("недоступен")
        )
    }

    @Test
    fun `underrun окрашивается критическим цветом`() {
        val lines = HudFormatter.format(
            snapshot(audio = AudioState(underrunsTotal = 99, underrunsPerSec = 5f)),
            Settings.OverlayMode.MINIMAL,
            ""
        )
        val audioLine = lines.first { it.text.contains("UNDERRUN") }
        assertEquals(HudFormatter.CRIT, audioLine.color)
    }

    @Test
    fun `bluetooth маршрут окрашивается предупреждением`() {
        val lines = HudFormatter.format(
            snapshot(
                audio = AudioState(
                    underrunsTotal = 0,
                    underrunsPerSec = 0f,
                    routeInfo = AudioRouteInfo(AudioRoute.BLUETOOTH_A2DP, "Bluetooth A2DP", null, true)
                )
            ),
            Settings.OverlayMode.MINIMAL,
            ""
        )
        val audioLine = lines.first { it.text.contains("аудио") }
        assertEquals(HudFormatter.WARN, audioLine.color)
    }

    @Test
    fun `режим выключен не рисует ни одной строки`() {
        val lines = HudFormatter.format(snapshot(), Settings.OverlayMode.OFF, "Игра")
        assertEquals(0, lines.size)
    }
}
