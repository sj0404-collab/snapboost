package com.sj0404.snapboost.audio

/**
 * Профили-пресеты: набор шагов, применяемых одним тапом.
 *
 * Профиль — это чистые данные: идентификаторы шагов и тексты. Всё исполнение
 * живёт в [AudioTuner], поэтому список пресетов можно проверить обычным
 * юнит-тестом без Android, а состав шагов — не размазывать по UI.
 *
 * Шаги описаны в терминах действия, а не в терминах пакета:
 * «выключить Bluetooth», «приглушить уведомления», «освободить фон». Порядок
 * в списке значим — он и есть порядок применения, от дешёвого к дорогому.
 */
object TuningProfiles {

    enum class Section { ROUTE, NOISE, MIC, HEAT, MEMORY, TEST }

    data class Step(
        val id: String,
        val section: Section,
        val title: String,
        /** Что именно делает шаг — одной строкой, для подтверждения в отчёте. */
        val detail: String
    )

    data class Profile(
        val id: String,
        val title: String,
        val summary: String,
        val steps: List<Step>
    )

    // ------------------------------------------------------------- реестр шагов

    private val step = { id: String, section: Section, title: String, detail: String ->
        Step(id, section, title, detail)
    }

    val DND_ON = step(
        "dnd_on", Section.NOISE, "Приглушить уведомления",
        "Режим «Не беспокоить» на приоритет: звуки уведомлений не перебивают игру"
    )
    val DND_OFF = step(
        "dnd_off", Section.NOISE, "Вернуть звук уведомлений",
        "Обычный режим прерываний"
    )
    val BT_OFF = step(
        "bt_off", Section.ROUTE, "Выключить Bluetooth",
        "A2DP убирает щелчки: поток по воздуху не имеет запаса на просадку"
    )
    val MIC_DENOISE_ON = step(
        "mic_denoise_on", Section.MIC, "Обработка микрофона",
        "Шумоподавление и эхоподавление — только там, где система даёт сессию"
    )
    val MIC_DENOISE_OFF = step(
        "mic_denoise_off", Section.MIC, "Выключить обработку микрофона",
        "Возврат к состоянию системы"
    )
    val BRIGHTNESS_LOW = step(
        "brightness_low", Section.HEAT, "Приглушить экран",
        "Яркость 25% и ручной режим: меньше нагрев — выше потолок частот CPU"
    )
    val AUTO_BRIGHTNESS_OFF = step(
        "auto_brightness_off", Section.HEAT, "Отключить автояркость",
        "Яркость не прыгает в игре, нагрев предсказуем"
    )
    val AUTO_BRIGHTNESS_ON = step(
        "auto_brightness_on", Section.HEAT, "Вернуть автояркость",
        "Система снова подстраивает яркость сама"
    )
    val BRIGHTNESS_RESTORE = step(
        "brightness_restore", Section.HEAT, "Вернуть исходную яркость",
        "Значение, которое было до применения профиля"
    )
    val KILL_BACKGROUND = step(
        "kill_background", Section.MEMORY, "Освободить фоновые процессы",
        "Ядра и память уходят играющему процессу"
    )
    val CLEAR_CACHE = step(
        "clear_cache", Section.MEMORY, "Очистить свой кэш",
        "Освобождает место, не трогая данные других приложений"
    )
    val IGNORE_BATTERY_OPT = step(
        "ignore_battery_opt", Section.MEMORY, "Не выгружать в фоне",
        "Системный запрос: без него foreground-сервис оверлея засыпает"
    )
    val SELFTEST = step(
        "audio_selftest", Section.TEST, "Самотест аудиобуфера",
        "Активная проверка: успевает ли HAL наполнять буфер"
    )

    /** Все шаги, которые умеет исполнять тюнер. Порядок = порядок в UI. */
    val allSteps: List<Step> = listOf(
        BT_OFF, DND_ON, DND_OFF, MIC_DENOISE_ON, MIC_DENOISE_OFF,
        BRIGHTNESS_LOW, BRIGHTNESS_RESTORE, AUTO_BRIGHTNESS_OFF, AUTO_BRIGHTNESS_ON,
        KILL_BACKGROUND, CLEAR_CACHE, IGNORE_BATTERY_OPT, SELFTEST
    )

    val stepIds: Set<String> = allSteps.map { it.id }.toSet()

    // ---------------------------------------------------------------- пресеты

    /**
     * «Чистый звук» — минимальный набор для главной жалобы: щелчки и треск.
     * Не трогает ни яркость, ни память: только то, что реально портит тракт.
     */
    val CLEAN_SOUND = Profile(
        id = "clean_sound",
        title = "Чистый звук",
        summary = "Bluetooth выкл, уведомления приглушены, фон освобождён, проверка буфера",
        steps = listOf(BT_OFF, DND_ON, KILL_BACKGROUND, SELFTEST)
    )

    /** «Тихая игра» — то же плюс всё, что снижает нагрев и шум в голосе. */
    val QUIET_GAME = Profile(
        id = "quiet_game",
        title = "Тихая игра",
        summary = "Профиль «Чистый звук» плюс обработка микрофона, тихий экран и защита от засыпания",
        steps = listOf(
            BT_OFF, DND_ON, MIC_DENOISE_ON, BRIGHTNESS_LOW, AUTO_BRIGHTNESS_OFF,
            KILL_BACKGROUND, CLEAR_CACHE, IGNORE_BATTERY_OPT, SELFTEST
        )
    )

    /** «Экономия энергии» — для длинных сессий, где нагрев важнее деталей тракта. */
    val BATTERY_SAFE = Profile(
        id = "battery_safe",
        title = "Экономия энергии",
        summary = "Тихий экран, ручная яркость, уведомления приглушены, фон освобождён",
        steps = listOf(DND_ON, BRIGHTNESS_LOW, AUTO_BRIGHTNESS_OFF, KILL_BACKGROUND, CLEAR_CACHE)
    )

    /** Откат всего, что приложение меняло само. */
    val RESET = Profile(
        id = "reset",
        title = "Сбросить",
        summary = "Возвращает то, что профиль поменял: яркость, автояркость, уведомления, микрофон",
        steps = listOf(DND_OFF, MIC_DENOISE_OFF, BRIGHTNESS_RESTORE, AUTO_BRIGHTNESS_ON)
    )

    val all: List<Profile> = listOf(CLEAN_SOUND, QUIET_GAME, BATTERY_SAFE, RESET)

    fun byId(id: String): Profile? = all.firstOrNull { it.id == id }
}
