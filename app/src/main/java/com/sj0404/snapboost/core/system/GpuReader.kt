package com.sj0404.snapboost.core.system

import com.sj0404.snapboost.core.GpuState
import java.io.File

/**
 * GPU через KGSL — интерфейс Qualcomm для Adreno.
 *
 * Это единственный источник, который на Snapdragon честно показывает, что
 * делает GPU: загрузку в процентах, реальную частоту, заводской потолок и
 * признаки троттлинга. На Mali/PowerVR путей нет и вернётся пустое состояние
 * с null — приложение определит это как "не Snapdragon" и не будет ничего
 * рисовать зря.
 *
 * Все чтения идут по кандидатным именам: наборы файлов отличаются между
 * поколениями (kgsl-3d0/kgsl-3d1, adreno 6xx против 7xx), а часть узлов
 * закроет SELinux. Правило прежнее: нет файла — null, а не ноль.
 */
object GpuReader {

    /** Имена узлов KGSL, встречающиеся на разных поколениях SoC. */
    private val THROTTLING_NODES = listOf("throttling", "thermal_throttling", "gpu_throttling")
    private val PWRCLAMP_NODES = listOf("max_pwrclamp", "thermal_pwrclamp", "pwrclamp")

    @Volatile
    private var root: File? = null

    @Volatile
    private var rootResolved = false

    fun reset() {
        rootResolved = false
        root = null
    }

    fun read(): GpuState {
        val r = resolveRoot() ?: return GpuState()

        val busy = readInt(File(r, "gpu_busy_percentage"))?.coerceIn(0, 100)
        val clock = readInt(File(r, "gpu_clockfreq"))
        val maxClock = readInt(File(r, "max_gpuclk"))
        val idle = readInt(File(r, "idle_timer"))

        val throttling = THROTTLING_NODES.firstNotNullOfOrNull { readInt(File(r, it)) }
        val pwrclamp = PWRCLAMP_NODES.firstNotNullOfOrNull { readInt(File(r, it)) }

        // Если ни один узел троттлинга не виден, но частота заметно ниже
        // потолка во время высокой загрузки, считать это троттлингом нельзя:
        // это может быть просто динамическое управление. Поэтому null, а не догадка.
        return GpuState(
            vendorPath = r.path,
            busyPct = busy,
            clockMhz = clock,
            maxClockMhz = maxClock,
            throttling = throttling,
            pwrclamp = pwrclamp,
            idleMs = idle
        )
    }

    private fun readInt(f: File): Int? {
        val v = Sysfs.readString(f.path) ?: return null
        return v.trim().toIntOrNull() ?: v.trim().toLongOrNull()?.toInt()
    }

    private fun resolveRoot(): File? {
        if (rootResolved) return root
        synchronized(this) {
            if (rootResolved) return root
            val found = Sysfs.kgslRoot()
            root = found
            rootResolved = true
            return found
        }
    }

    /** Человекочитаемое имя платформы — для заголовка в UI. */
    fun platform(): String {
        val soc = Privileged.prop("ro.soc.model")
        if (!soc.isNullOrEmpty()) return soc
        val man = Privileged.prop("ro.product.manufacturer")
        val dev = Privileged.prop("ro.product.model")
        return listOfNotNull(man, dev).joinToString(" ").ifEmpty { "unknown" }
    }

    fun gpuModelName(): String? {
        Sysfs.kgslRoot() ?: return null
        return Privileged.prop("ro.board.platform")
            ?: Privileged.prop("ro.hardware")
            ?: "Adreno (KGSL)"
    }
}
