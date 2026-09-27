package com.sj0404.snapboost.core.system

import com.sj0404.snapboost.core.CpuState
import com.sj0404.snapboost.core.ThermalState
import com.sj0404.snapboost.core.ThermalZone
import android.os.PowerManager
import java.io.File

/**
 * Частоты и троттлинг CPU по cpufreq.
 *
 * Признак троттлинга берётся не из эвристики "частота упала", а из
 * сравнения scaling_max_freq с cpuinfo_max_freq: когда thermal governor
 * (или OEM-профилировщик) зажимает потолок, разница видна напрямую.
 * Дополнительно считается big.LITTLE-ядро — именно оно проседает первым
 * под нагрузкой Unity/UE и именно по нему видно потерю кадров.
 */
object CpuReader {

    private var lastGovernor: String? = null

    fun read(): CpuState {
        val online = Sysfs.onlineCpus()
        val cores = Sysfs.cpuCount()
        if (online.isEmpty()) {
            return CpuState(coreCount = cores, onlineCores = 0, governor = lastGovernor)
        }

        var curSum = 0L
        var curCount = 0
        var lockedSum = 0L
        var lockedCount = 0
        var hwSum = 0L
        var hwCount = 0
        var governor: String? = null
        var bigIdx = online.maxOrNull() ?: 0

        for (i in online) {
            val cur = Sysfs.readLong(Sysfs.cpufreqFile(i, "scaling_cur_freq").path)
            if (cur != null) {
                curSum += cur
                curCount++
            }
            val locked = Sysfs.readLong(Sysfs.cpufreqFile(i, "scaling_max_freq").path)
            if (locked != null) {
                lockedSum += locked
                lockedCount++
            }
            val hw = Sysfs.readLong(Sysfs.cpufreqFile(i, "cpuinfo_max_freq").path)
            if (hw != null) {
                hwSum += hw
                hwCount++
            }
            if (governor == null) {
                governor = Sysfs.readString(Sysfs.cpufreqFile(i, "scaling_governor").path)
            }
        }

        if (governor != null) lastGovernor = governor

        val bigCur = Sysfs.readLong(Sysfs.cpufreqFile(bigIdx, "scaling_cur_freq").path)
        val bigHw = Sysfs.readLong(Sysfs.cpufreqFile(bigIdx, "cpuinfo_max_freq").path)

        val avgCur = if (curCount > 0) curSum / curCount else null
        val avgLocked = if (lockedCount > 0) lockedSum / lockedCount else null
        val avgHw = if (hwCount > 0) hwSum / hwCount else null

        // Потолок ниже заводского максимума => тепловой/политический governor в действии.
        val throttled: Boolean? = when {
            avgLocked != null && avgHw != null && avgHw > 0 -> avgLocked < avgHw * 0.97
            avgCur != null && avgHw != null && avgHw > 0 -> avgCur < avgHw * 0.55
            else -> null
        }

        return CpuState(
            coreCount = cores,
            onlineCores = online.size,
            curKhz = avgCur?.toInt(),
            lockedMaxKhz = avgLocked?.toInt(),
            hwMaxKhz = avgHw?.toInt(),
            bigCurKhz = bigCur?.toInt(),
            bigMaxKhz = bigHw?.toInt(),
            governor = governor ?: lastGovernor,
            throttled = throttled,
            loadRatio = if (avgCur != null && avgHw != null && avgHw > 0) {
                (avgCur.toFloat() / avgHw).coerceIn(0f, 1f)
            } else null
        )
    }
}

/**
 * Температуры по thermal-зонам + официальный статус от PowerManager.
 *
 * Зоны читаются напрямую, потому что у вендоров разные имена типов
 * (battery, case, soc, uss, gpu1...), а PowerManager.getCurrentThermalStatus()
 * даёт одну агрегированную цифру без разбивки. Показываем и то, и то.
 */
object ThermalReader {

    fun read(power: PowerManager?): ThermalState {
        val status = try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                power?.currentThermalStatus
            } else null
        } catch (_: Throwable) {
            null
        }

        val headroom = try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                power?.getThermalHeadroom(5)?.takeIf { it.isFinite() }
            } else null
        } catch (_: Throwable) {
            null
        }

        val zones = ArrayList<ThermalZone>(8)
        for (dir in Sysfs.thermalZones()) {
            val type = Sysfs.readString(File(dir, "type").path) ?: continue
            val c = Sysfs.readMilliC(File(dir, "temp").path) ?: continue
            if (c < -20f || c > 120f) continue
            zones += ThermalZone(type, c)
        }
        val sorted = zones.sortedByDescending { it.celsius }
        return ThermalState(
            status = status,
            headroom = headroom,
            zones = sorted.take(8),
            hottest = sorted.firstOrNull()?.celsius
        )
    }
}

