package com.sj0404.snapboost.core.system

import java.io.InputStream
import java.util.concurrent.TimeUnit

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val via: String
) {
    val ok: Boolean get() = exitCode == 0

    /**
     * Доступ запрещён SELinux/Binder, а не «команда не найдена».
     * Разница принципиальна: в первом случае метрику получить нельзя в принципе,
     * во втором — команда просто отсутствует на этой прошивке.
     */
    val denied: Boolean
        get() {
            val haystack = stderr + "\n" + stdout
            return haystack.contains("Permission denial", ignoreCase = true) ||
                haystack.contains("SecurityException", ignoreCase = true) ||
                haystack.contains("requires android.permission", ignoreCase = true) ||
                haystack.contains("Permission Denial", ignoreCase = true) ||
                haystack.contains("not allowed", ignoreCase = true)
        }

    val notFound: Boolean
        get() {
            val haystack = stderr + "\n" + stdout
            return haystack.contains("not found", ignoreCase = true) ||
                haystack.contains("No such file", ignoreCase = true) ||
                haystack.contains("Unknown command", ignoreCase = true)
        }
}

interface CommandRunner {
    val name: String
    val available: Boolean

    fun run(cmd: List<String>, timeoutMs: Int = 4000): CommandResult

    fun runLine(cmd: List<String>, timeoutMs: Int = 4000): String? =
        run(cmd, timeoutMs).stdout.lineSequence().firstOrNull { it.isNotBlank() }
}

/**
 * Читает stdout/stderr процесса параллельно, иначе на объёме dumpsys
 * (десятки килобайт) процесс упирается в заполнение pipe и взаимно блокируется
 * с waitFor. Это классический deadlock, поэтому потоки обязаны сливаться конкурентно.
 */
internal fun execProcess(process: Process, timeoutMs: Int, via: String): CommandResult {
    val out = StreamCollector(process.inputStream)
    val err = StreamCollector(process.errorStream)
    out.start()
    err.start()
    return try {
        val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            CommandResult(-1, out.text(), "timeout after ${timeoutMs}ms", via)
        } else {
            out.join(250)
            err.join(250)
            CommandResult(process.exitValue(), out.text(), err.text(), via)
        }
    } catch (t: Throwable) {
        CommandResult(-1, out.text(), t.toString(), via)
    } finally {
        try {
            process.destroy()
        } catch (_: Throwable) {
        }
    }
}

internal class StreamCollector(private val stream: InputStream) {
    private val sb = StringBuilder(4096)
    @Volatile
    private var done = false

    fun start(): Thread = Thread {
        try {
            val reader = stream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                sb.append(line).append('\n')
            }
        } catch (_: Throwable) {
        } finally {
            done = true
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun join(ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        while (!done && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    fun text(): String = synchronized(sb) { sb.toString() }
}

/**
 * Выполняет команды от UID самого приложения.
 *
 * Чего это НЕ даёт: `dumpsys` требует android.permission.DUMP, поэтому
 * `media.audio_flinger` и `SurfaceFlinger --latency` отсюда вернут
 * "Permission denial". Это ожидаемо и не маскируется: команды, требующие
 * привилегий, всегда проверяют [CommandResult.denied] и показывают N/A.
 *
 * Зато sysfs/procfs и `getprop` читаются отсюда полноценно.
 */
class DirectRunner : CommandRunner {
    override val name: String = "app"
    override val available: Boolean = true

    override fun run(cmd: List<String>, timeoutMs: Int): CommandResult = try {
        execProcess(ProcessBuilder(cmd).redirectErrorStream(false).start(), timeoutMs, name)
    } catch (t: Throwable) {
        CommandResult(-1, "", t.toString(), name)
    }
}
