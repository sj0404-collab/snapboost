package com.sj0404.snapboost.core.system

import java.io.File

/**
 * Чтение sysfs/procfs без исключений в горячем пути.
 *
 * Каждый путь на OEM-прошивках либо существует, либо нет: часть вендоров прячет
 * `cpufreq` под SELinux, часть ядер offline, часть thermal-зон скрыта. Поэтому
 * все методы возвращают null вместо того, чтобы бросать или подставлять
 * фиктивные значения. null в модели данных означает "нет данных", а не "ноль".
 */
object Sysfs {

    private val CPUS_ROOT = File("/sys/devices/system/cpu")
    private val THERMAL_ROOT = File("/sys/class/thermal")
    private val KGSL_CANDIDATES = listOf(
        "/sys/class/kgsl/kgsl-3d0",
        "/sys/class/kgsl/kgsl-3d1",
        "/sys/devices/platform/soc/*.qcom,kgsl-3d0/kgsl/kgsl-3d0"
    )

    fun readString(path: String): String? = readString(File(path))

    fun readString(file: File): String? = try {
        if (!file.isFile || !file.canRead()) return null
        val text = file.readText().trim()
        if (text.isEmpty()) null else text
    } catch (_: Throwable) {
        null
    }

    fun readLong(path: String): Long? = readString(path)?.toLongOrNull()

    fun readInt(path: String): Int? = readString(path)?.toIntOrNull()

    /** Частота в ГГц, если ядро отдаёт кГц. */
    fun readGhz(path: String): Float? = readLong(path)?.let { khz ->
        when {
            khz > 10_000_000L -> khz / 1_000_000_000f
            khz > 10_000L -> khz / 1_000_000f
            else -> khz / 1000f
        }
    }

    /** Температура из thermal-зоны в градусах Цельсия. */
    fun readMilliC(path: String): Float? = readLong(path)?.let { it / 1000f }

    fun cpuCount(): Int = try {
        CPUS_ROOT.listFiles { f -> f.name.matches(Regex("cpu\\d+")) }?.size ?: 0
    } catch (_: Throwable) {
        0
    }

    fun cpuDir(index: Int): File = File(CPUS_ROOT, "cpu$index")

    fun cpufreqFile(index: Int, name: String): File =
        File(cpuDir(index), "cpufreq/$name")

    fun onlineCpus(): List<Int> = try {
        (readString(File(CPUS_ROOT, "online")) ?: "")
            .split(',')
            .flatMap { part ->
                val bounds = part.split('-')
                if (bounds.size == 2) {
                    val from = bounds[0].trim().toIntOrNull()
                    val to = bounds[1].trim().toIntOrNull()
                    if (from != null && to != null && to >= from) (from..to).toList() else emptyList()
                } else {
                    part.trim().toIntOrNull()?.let { listOf(it) } ?: emptyList()
                }
            }
    } catch (_: Throwable) {
        emptyList()
    }

    fun thermalZones(): List<File> = try {
        THERMAL_ROOT.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
            ?.sortedBy { it.name.filter(Char::isDigit).toIntOrNull() ?: 0 }
            ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    fun kgslRoot(): File? = KGSL_CANDIDATES.firstNotNullOfOrNull { path ->
        val direct = File(path)
        if (direct.isDirectory) direct else null
    } ?: KGSL_CANDIDATES.firstNotNullOfOrNull { glob ->
        val parent = glob.substringBefore("/*.qcom")
        File(parent).listFiles { f -> f.name.endsWith(".qcom,kgsl-3d0") }?.firstOrNull()
            ?.let { File(it, "kgsl/kgsl-3d0") }
            ?.takeIf { it.isDirectory }
    }

    /** Число незначимых ошибок чтения — для диагностики "слепых зон" на конкретной прошивке. */
    @Volatile
    var readFailures: Int = 0
        private set

    fun noteFailure() {
        readFailures++
    }
}
