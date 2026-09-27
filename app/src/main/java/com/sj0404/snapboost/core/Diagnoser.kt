package com.sj0404.snapboost.core

/**
 * Связывает наблюдаемые симптомы с конкретными причинами.
 *
 * Правило: каждое заключение обязано ссылаться на реально прочитанное поле.
 * Если источника нет, выносится INFO о том, что данных не хватает, — а не
 * правдоподобный диагноз. Это отличает инструмент от угадывалки.
 */
object Diagnoser {

    enum class Severity { CRITICAL, WARN, INFO }

    /**
     * Что приложение реально может сделать по этому заключению. Список
     * намеренно короткий: он содержит только действия из публичного API.
     * Всё, что требует системных прав, в UI уходит человеку текстом, а не
     * кнопкой, которая ничего не делает.
     */
    enum class Action {
        WIRED_AUDIO,
        SILENCE_NOTIFICATIONS,
        KILL_BACKGROUND,
        COOL_DOWN,
        OPEN_AUDIO_SETTINGS
    }

    data class Finding(
        val severity: Severity,
        val title: String,
        val detail: String,
        val action: Action? = null
    )

    fun analyze(s: Snapshot): List<Finding> {
        val f = ArrayList<Finding>(10)
        val a = s.audio

        // ------------------------------------------------------- Аудио: ядро жалоб
        val rate = a.underrunsPerSec
        if (rate != null && rate >= 0.5f) {
            f += Finding(
                Severity.CRITICAL,
                "Аудиобуфер голодает: underrun ${"%.1f".format(rate)}/с",
                "Счётчик underrun в AudioFlinger растёт. Буфер не успевает наполняться — " +
                    "именно это слышно как «рыба», щелчки и пропуски на спецэффектах.",
                if (a.route == AudioRoute.BLUETOOTH_A2DP) Action.WIRED_AUDIO else Action.KILL_BACKGROUND
            )
        }

        if (a.route == AudioRoute.BLUETOOTH_A2DP) {
            f += Finding(
                Severity.WARN,
                "Звук идёт по Bluetooth A2DP",
                "Кодек перепаковывает поток блоками по воздуху и не имеет запаса на просадку. " +
                    "При нагруженном CPU это почти гарантированные щелчки. Проводные наушники или динамик надёжнее.",
                Action.WIRED_AUDIO
            )
        } else if (a.route == AudioRoute.UNKNOWN) {
            f += Finding(
                Severity.INFO,
                "Маршрут вывода не определён",
                "Определить единственный маршрут не получилось: либо подключено несколько устройств, " +
                    "либо нет данных. Проверка A2DP поэтому не выполнялась."
            )
        }

        if (a.sampleRateMismatch == true) {
            f += Finding(
                Severity.WARN,
                "Работа ресемплинга",
                "Запрошенная частота ${a.sampleRate} Гц не совпадает с частотой HAL. " +
                    "Каждый пересчёт — лишняя задержка в тракте, а значит меньше запас буфера."
            )
        }

        a.dspEvidence?.takeIf { a.dspSuspect == true }?.let {
            f += Finding(
                Severity.WARN,
                "В тракте есть системный DSP",
                "Найдено: $it. Объёмная обработка добавляет буферы и задержку; в стерео-играх она не нужна. " +
                    "Публичным API обычного приложения её не отключить — только в системных настройках звука.",
                Action.OPEN_AUDIO_SETTINGS
            )
        }

        val foreignFocus = a.focusHolders.filter { it != s.process.packageName }
        if (foreignFocus.isNotEmpty()) {
            f += Finding(
                Severity.WARN,
                "Аудио-фокус удерживает другое приложение",
                "В стеке фокуса: ${foreignFocus.joinToString()}. Пока фокус не ваш, система может " +
                    "приглушить игровой звук целиком.",
                Action.SILENCE_NOTIFICATIONS
            )
        }

        if (a.underrunsTotal == null) {
            f += Finding(
                Severity.INFO,
                "Счётчик underrun недоступен",
                "Он живёт в `dumpsys media.audio_flinger`, а этот дамп требует android.permission.DUMP — " +
                    "приложению без системных прав он не выдаётся. Приложение не подставляет вместо него " +
                    "оценку: активный самотест аудиобуфера делается по кнопке и измеряет реальное поведение HAL."
            )
        }

        // ------------------------------------------------------- Троттлинг и нагрев
        if (s.cpu.throttled == true) {
            f += Finding(
                Severity.CRITICAL,
                "CPU под троттлингом",
                "Потолок частоты опущен относительно заводского максимума " +
                    "(${"%.0f".format(100 * (s.cpu.loadRatio ?: 0f))}% от максимума). " +
                    "Троттлинг поднимает frametime, а от frametime страдает и аудиобуфер.",
                Action.COOL_DOWN
            )
        }

        s.thermal.status?.let { st ->
            if (st >= 3) {
                f += Finding(
                    Severity.CRITICAL,
                    "Системный тепловой статус: ${s.thermal.statusName}",
                    "Тепловая подсистема сообщает серьёзный нагрев. Частоты будут урезаны " +
                        "независимо от настроек.",
                    Action.COOL_DOWN
                )
            }
        }

        s.thermal.headroom?.let { h ->
            if (h >= 0.8f) {
                f += Finding(
                    Severity.WARN,
                    "Запас по нагреву исчерпывается",
                    "Headroom = ${"%.2f".format(h)} при прогнозе на 5 секунд. Скоро начнётся сброс частот.",
                    Action.COOL_DOWN
                )
            }
        }

        s.thermal.hottest?.let { t ->
            if (t >= 45f) {
                val zone = s.thermal.zones.firstOrNull()?.type ?: "?"
                f += Finding(
                    Severity.WARN,
                    "Горячая зона $zone: ${"%.1f".format(t)} °C",
                    "Температура корпуса напрямую определяет, сколько производительности система " +
                        "позволит себе отдать. Выше 45 °C на Snapdragon просадка кадров заметна в игре.",
                    Action.COOL_DOWN
                )
            }
        }

        s.gpu.throttled?.let { th ->
            if (th) {
                f += Finding(
                    Severity.WARN,
                    "GPU под троттлингом (KGSL)",
                    "Драйвер сообщает активный троттлинг/pwrclamp. Частота GPU снижена " +
                        "независимо от загрузки.",
                    Action.COOL_DOWN
                )
            }
        }

        // ------------------------------------------------------- Простои (фризы)
        s.pressure.cpuSomeAvg10?.let { v ->
            if (v >= 15f) {
                f += Finding(
                    Severity.WARN,
                    "Простои CPU: ${"%.0f".format(v)}% времени",
                    "Задачи стоят в ожидании ресурсов. Это прямой признак «дёргающегося» кадра: " +
                        "частота может быть идеальной, а кадр всё равно опоздать."
                )
            }
        }

        s.pressure.ioSomeAvg10?.let { v ->
            if (v >= 5f) {
                f += Finding(
                    Severity.WARN,
                    "Простои ввода-вывода: ${"%.0f".format(v)}% времени",
                    "Поток подгрузки ресурсов идёт рывками, что и вызывает паузы в стриминге звука."
                )
            }
        }

        // ------------------------------------------------------- Кадры
        if (s.fps.valid) {
            val j = s.fps.jankPct ?: 0
            if (j >= 15) {
                f += Finding(
                    Severity.WARN,
                    "Дропы кадров: $j%",
                    "Более пятой части кадров длиннее полутора периодов дисплея. " +
                        "Проверьте нагрев и частоты выше.",
                    Action.COOL_DOWN
                )
            }
        } else {
            f += Finding(
                Severity.INFO,
                "Реальный FPS недоступен обычному приложению",
                "Настоящие кадры в секунду живут в `dumpsys SurfaceFlinger --latency`, а дамп требует " +
                    "android.permission.DUMP. Без системных прав приложение не ставит внешних сервисов " +
                    "ради одной метрики и не выдумывает число: в HUD будет N/A."
            )
        }

        // ------------------------------------------------------- Процесс игры
        s.process.cpuPct?.let { pct ->
            if (pct >= 180) {
                f += Finding(
                    Severity.WARN,
                    "Игра грузит CPU: $pct%",
                    "Процесс забирает больше двух ядер. На уходе в троттлинг оставшимся ядрам " +
                        "не хватит ресурса на аудиопоток."
                )
            }
        }

        if (s.process.pid == null && s.process.packageName != null) {
            f += Finding(
                Severity.INFO,
                "Процесс игры не найден",
                "Пакет ${s.process.packageName} известен, но процесса с таким именем нет. " +
                    "Возможно, игра свёрнута или запущена в отдельном профиле."
            )
        }

        if (s.gpu.vendorPath == null) {
            f += Finding(
                Severity.INFO,
                "KGSL не найден",
                "Это не Qualcomm Adreno (или драйвер скрыт), поэтому GPU-метрики пустые. " +
                    "CPU, температура, PSI и аудио при этом остаются рабочими."
            )
        }

        // Сначала критичное, затем предупреждения и заметки: в списке и в HUD
        // пользователь должен видеть самую серьёзную проблему первой.
        return f.sortedBy { it.severity.ordinal }
    }
}
