package com.sj0404.snapboost.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты на отчёт о применении профиля.
 *
 * Проверяется ровно то свойство, ради которого отчёт существует: в нём видно,
 * что именно не сработало. Если ручной шаг или отказ системы молча скрылись
 * в «применено», человек будет искать, почему звук всё ещё рвётся.
 */
class TuningReportTest {

    private fun step(id: String) = TuningProfiles.allSteps.first { it.id == id }

    private fun report(vararg results: Pair<String, AudioTuner.Outcome>) = TuningRunner.Report(
        profile = TuningProfiles.CLEAN_SOUND,
        results = results.map { (id, o) -> TuningRunner.StepResult(step(id), o) },
        probe = null
    )

    private fun ok() = AudioTuner.Outcome(true, "t", "применено")
    private fun manual() = AudioTuner.Outcome(false, "t", "нужно подтверждение", manual = true)
    private fun fail() = AudioTuner.Outcome(false, "t", "запрещено системой")

    @Test
    fun `все шаги применены`() {
        val r = report(
            TuningProfiles.BT_OFF.id to ok(),
            TuningProfiles.DND_ON.id to ok()
        )
        assertEquals("Применено шагов: 2 из 2", r.headline())
    }

    @Test
    fun `ручной шаг не выдаётся за успешный`() {
        val r = report(
            TuningProfiles.BT_OFF.id to manual(),
            TuningProfiles.DND_ON.id to ok()
        )
        assertEquals("Применено 1, нужно подтверждение: 1, не вышло: 0", r.headline())
    }

    @Test
    fun `отказ системы попадает в счётчик неудач`() {
        val r = report(
            TuningProfiles.BT_OFF.id to fail(),
            TuningProfiles.DND_ON.id to ok(),
            TuningProfiles.KILL_BACKGROUND.id to manual()
        )
        assertEquals("Применено 1, нужно подтверждение: 1, не вышло: 1", r.headline())
    }

    @Test
    fun `пустой профиль не выдаёт отчёт об успехе`() {
        val r = TuningRunner.Report(TuningProfiles.CLEAN_SOUND, emptyList(), null)
        assertEquals("Профиль пуст: применять нечего", r.headline())
    }
}
