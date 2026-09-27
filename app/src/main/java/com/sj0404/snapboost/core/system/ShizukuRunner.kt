package com.sj0404.snapboost.core.system

import android.os.Build
import android.util.Log

/**
 * Мост к Shizuku: процесс, запущенный через него, получает UID shell (2000),
 * а значит реальный доступ к `dumpsys` и к `settings put`.
 *
 * Это единственный способ получить настоящие FPS (SurfaceFlinger --latency) и
 * счётчик audio underrun (media.audio_flinger) без root. Без Shizuku эти
 * метрики остаются N/A — приложение не будет их выдумывать.
 *
 * Обращение к Shizuku идёт строго через рефлексию: если библиотека или само
 * приложение Shizuku отсутствует, класс не загрузится, и мост молча
 * деградирует в "недоступен", вместо того чтобы ронять приложение.
 */
class ShizukuRunner : CommandRunner {

    override val name: String = "shizuku"

    override val available: Boolean
        get() {
            if (!Build.SUPPORTED) return false
            return try {
                val cls = Class.forName("dev.rikka.shizuku.Shizuku")
                val ping = cls.getMethod("pingBinder")
                (ping.invoke(null) as? Boolean) == true
            } catch (_: Throwable) {
                false
            }
        }

    override fun run(cmd: List<String>, timeoutMs: Int): CommandResult {
        val remote = spawnRemote(cmd) ?: return CommandResult(
            -1, "", "Shizuku недоступен (не установлен, не запущен или нет разрешения)", name
        )
        return execProcess(remote, timeoutMs, name)
    }

    private fun spawnRemote(cmd: List<String>): Process? = try {
        val cls = Class.forName("dev.rikka.shizuku.Shizuku")
        val newProcess = cls.methods.firstOrNull { m ->
            m.name == "newProcess" && m.parameterTypes.size == 3 &&
                m.parameterTypes[0] == Array<String>::class.java
        } ?: return null
        @Suppress("UNCHECKED_CAST")
        newProcess.invoke(null, cmd.toTypedArray(), null, null) as? Process
    } catch (t: Throwable) {
        Log.w(TAG, "Shizuku newProcess failed: ${t.message}")
        null
    }

    private companion object {
        const val TAG = "SnapBoost/Shizuku"
    }
}
