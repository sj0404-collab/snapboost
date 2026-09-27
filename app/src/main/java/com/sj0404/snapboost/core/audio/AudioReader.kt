package com.sj0404.snapboost.core.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import com.sj0404.snapboost.core.AudioRoute
import com.sj0404.snapboost.core.AudioRouteInfo
import com.sj0404.snapboost.core.AudioState
import com.sj0404.snapboost.core.system.Privileged

/**
 * Диагностика аудиотракта — главный модуль приложения.
 *
 * Механика «рвущегося» звука в мобильных играх всегда одна: аудиобуфер не
 * успевает наполняться. Пока WRITE идёт вовремя, всё хорошо; как только
 * игра отдаёт кадр позже, чем буфер опустошается, HAL не перезаполняет его и
 * в выходе появляются щелчки, повторы и «рыба». Дальше по цепочке:
 *
 *  1. Игровой AudioTrack теряет данные (underrun) — счётчик в AudioFlinger.
 *  2. Задержка растёт, потому что система тратит CPU на DSP/эквалайзер.
 *  3. Данные идут по Bluetooth-кодеку с малым буфером — щелчки гарантированы.
 *  4. Кто-то перехватил аудио-фокус — игровой звук глушится целиком.
 *
 * Каждый пункт проверяется по своему источнику; если источника нет, поле
 * остаётся null, а не заполняется правдоподобной догадкой.
 */
