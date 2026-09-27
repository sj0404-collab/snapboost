package com.sj0404.snapboost.core

/**
 * Модель метрик.
 *
 * Ключевое правило, которому подчинены все поля: null означает "источник
 * недоступен", а НЕ "ноль" и НЕ "норма". Оверлей в этом случае печатает N/A.
 * Это сделано намеренно: подставные значения в HUD-мониторе опаснее
 * отсутствующих — по ним принимают решения о настройке железа.
 */

data class CpuState(
    val coreCount: Int = 0,
    val onlineCores: Int = 0,
    val curKhz: Int? = null,
    val lockedMaxKhz: Int? = null,
    val hwMaxKhz: Int? = null,
    val bigCurKhz: Int? = null,
    val bigMaxKhz: Int? = null,
    val governor: String? = null,
    /** Частота зажата ниже заводского максимума — прямой признак thermal governor. */
    val throttled: Boolean? = null,
    /** Доля от hwMax, где cur = scaling_cur_freq. */
    val loadRatio: Float? = null
) {
    val curMhz: Int? get() = curKhz?.div(1000)
    val bigCurMhz: Int? get() = bigCurKhz?.div(1000)
    val bigMaxMhz: Int? get() = bigMaxKhz?.div(1000)
}

data class GpuState(
    val vendorPath: String? = null,
    val busyPct: Int? = null,
    val clockMhz: Int? = null,
    val maxClockMhz: Int? = null,
    /** Qualcomm отдаёт 0/1; на некоторых прошивках значение > 0 означает активный pwrclamp. */
    val throttling: Int? = null,
    val pwrclamp: Int? = null,
    val idleMs: Int? = null
) {
    val throttled: Boolean? get() = throttling?.let { it > 0 } ?: pwrclamp?.let { it > 0 }
}

data class ThermalZone(val type: String, val celsius: Float)

data class ThermalState(
    val status: Int? = null,
    val headroom: Float? = null,
    val zones: List<ThermalZone> = emptyList(),
    val hottest: Float? = null
) {
    val statusName: String?
        get() = status?.let {
            when (it) {
                0 -> "none"
                1 -> "light"
                2 -> "moderate"
                3 -> "severe"
                else -> "critical/emergency"
            }
        }
}

data class PressureState(
    val cpuSomeAvg10: Float? = null,
    val memSomeAvg10: Float? = null,
    val ioSomeAvg10: Float? = null
) {
    val worst: Float? get() = listOfNotNull(cpuSomeAvg10, memSomeAvg10, ioSomeAvg10).maxOrNull()
    val worstName: String?
        get() = when (worst) {
            null -> null
            cpuSomeAvg10 -> "CPU"
            memSomeAvg10 -> "RAM"
            else -> "I/O"
        }
}

data class ProcessState(
    val packageName: String? = null,
    val pid: Int? = null,
    /** Доля от одного ядра. 100% — одно ядро загружено полностью. */
    val cpuPct: Int? = null,
    val rssMb: Int? = null,
    val threads: Int? = null,
    val isGame: Boolean = false
)

data class FpsState(
    val fps: Float? = null,
    val frameTimeP50: Float? = null,
    val frameTimeP99: Float? = null,
    val jankPct: Int? = null,
    val displayHz: Int? = null,
    val valid: Boolean = false
)

enum class AudioRoute { SPEAKER, WIRED, BLUETOOTH_A2DP, BLUETOOTH_LE, USB, HDMI, UNKNOWN, UNAVAILABLE }

data class AudioRouteInfo(
    val route: AudioRoute = AudioRoute.UNAVAILABLE,
    val name: String = "N/A",
    val detail: String? = null,
    /** null, когда маршрут невозможно определить однозначно. Не угадывается. */
    val confident: Boolean = false
)

data class AudioState(
    val sampleRate: Int? = null,
    val framesPerBuffer: Int? = null,
    val routeInfo: AudioRouteInfo = AudioRouteInfo(),
    /**
     * Счётчик underrun из AudioFlinger. Обычному приложению недоступен:
     * `dumpsys media.audio_flinger` требует android.permission.DUMP,
     * поэтому поле остаётся null и в HUD отображается как N/A.
     */
    val underrunsTotal: Int? = null,
    val underrunsPerSec: Float? = null,
    val activeTracks: Int? = null,
    val outputLatencyMs: Int? = null,
    val sampleRateMismatch: Boolean? = null,
    /** Активные сторонние приложения, способные перехватить аудио-фокус. */
    val focusHolders: List<String> = emptyList(),
    val dspSuspect: Boolean? = null,
    val dspEvidence: String? = null
) {
    val route: AudioRoute get() = routeInfo.route
    val routeName: String get() = routeInfo.name
}

data class Snapshot(
    val ts: Long = System.currentTimeMillis(),
    val cpu: CpuState = CpuState(),
    val gpu: GpuState = GpuState(),
    val thermal: ThermalState = ThermalState(),
    val pressure: PressureState = PressureState(),
    val process: ProcessState = ProcessState(),
    val audio: AudioState = AudioState(),
    val fps: FpsState = FpsState()
)
