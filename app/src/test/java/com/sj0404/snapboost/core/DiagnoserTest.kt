package com.sj0404.snapboost.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты фиксируют главное свойство приложения: отсутствие данных не
 * превращается в выдуманные значения. Каждый тест проверяет, что при пустых
 * метриках не появляется ни одного ложного заключения.
 */
class DiagnoserTest {

    @Test
    fun `пустой снимок не даёт ложных тревог`() {
        val findings = Diagnoser.analyze(Snapshot())

        // На пустом снимке допустимы только информационные сообщения
        // об отсутствии данных, ни одной настоящей проблемы.
        val real = findings.filter { it.severity != Diagnoser.Severity.INFO }
        assertTrue("Пустые данные не должны давать проблем: $real", real.isEmpty())
    }

    @Test
    fun `подтверждённый underrun даёт критическое заключение`() {
        val s = Snapshot(
            audio = AudioState(
                underrunsTotal = 40,
                underrunsPerSec = 3.2f,
                routeInfo = AudioRouteInfo(AudioRoute.SPEAKER, "Динамик", null, true)
            )
        )
        val findings = Diagnoser.analyze(s)
        val crit = findings.first { it.severity == Diagnoser.Severity.CRITICAL }
        assertTrue(crit.title.contains("underrun", ignoreCase = true))
    }

    @Test
    fun `bluetooth маршрут поднимает предупреждение`() {
        val s = Snapshot(
            audio = AudioState(
                routeInfo = AudioRouteInfo(AudioRoute.BLUETOOTH_A2DP, "Bluetooth A2DP", null, true)
            )
        )
        val findings = Diagnoser.analyze(s)
        assertTrue(findings.any { it.title.contains("Bluetooth") })
    }

    @Test
    fun `троттлинг cpu даёт критическое заключение`() {
        val s = Snapshot(
            cpu = CpuState(curKhz = 1_200_000, hwMaxKhz = 3_000_000, throttled = true, loadRatio = 0.4f)
        )
        val findings = Diagnoser.analyze(s)
        assertTrue(findings.any {
            it.severity == Diagnoser.Severity.CRITICAL && it.title.contains("троттлинг", ignoreCase = true)
        })
    }

    @Test
    fun `неизвестный маршрут не превращается в bluetooth`() {
        val s = Snapshot(
            audio = AudioState(routeInfo = AudioRouteInfo(AudioRoute.UNKNOWN, "несколько", null, false))
        )
        val findings = Diagnoser.analyze(s)
        assertTrue(findings.none { it.title.contains("Bluetooth") })
    }

    @Test
    fun `без KGSL gpu метрики остаются пустыми и это честно отмечается`() {
        val findings = Diagnoser.analyze(Snapshot(gpu = GpuState()))
        assertTrue(findings.any { it.title.contains("KGSL") })
    }

    @Test
    fun `высокий PSI при идеальных частотах всё равно ловится`() {
        // Ключевой случай: частоты в норме, а кадры всё равно рвутся.
        val s = Snapshot(
            cpu = CpuState(curKhz = 3_000_000, hwMaxKhz = 3_000_000, throttled = false),
            pressure = PressureState(cpuSomeAvg10 = 42f)
        )
        val findings = Diagnoser.analyze(s)
        assertTrue(findings.any { it.title.contains("Простои CPU") })
    }
}