class AudioReader(private val context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var lastUnderruns: Int? = null
    private var lastUnderrunAt: Long = 0L

    fun read(): AudioState {
        val sampleRate = readProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        val fpb = readProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()

        // Реальная частота, с которой умеет работать HAL. Расхождение с текущей
        // означает ресемплинг, а ресемплинг — источник артефактов.
        val nativeRate = try {
            AudioTrack.getNativeOutputSampleRate(context).takeIf { it > 0 }
        } catch (_: Throwable) {
            null
        }
        val mismatch = when {
            sampleRate != null && nativeRate != null && nativeRate > 0 -> sampleRate != nativeRate
            else -> null
        }

        val audioDump = Privileged.dumpsys("audio", ttlMs = 4000L)
        val flinger = Privileged.dumpsys("media.audio_flinger", ttlMs = 3000L)

        val underruns = flinger?.let { parseUnderruns(it) }
        val perSec = computeUnderrunRate(underruns)
        val dsp = audioDump?.let { detectDsp(it) }

        return AudioState(
            sampleRate = sampleRate ?: nativeRate,
            framesPerBuffer = fpb,
            routeInfo = resolveRoute(audioDump),
            underrunsTotal = underruns,
            underrunsPerSec = perSec,
            activeTracks = flinger?.let { countOutputThreads(it) },
            outputLatencyMs = flinger?.let { parseLatency(it) },
            sampleRateMismatch = mismatch,
            focusHolders = audioDump?.let { parseFocusHolders(it) }.orEmpty(),
            dspSuspect = dsp != null,
            dspEvidence = dsp
        )
    }

    private fun readProperty(name: String): String? = try {
        @Suppress("DEPRECATION")
        audioManager.getProperty(name)
    } catch (_: Throwable) {
        null
    }

    /**
     * Определяет реальный маршрут вывода.
     *
     * Без привилегий надёжно определить можно только когда подключено ровно
     * одно внешнее устройство. Если их несколько или ни одного — возвращается
     * UNKNOWN с confident=false, а не догадка «наверное динамик».
     * С привилегиями парсится `dumpsys audio`, где у потока есть строка
     * "Current: N (device)".
     */
    private fun resolveRoute(audioDump: String?): AudioRouteInfo {
        if (audioDump != null) {
            parseCurrentDevice(audioDump)?.let { return it }
        }

        val devices = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Throwable) {
            emptyArray<AudioDeviceInfo>()
        }
        if (devices.isEmpty()) return AudioRouteInfo()

        val external = devices.filterNot { it.isInternal() }
        return when {
            external.isEmpty() -> {
                val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                AudioRouteInfo(
                    route = AudioRoute.SPEAKER,
                    name = "Динамик",
                    detail = speaker?.productName?.toString()?.takeIf { it.isNotBlank() },
                    confident = true
                )
            }
            external.size == 1 -> describe(external.first())
            else -> AudioRouteInfo(
                route = AudioRoute.UNKNOWN,
                name = "несколько",
                detail = external.joinToString(", ") { it.typeName() },
                confident = false
            )
        }
    }

    private fun parseCurrentDevice(dump: String): AudioRouteInfo? {
        val m = Regex("Current:\\s*\\d+\\s*\\(([^)]+)\\)").find(dump) ?: return null
        val raw = m.groupValues[1].lowercase()
        val route = when {
            raw.contains("a2dp") -> AudioRoute.BLUETOOTH_A2DP
            raw.contains("bluetooth") || raw.contains("bt_sco") -> AudioRoute.BLUETOOTH_LE
            raw.contains("hdmi") -> AudioRoute.HDMI
            raw.contains("usb") -> AudioRoute.USB
            raw.contains("headset") || raw.contains("wired") -> AudioRoute.WIRED
            raw.contains("speaker") -> AudioRoute.SPEAKER
            else -> AudioRoute.UNKNOWN
        }
        return AudioRouteInfo(route = route, name = raw, confident = route != AudioRoute.UNKNOWN)
    }

    private fun describe(d: AudioDeviceInfo): AudioRouteInfo {
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() }
        val info = when (d.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                AudioRouteInfo(AudioRoute.BLUETOOTH_A2DP, "Bluetooth A2DP", name, true)
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                AudioRouteInfo(AudioRoute.BLUETOOTH_LE, "Bluetooth LE", name, true)
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                AudioRouteInfo(AudioRoute.WIRED, "Проводной/USB", name, true)
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC ->
                AudioRouteInfo(AudioRoute.HDMI, "HDMI", name, true)
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ->
                AudioRouteInfo(AudioRoute.SPEAKER, "Динамик", name, true)
            else -> AudioRouteInfo(AudioRoute.UNKNOWN, d.typeName(), name, false)
        }
        return info
    }

    private fun AudioDeviceInfo.isInternal(): Boolean =
        type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
            type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
            type == AudioDeviceInfo.TYPE_TELEPHONY ||
            type == AudioDeviceInfo.TYPE_BUILTIN_MIC

    private fun AudioDeviceInfo.typeName(): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "a2dp"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb headset"
        else -> "type=$type"
    }

    /**
     * Суммирует счётчики underrun из дампа AudioFlinger.
     *
     * Формат поля менялся между версиями Android ("Underruns: N",
     * "underruns=N"), поэтому берём все совпадения. Сумма монотонна, а значит
     * из неё корректно считается прирост в секунду. Если полей нет вовсе —
     * возвращаем null: счётчик на этой прошивке недоступен, и выдумывать его
     * нельзя, иначе в HUD появится правдоподобная ложь.
     */
    private fun parseUnderruns(dump: String): Int? {
        var total = 0
        var found = false
        Regex("(?i)\\bunderrun[s]?\\b\\s*[:=]\\s*(\\d+)")
            .findAll(dump)
            .forEach { m ->
                m.groupValues[1].toIntOrNull()?.let {
                    total += it
                    found = true
                }
            }
        return if (found) total else null
    }

    private fun computeUnderrunRate(current: Int?): Float? {
        val now = System.currentTimeMillis()
        val prev = lastUnderruns
        val prevAt = lastUnderrunAt
        if (current != null) {
            lastUnderruns = current
            lastUnderrunAt = now
        }
        if (current == null || prev == null || prevAt == 0L) return null
        val dt = (now - prevAt) / 1000f
        if (dt <= 0.2f) return null
        val delta = current - prev
        // Счётчик мог сброситься при пересоздании трека — это не "минус".
        return if (delta < 0) 0f else delta / dt
    }

    private fun countOutputThreads(dump: String): Int? {
        val n = Regex("(?m)^\\s*Output thread").findAll(dump).count()
        return if (n > 0) n else null
    }

    private fun parseLatency(dump: String): Int? =
        Regex("(?i)\\blatency[:=]\\s*(\\d+)").find(dump)
            ?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Стек аудио-фокуса. Если в вершине чужое приложение, оно может глушить
     * игру целиком — частая и непонятная пользователю причина «пропал звук».
     */
    private fun parseFocusHolders(dump: String): List<String> {
        val idx = dump.indexOf("Audio Focus stack", ignoreCase = true)
        if (idx < 0) return emptyList()
        val tail = dump.substring(idx, minOf(dump.length, idx + 1500))
        return Regex("(?:^|\\s)([a-z][a-zA-Z0-9_]*(?:\\.[a-z0-9_]+){2,})(?=/)")
            .findAll(tail)
            .map { it.groupValues[1] }
            .distinct()
            .filter { !it.startsWith("android.media") && !it.startsWith("com.android.systemui") }
            .take(4)
            .toList()
    }

    /**
     * Ищет следы системного DSP (Dolby/Dirac/Histen surround) в дампе.
     * Это гипотеза, поэтому наружу отдаётся вместе с найденным доказательством,
     * а UI показывает его как подозрение, а не как факт.
     */
    private fun detectDsp(dump: String): String? {
        val keys = listOf("dolby", "atmos", "dirac", "surround", "histen", "dsp_effect", "audio_effect")
        val lower = dump.lowercase()
        val hit = keys.firstOrNull { lower.contains(it) } ?: return null
        return dump.lineSequence()
            .firstOrNull { it.lowercase().contains(hit) }
            ?.trim()
            ?.take(120)
    }
}