/**
 * Pressure Stall Information из /proc/pressure (Android 12+).
 *
 * Это самый прямой измеритель «фризов»: some avg10 — доля времени, когда
 * задачи стояли в ожидании ресурсов. Частоты могут выглядеть идеально,
 * при этом CPU some avg10 = 40% означает, что кадры рвутся.
 */
object PressureReader {

    private fun parse(path: String): Float? {
        val text = Sysfs.readString(path) ?: return null
        val some = text.substringBefore('\n')
        val m = Regex("some\\s+avg10=([0-9.]+)").find(some) ?: return null
        return m.groupValues[1].toFloatOrNull()
    }

    fun read(): com.sj0404.snapboost.core.PressureState =
        com.sj0404.snapboost.core.PressureState(
            cpuSomeAvg10 = parse("/proc/pressure/cpu"),
            memSomeAvg10 = parse("/proc/pressure/memory"),
            ioSomeAvg10 = parse("/proc/pressure/io")
        )
}

/**
 * CPU%, RSS и потоки конкретного процесса по /proc.
 *
 * CPU% считается как разница utime+stime между двумя отсчётами, делённая на
 * число ядер и на интервал. Это тот же метод, что у top, и он не требует
 * никаких разрешений — в отличие от ActivityManager.getMyMemoryState.
 */
object ProcReader {

    private var lastTicks: Long = -1
    private var lastAt: Long = 0
    private var lastPid: Int = -1
    private var lastCpuPct: Int? = null
    private val USER_HZ = 100L

    private fun findPid(pkg: String): Int? {
        val dir = File("/proc")
        val children = dir.listFiles() ?: return null
        for (f in children) {
            val name = f.name
            if (!name.all { it.isDigit() }) continue
            val cmd = Sysfs.readString(File(f, "cmdline").path) ?: continue
            val proc = cmd.substringBefore('\u0000')
            if (proc == pkg) return name.toIntOrNull()
        }
        return null
    }

    private fun readTicks(pid: Int): Long? {
        val stat = Sysfs.readString(File("/proc/$pid/stat").path) ?: return null
        // comm может содержать пробелы и скобки, поэтому режем по последней ')'
        val after = stat.substringAfterLast(')')
        val fields = after.trim().split(Regex("\\s+"))
        // после ')' идёт state, поэтому utime это fields[11], stime fields[12]
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
        return utime + stime
    }

    fun read(pkg: String?): com.sj0404.snapboost.core.ProcessState {
        if (pkg.isNullOrEmpty()) return com.sj0404.snapboost.core.ProcessState()
        val pid = if (pkg == cachedPkg) lastPid.takeIf { it > 0 } else findPid(pkg)
        cachedPkg = pkg
        if (pid == null || pid <= 0) {
            lastPid = -1
            return com.sj0404.snapboost.core.ProcessState(packageName = pkg)
        }
        lastPid = pid

        val now = System.currentTimeMillis()
        val ticks = readTicks(pid)
        val cores = Sysfs.cpuCount().coerceAtLeast(1)

        var cpuPct: Int? = lastCpuPct
        if (ticks != null && lastTicks >= 0 && lastPid == pid && now > lastAt) {
            val dt = (now - lastAt) / 1000.0
            if (dt > 0.2) {
                val delta = ticks - lastTicks
                cpuPct = (delta / USER_HZ / dt / cores * 100).toInt().coerceIn(0, 999)
            }
        }
        if (ticks != null) {
            lastTicks = ticks
            lastAt = now
        }
        lastCpuPct = cpuPct

        val rssKb = Sysfs.readLong("/proc/$pid/statm")?.let { it * 4 }
        val threads = Sysfs.readString("/proc/$pid/status")
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("Threads:") }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()

        return com.sj0404.snapboost.core.ProcessState(
            packageName = pkg,
            pid = pid,
            cpuPct = cpuPct,
            rssMb = rssKb?.div(1024)?.toInt(),
            threads = threads,
            isGame = true
        )
    }

    private var cachedPkg: String? = null

    /** Сбрасывает накопленные счётчики — нужно после смены игры. */
    fun reset() {
        lastTicks = -1
        lastAt = 0
        lastPid = -1
        lastCpuPct = null
        cachedPkg = null
    }
}
