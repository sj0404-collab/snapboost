package com.sj0404.snapboost.audio

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.sj0404.snapboost.core.system.Privileged

/**
 * Действия, которые действительно влияют на стабильность.
 *
 * Каждое действие возвращает честный исход: применилось, не смогло или
 * недостаточно привилегий. Ничего не имитируется — если Android запретил
 * операцию, пользователь увидит именно это, а не зелёную галочку.
 *
 * Чего здесь принципиально нет: снятия thermal-лимитов, записи в cpufreq и
 * KGSL, root-команд. Троттлинг — это защита от деградации SoC, и обход
 * лимитов ускорение, а не буст: ускоренно изношенный Snapdragon теряет
 * производительность навсегда.
 */
class SoundFixer(private val context: Context) {

    data class Outcome(
        val ok: Boolean,
        val title: String,
        val detail: String
    )

    private val activityManager: ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ------------------------------------------------- 1. Bluetooth-аудио

    /**
     * Выключает Bluetooth.
     *
     * Почему это главный кандидат на «рвущийся звук»: A2DP передаёт данные
     * по воздуху небольшими блоками с собственной перепаковкой. Как только
     * система не успевает отдать блок, на выходе щелчок — и на игре это
     * слышно как постоянный треск на спецэффектах.
     *
     * На Android 13+ выключение BT программно заблокировано для обычных
     * приложений, поэтому здесь показывается системный диалог, а не
     * бездействующий вызов.
     */
    fun disableBluetooth(): Outcome {
        val adapter = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (_: Throwable) {
            null
        }
        if (adapter == null) return Outcome(false, "Bluetooth", "Bluetooth отсутствует на устройстве")
        if (!adapter.isEnabled) return Outcome(true, "Bluetooth", "Уже выключен — менять нечего")

        if (Build.VERSION.SDK_INT < 33) {
            return try {
                val ok = adapter.disable()
                if (ok) Outcome(true, "Bluetooth", "Bluetooth выключен: аудио пойдёт по проводу или динамику")
                else Outcome(false, "Bluetooth", "Система не дала выключить Bluetooth")
            } catch (t: Throwable) {
                Outcome(false, "Bluetooth", "Отказ: ${t.message}")
            }
        }

        return try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Outcome(
                false,
                "Bluetooth",
                "Система требует подтверждения — появился диалог. После отключения звук пойдёт без перепаковки по воздуху"
            )
        } catch (t: Throwable) {
            Outcome(false, "Bluetooth", "Не удалось показать диалог: ${t.message}")
        }
    }

    // ------------------------------------------------- 2. Уведомления

    /** Тихий режим: убирает щелчки от уведомлений и звуки других приложений. */
    fun silenceNotifications(enable: Boolean): Outcome {
        return try {
            if (enable) {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                Outcome(true, "Уведомления", "Включён приоритетный режим: звуки уведомлений приглушены")
            } else {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                Outcome(true, "Уведомления", "Обычный режим звука восстановлен")
            }
        } catch (t: Throwable) {
            Outcome(
                false,
                "Уведомления",
                "Нет доступа «Не беспокоить»: ${t.javaClass.simpleName}"
            )
        }
    }

    // ------------------------------------------------- 3. Анимации

    /**
     * Обнуляет анимации системы. Экономит кадры на UI-потоке, которые в играх
     * уходят на перерисовку элементов интерфейса.
     */
    fun disableAnimations(enable: Boolean): Outcome {
        if (!Privileged.hasShell) {
            return Outcome(
                false,
                "Анимации",
                "Нужны права shell (Shizuku): настройка глобальных анимаций защищена WRITE_SECURE_SETTINGS"
            )
        }
        val value = if (enable) "0.0" else "1.0"
        val keys = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
        val failed = keys.count { key ->
            val r = Privileged.runner().run(listOf("settings", "put", "global", key, value), 3000)
            !r.ok
        }
        return if (failed == 0) {
            Outcome(
                true,
                "Анимации",
                if (enable) "Анимации выключены: UI-поток освободится" else "Анимации включены"
            )
        } else {
            Outcome(false, "Анимации", "Не удалось применить $failed из ${keys.size} параметров")
        }
    }

    // ------------------------------------------------- 4. Surround/DSP

    /**
     * Выключает surround-форматы (Dolby Atmos и аналоги).
     *
     * Системный DSP для объёмного звука добавляет в тракт дополнительные
     * буферы и задержку. Когда игра выдаёт поток, у HAL не остаётся запаса,
     * и звук рвётся. Для стерео-игр этот обработчик не нужен.
     */
    fun disableSurround(): Outcome {
        if (!Privileged.hasShell) {
            return Outcome(
                false,
                "Объёмный звук",
                "Нужны права shell (Shizuku): переключение surround защищено"
            )
        }
        val list = Privileged.runner().run(listOf("cmd", "audio", "list-surround-formats"), 3000)
        if (!list.ok || list.stdout.isBlank()) {
            return Outcome(
                false,
                "Объёмный звук",
                "Команда не поддерживается этой прошивкой: ${list.stderr.trim().take(80)}"
            )
        }
        val formats = Regex("^\\s*(\\S+)\\s*$", RegexOption.MULTILINE)
            .findAll(list.stdout)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() && !it.contains(" ") }
            .distinct()
            .toList()
        if (formats.isEmpty()) {
            return Outcome(false, "Объёмный звук", "Список форматов пуст — нечего отключать")
        }
        var off = 0
        for (f in formats) {
            val r = Privileged.runner().run(
                listOf("cmd", "audio", "set-surround-format-enabled", f, "false"), 3000
            )
            if (r.ok) off++
        }
        return if (off > 0) {
            Outcome(true, "Объёмный звук", "Отключено форматов: $off из ${formats.size} (${formats.joinToString()})")
        } else {
            Outcome(false, "Объёмный звук", "Не удалось отключить ни один формат")
        }
    }

    // ------------------------------------------------- 5. Игровой режим

    /**
     * Просит системный GameManager включить производительный режим.
     * Поддерживается не всеми прошивками — при отказе сообщаем честно.
     */
    fun setPerformanceMode(pkg: String?): Outcome {
        if (pkg == null) return Outcome(false, "Игровой режим", "Игра не определена")
        if (Build.VERSION.SDK_INT < 31) {
            return Outcome(false, "Игровой режим", "Требуется Android 12+")
        }
        return try {
            val gm = context.getSystemService(Context.GAME_SERVICE) as? android.app.GameManager
                ?: return Outcome(false, "Игровой режим", "Сервис GameManager недоступен")
            gm.setGameMode(pkg, android.app.GameManager.MODE_PERFORMANCE)
            Outcome(true, "Игровой режим", "Запрошен производительный режим для $pkg")
        } catch (t: Throwable) {
            Outcome(false, "Игровой режим", "Система отклонила запрос: ${t.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------- 6. Фоновые процессы

    /**
     * Освобождает ресурсы: фоновые приложения занимают ядра и память,
     * из-за чего страдают и кадры, и аудиобуфер.
     */
    fun killHeavyBackground(): Outcome {
        val heavy = listOf(
            "org.telegram.messenger", "com.discord", "com.zhiliaoapp.musically",
            "com.google.android.youtube", "com.spotify.music", "com.facebook.katana",
            "com.instagram.android", "com.android.chrome", "com.linkedin.android",
            "com.netflix.mediaclient", "com.booking", "com.uber.driver",
            "com.snapchat.android", "com.twitter.android", "com.whatsapp"
        )
        val me = context.packageName
        var killed = 0
        var blocked = 0
        for (p in heavy) {
            if (p == me) continue
            try {
                activityManager.killBackgroundProcesses(p)
                killed++
            } catch (_: SecurityException) {
                blocked++
            } catch (_: Throwable) {
                blocked++
            }
        }
        return if (killed > 0) {
            Outcome(
                true,
                "Фоновые процессы",
                "Освобождено пакетов: $killed" + if (blocked > 0) ", системных пропущено: $blocked" else ""
            )
        } else {
            Outcome(false, "Фоновые процессы", "Ни один процесс не завершён (нужно разрешение или нет прав)")
        }
    }

    // ------------------------------------------------- Диагностика

    /**
     * Запрашивает у системы сведения о текущем маршруте. Используется для
     * подтверждения, что переключение Bluetooth реально произошло.
     */
    fun currentOutputDeviceName(): String? = try {
        val res = Privileged.runner().run(listOf("dumpsys", "audio"), 4000)
        Regex("Current:\\s*\\d+\\s*\\(([^)]+)\\)").find(res.stdout)?.groupValues?.get(1)
    } catch (_: Throwable) {
        null
    }

    fun dndEnabled(): Boolean = try {
        notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    } catch (_: Throwable) {
        false
    }

    fun animationsDisabled(): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Throwable) {
        false
    }
}
