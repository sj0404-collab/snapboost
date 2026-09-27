package com.sj0404.snapboost.audio

import com.sj0404.snapboost.core.Settings
import com.sj0404.snapboost.core.audio.AudioProbe

/**
 * Применяет профиль целиком и собирает честный отчёт.
 *
 * Правила, из-за которых это отдельный класс, а не метод во ViewModel:
 *
 *  1. Шаги не «вроде применяются» — каждый возвращает свой [AudioTuner.Outcome],
 *     а отчёт показывает, что именно вышло. Часть шагов физически требует
 *     действия пользователя (системный диалог), это помечено, а не спрятано.
 *  2. Один упавший шаг не отменяет остальные. Если Bluetooth не выключился,
 *     освобождение памяти всё равно полезно.
 *  3. Шаг, которого нет в реестре, — это ошибка разработчика, а не «пропустить
 *     молча»: такое попадает в отчёт как невыполненное.
 */
class TuningRunner(
    private val tuner: AudioTuner,
    private val settings: Settings
) {

    data class StepResult(
        val step: TuningProfiles.Step,
        val outcome: AudioTuner.Outcome
    )

    data class Report(
        val profile: TuningProfiles.Profile,
        val results: List<StepResult>,
        val probe: AudioProbe.Result?
    ) {
        val applied: Int get() = results.count { it.outcome.ok }
        val manual: Int get() = results.count { it.outcome.manual }
        val failed: Int get() = results.size - applied - manual

        /** Одна строка для карточки: без приукрашивания, только итог. */
        fun headline(): String = when {
            results.isEmpty() -> "Профиль пуст: применять нечего"
            failed == 0 && manual == 0 -> "Применено шагов: $applied из ${results.size}"
            else -> "Применено $applied, нужно подтверждение: $manual, не вышло: $failed"
        }
    }

    fun run(profile: TuningProfiles.Profile): Report {
        val results = ArrayList<StepResult>(profile.steps.size)
        var probe: AudioProbe.Result? = null

        for (step in profile.steps) {
            val outcome = when (step.id) {
                TuningProfiles.BT_OFF.id -> tuner.disableBluetooth()
                TuningProfiles.DND_ON.id -> tuner.setDnd(true)
                TuningProfiles.DND_OFF.id -> tuner.setDnd(false)
                TuningProfiles.MIC_DENOISE_ON.id -> tuner.setMicDenoise(true)
                TuningProfiles.MIC_DENOISE_OFF.id -> tuner.setMicDenoise(false)
                TuningProfiles.AUTO_BRIGHTNESS_OFF.id -> tuner.setAutoBrightness(false)
                TuningProfiles.AUTO_BRIGHTNESS_ON.id -> tuner.setAutoBrightness(true)
                TuningProfiles.BRIGHTNESS_LOW.id -> lowerBrightness()
                TuningProfiles.BRIGHTNESS_RESTORE.id -> restoreBrightness()
                TuningProfiles.KILL_BACKGROUND.id -> tuner.killHeavyBackground()
                TuningProfiles.CLEAR_CACHE.id -> tuner.clearOwnCache()
                TuningProfiles.IGNORE_BATTERY_OPT.id -> tuner.requestIgnoreBatteryOptimizations()
                TuningProfiles.SELFTEST.id -> {
                    val r = tuner.audioSelfTest()
                    probe = r
                    AudioTuner.Outcome(
                        ok = !r.starved,
                        title = "Самотест аудиобуфера",
                        detail = "${"%.0f".format(r.bufferMs)} мс буфер, минимум " +
                            "${r.minInFlightFrames} из ${r.bufferFrames} кадров в полёте. ${r.verdict}"
                    )
                }
                else -> AudioTuner.Outcome(
                    false,
                    step.title,
                    "Шаг «${step.id}» не реализован в тюнере: это ошибка сборки, а не отказ системы"
                )
            }
            results += StepResult(step, outcome)
        }

        settings.lastProfileId = profile.id
        return Report(profile, results, probe)
    }

    /**
     * Понижает яркость, но только один раз запоминая исходное значение.
     * Иначе второй профиль подряд «запомнил» бы уже пониженную яркость, и
     * «Сбросить» вернул бы не то, что человек выставил руками.
     */
    private fun lowerBrightness(): AudioTuner.Outcome {
        if (settings.brightnessBeforeTune < 0) {
            val current = tuner.readState().brightness
            if (current >= 0) settings.brightnessBeforeTune = current
        }
        return tuner.setBrightness(LOW_BRIGHTNESS)
    }

    private fun restoreBrightness(): AudioTuner.Outcome {
        val saved = settings.brightnessBeforeTune
        if (saved < 0) {
            return AudioTuner.Outcome(
                false,
                "Яркость",
                "Приложение не понижало яркость в этой сессии — нечего возвращать"
            )
        }
        val outcome = tuner.setBrightness(saved.toFloat() / AudioTuner.MAX_BRIGHTNESS)
        if (outcome.ok) settings.brightnessBeforeTune = -1
        return outcome
    }

    companion object {
        /** 25% — уровень, на котором экран не слепит и не греет корпус. */
        const val LOW_BRIGHTNESS = 0.25f
    }
}
