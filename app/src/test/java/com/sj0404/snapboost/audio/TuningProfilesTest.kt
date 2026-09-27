package com.sj0404.snapboost.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты на реестр пресетов.
 *
 * Здесь нет Android: проверяется чистая логика — что профили ссылаются только
 * на существующие шаги, не повторяют шаги и идут в общем порядке «от маршрута
 * к тесту». Эти инварианты ломаются тихо: шаг перестаёт выполняться, а отчёт
 * об этом узнаёт только пользователь в игре.
 */
class TuningProfilesTest {

    @Test
    fun `каждый шаг профиля есть в реестре`() {
        TuningProfiles.all.forEach { p ->
            p.steps.forEach { s ->
                assertTrue(
                    "Профиль ${p.id} ссылается на неизвестный шаг ${s.id}",
                    TuningProfiles.stepIds.contains(s.id)
                )
            }
        }
    }

    @Test
    fun `в профиле нет повторяющихся шагов`() {
        TuningProfiles.all.forEach { p ->
            val ids = p.steps.map { it.id }
            assertEquals(
                "Профиль ${p.id} повторяет шаг: $ids",
                ids.size, ids.distinct().size
            )
        }
    }

    @Test
    fun `шаги идут в порядке реестра`() {
        val order = TuningProfiles.allSteps.map { it.id }
        TuningProfiles.all.forEach { p ->
            val positions = p.steps.map { order.indexOf(it.id) }
            assertEquals(
                "Профиль ${p.id} идёт не в порядке реестра: $positions",
                positions.sorted(),
                positions
            )
        }
    }

    @Test
    fun `идентификаторы профилей уникальны`() {
        val ids = TuningProfiles.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `профиль находится по идентификатору`() {
        assertEquals(TuningProfiles.QUIET_GAME, TuningProfiles.byId("quiet_game"))
        assertEquals(null, TuningProfiles.byId("нет_такого"))
    }

    @Test
    fun `профиль чистого звука трогает только тракт`() {
        // Главный пресет не должен менять яркость или память: он про звук.
        val ids = TuningProfiles.CLEAN_SOUND.steps.map { it.id }
        assertTrue(ids.contains(TuningProfiles.BT_OFF.id))
        assertTrue(ids.contains(TuningProfiles.DND_ON.id))
        assertTrue(ids.contains(TuningProfiles.SELFTEST.id))
        assertTrue(!ids.contains(TuningProfiles.BRIGHTNESS_LOW.id))
    }

    @Test
    fun `сброс откатывает то, что профили меняют`() {
        val ids = TuningProfiles.RESET.steps.map { it.id }
        assertTrue(ids.contains(TuningProfiles.BRIGHTNESS_RESTORE.id))
        assertTrue(ids.contains(TuningProfiles.DND_OFF.id))
        assertTrue(ids.contains(TuningProfiles.MIC_DENOISE_OFF.id))
        assertTrue(!ids.contains(TuningProfiles.BT_OFF.id))
    }
}
