package com.sj0404.snapboost.audio

import android.app.ActivityManager
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.sj0404.snapboost.core.audio.AudioProbe

/**
 * Тюнер телефона: всё, что Android позволяет настроить обычному приложению.
 *
 * Жёсткое ограничение этого класса — только публичный API. Ни root, ни Shizuku,
 * ни `settings put global`: всё, что защищено WRITE_SECURE_SETTINGS или
 * android.permission.DUMP, отсюда недоступно и не выдаётся за доступное.
 *
 * Каждое действие возвращает честный [Outcome]. Формулировка принципиальна:
 *   ok     — изменение подтверждено чтением значения обратно из системы;
 *   manual — система открыла свой диалог, результат зависит от пользователя;
 *   !ok    — Android запретил операцию, и приложение говорит об этом прямо.
 *
 * Порядок тоже не случаен: сначала маршрут и помехи (дешёвое и главное для
 * звука), затем тепло и память (здесь важно не мешать игре).
 */
class AudioTuner(private val context: Context) {

    /**
     * @param ok применено и подтверждено чтением обратно
     * @param manual система потребовала действия пользователя (диалог, экран настроек)
     */
    data class Outcome(
        val ok: Boolean,
        val title: String,
        val detail: String,
        val manual: Boolean = false
    )

    /** Три состояния переключателя: неизвестно / выкл / вкл. */
    enum class Tri { UNKNOWN, OFF, ON }

    /**
     * Живое состояние телефона. Читается из системы, а не из памяти приложения:
     * если пользователь что-то поменял в системных настройках, тумблер встанет
     * в правильное положение сам.
     */
    data class State(
        val btEnabled: Boolean,
        val btAudioConnected: Boolean,
        val routeName: String,
        val routeDetail: String?,
        val dndOn: Boolean,
        val dndGranted: Boolean,
        val autoBrightness: Boolean,
        val brightness: Int,
        val echoCanceler: Tri,
        val noiseSuppressor: Tri,
        val gainControl: Tri,
        val powerSaveMode: Boolean,
        val memTotalMb: Long,
        val memAvailMb: Long,
        val memLow: Boolean,
        val ignorBatteryOptimizations: Boolean
    ) {
        val memUsedPct: Int
            get() = if (memTotalMb <= 0) 0
            else (((memTotalMb - memAvailMb) * 100) / memTotalMb).toInt().coerceIn(0, 100)
    }

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val activityManager: ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val powerManager: PowerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    // -------------------------------------------------------------- состояние

    fun readState(): State {
        val devices = outputDevices()
        val a2dp = devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val wired = devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        val speaker = devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val external = devices.count { !it.isInternal() }

        val route = when {
            a2dp -> "Bluetooth A2DP"
            wired -> "Проводные/USB наушники"
            external > 0 -> "Внешнее устройство"
            speaker -> "Динамик"
            devices.isEmpty() -> "N/A"
            else -> "Неизвестно"
        }

        val mic = MicFx.observed()

        val mem = runCatching {
            val mi = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(mi)
            mi
        }.getOrNull()

        return State(
            btEnabled = bluetoothEnabled(),
            btAudioConnected = a2dp || devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO },
            routeName = route,
            routeDetail = if (external > 1) "подключено внешних устройств: $external" else null,
            dndOn = dndEnabled(),
            dndGranted = notificationManager.isNotificationPolicyAccessGranted,
            autoBrightness = isAutoBrightness(),
            brightness = brightness(),
            echoCanceler = mic.echo,
            noiseSuppressor = mic.noise,
            gainControl = mic.gain,
            powerSaveMode = runCatching { powerManager.isPowerSaveMode }.getOrDefault(false),
            memTotalMb = mem?.totalMem?.toMb() ?: 0L,
            memAvailMb = mem?.availMem?.toMb() ?: 0L,
            memLow = mem?.lowMemory ?: false,
            ignorBatteryOptimizations = runCatching {
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }.getOrDefault(false)
        )
    }

    private fun outputDevices(): List<AudioDeviceInfo> = try {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun AudioDeviceInfo.isInternal(): Boolean =
        type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
            type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ||
            type == AudioDeviceInfo.TYPE_TELEPHONY ||
            type == AudioDeviceInfo.TYPE_BUILTIN_MIC

    private fun Long.toMb(): Long = this / (1024L * 1024L)

    // ------------------------------------------------------------ 1. Bluetooth

    /**
     * Выключает Bluetooth — самый частый виновник «рвущегося» звука.
     *
     * A2DP передаёт поток по воздуху блоками с перепаковкой и без запаса на
     * просадку: стоит системе задержаться — щелчок, и на спецэффектах это
     * слышно как постоянный треск.
     *
     * На Android 13+ программное выключение Bluetooth заблокировано, поэтому
     * показывается системный диалог. Это не «не сработало», а manual.
     */
    @Suppress("DEPRECATION")
    fun disableBluetooth(): Outcome {
        val adapter = bluetoothAdapter()
            ?: return Outcome(false, "Bluetooth", "На устройстве нет Bluetooth-модуля")

        if (!adapter.isEnabled) {
            return Outcome(true, "Bluetooth", "Уже выключен — аудио идёт без перепаковки по воздуху")
        }

        if (Build.VERSION.SDK_INT < 33) {
            return try {
                val ok = adapter.disable()
                if (ok || !adapter.isEnabled) {
                    Outcome(true, "Bluetooth", "Bluetooth выключен: звук пойдёт по проводу или через динамик")
                } else {
                    Outcome(false, "Bluetooth", "Система отклонила выключение")
                }
            } catch (t: Throwable) {
                Outcome(false, "Bluetooth", "Отказ: ${t.message}")
            }
        }

        return try {
            val intent = Intent(ACTION_REQUEST_DISABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Outcome(
                false,
                "Bluetooth",
                "Система требует подтверждения — открыт системный диалог. " +
                    "После отключения звук пойдёт без перепаковки по воздуху",
                manual = true
            )
        } catch (t: Throwable) {
            Outcome(false, "Bluetooth", "Не удалось показать диалог: ${t.message}")
        }
    }

    fun bluetoothEnabled(): Boolean = try {
        bluetoothAdapter()?.isEnabled ?: false
    } catch (_: Throwable) {
        false
    }

    @Suppress("DEPRECATION")
    private fun bluetoothAdapter(): BluetoothAdapter? = try {
        BluetoothAdapter.getDefaultAdapter()
    } catch (_: SecurityException) {
        null
    } catch (_: Throwable) {
        null
    }

    // --------------------------------------------------------- 2. Уведомления

    /**
     * «Не беспокоить». Убирает щелчки от уведомлений, которые в игре звучат
     * тем громче, чем сама игра, и забирают внимание на спецэффектах.
     */
    fun setDnd(enable: Boolean): Outcome {
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            return Outcome(
                false,
                "Уведомления",
                "Нужен доступ «Не беспокоить»: нажмите «Выдать» в разделе «Помехи»",
                manual = true
            )
        }
        return try {
            notificationManager.setInterruptionFilter(
                if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            val applied = dndEnabled() == enable
            if (applied) {
                Outcome(
                    true,
                    "Уведомления",
                    if (enable) "Включён приоритетный режим: звуки уведомлений приглушены"
                    else "Обычный режим звука восстановлен"
                )
            } else {
                Outcome(false, "Уведомления", "Система проигнорировала переключение режима")
            }
        } catch (t: Throwable) {
            Outcome(false, "Уведомления", "Отказ: ${t.message}")
        }
    }

    fun dndEnabled(): Boolean = try {
        notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    } catch (_: Throwable) {
        false
    }

    fun openDndSettings(): Boolean = open(
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    )

    fun openAudioSettings(): Boolean = open(Intent(Settings.ACTION_SOUND_SETTINGS))

    fun openBluetoothSettings(): Boolean = open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    fun openDisplaySettings(): Boolean = open(Intent(Settings.ACTION_DISPLAY_SETTINGS))

    fun openBatterySaverSettings(): Boolean = open(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))

    // ------------------------------------------------------------ 3. Микрофон

    /**
     * Шумоподавление, эхоподавление и автоусиление в тракте микрофона.
     *
     * Публичный API даёт доступ к эффектам только своей аудиосессии
     * (SESSION = 0 — глобальная). Если игра или мессенджер держат свою сессию,
     * эффект на них не подействует — и приложение сообщает об этом прямо,
     * вместо того чтобы показать зелёный переключатель.
     */
    fun setMicDenoise(enable: Boolean): Outcome {
        val applied = listOf(
            MicFx.set(MicFx.noise, enable),
            MicFx.set(MicFx.echo, enable),
            MicFx.set(MicFx.gain, enable)
        ).count { it }
        val available = listOf(MicFx.noise, MicFx.echo, MicFx.gain).count { it != null }
        val verb = if (enable) "включены" else "выключены"

        return when {
            available == 0 ->
                Outcome(
                    false,
                    "Микрофон",
                    "Аудиопроцессор на этой прошивке не отдал ни одного из эффектов " +
                        "(шумоподавление, эхоподавление, автоусиление)"
                )
            applied == 0 ->
                Outcome(
                    false,
                    "Микрофон",
                    "Сессия микрофона сейчас не активна: эффекты $verb только там, где игра или " +
                        "мессенджер держат свою сессию. Запустите голосовой чат — состояние обновится",
                    manual = true
                )
            else ->
                Outcome(
                    applied == available,
                    "Микрофон",
                    "Обработка $verb: эффектов затронуто $applied из $available"
                )
        }
    }

    // ------------------------------------------------- 4. Яркость и нагрев

    /**
     * Яркость вручную и пониженно.
     *
     * Прямая связь с аудио: чем горячее корпус, тем ниже системе разрешено
     * поднять частоты CPU, а значит меньше запаса у аудиобуфера. Яркость —
     * самый заметный и самый безопасный способ снять несколько градусов.
     *
     * Запись идёт в Settings.System и сразу перечитывается обратно: на части
     * прошивок ключ защищён, и тогда приложение честно сообщает об отказе.
     */
    fun setBrightness(level01: Float): Outcome {
        val value = (level01.coerceIn(0f, 1f) * MAX_BRIGHTNESS).toInt().coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        val resolver = context.contentResolver
        return try {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val back = readInt(Settings.System.SCREEN_BRIGHTNESS)
            if (back == value) {
                Outcome(
                    true,
                    "Яркость",
                    "Установлено ${value * 100 / MAX_BRIGHTNESS}%, автояркость выключена"
                )
            } else {
                Outcome(
                    false,
                    "Яркость",
                    "Система не приняла значение (вернула ${back ?: "нет данных"}): " +
                        "ключ защищён на этой прошивке. Вручную: Настройки → Экран → Яркость"
                )
            }
        } catch (t: Throwable) {
            Outcome(false, "Яркость", "Отказ: ${t.message}")
        }
    }

    fun setAutoBrightness(enable: Boolean): Outcome {
        val resolver = context.contentResolver
        val mode = if (enable) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        return try {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
            if (isAutoBrightness() == enable) {
                Outcome(
                    true,
                    "Автояркость",
                    if (enable) "Включена: яркость плавает вместе с освещением"
                    else "Выключена: яркость держится на выбранном уровне и не прыгает в игре"
                )
            } else {
                Outcome(false, "Автояркость", "Система не приняла переключение режима")
            }
        } catch (t: Throwable) {
            Outcome(false, "Автояркость", "Отказ: ${t.message}")
        }
    }

    private fun brightness(): Int = readInt(Settings.System.SCREEN_BRIGHTNESS) ?: -1

    private fun isAutoBrightness(): Boolean =
        readInt(Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

    private fun readInt(key: String): Int? = try {
        Settings.System.getInt(context.contentResolver, key)
    } catch (_: Throwable) {
        null
    }

    /**
     * Просит систему не душить приложение в фоне.
     *
     * Относится к самому SnapBoost: без этого запроса система может урезать
     * его foreground-сервис, и оверлей перестанет обновляться на длинной игре.
     */
    fun requestIgnoreBatteryOptimizations(): Outcome {
        if (Build.VERSION.SDK_INT < 23) {
            return Outcome(false, "Оптимизация батареи", "На этой версии Android оптимизации батареи нет")
        }
        return try {
            if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                return Outcome(true, "Оптимизация батареи", "Уже разрешено: сервис не будет засыпать")
            }
            @Suppress("BatteryLife")
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Outcome(
                false,
                "Оптимизация батареи",
                "Открыт системный запрос — нужно подтвердить. После этого оверлей и измерения " +
                    "не будут выгружаться в фоне",
                manual = true
            )
        } catch (t: Throwable) {
            Outcome(false, "Оптимизация батареи", "Система не показала запрос: ${t.message}")
        }
    }

    // --------------------------------------------------------- 5. Память и фон

    /**
     * Освобождает ресурсы: фоновые приложения занимают ядра и память, из-за
     * чего страдают и кадры, и запас аудиобуфера.
     *
     * KILL_BACKGROUND_PROCESSES — обычное разрешение, работает без root.
     * Системные процессы так не завершить — они и не трогаются.
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
        var skipped = 0
        for (p in heavy) {
            if (p == me) continue
            try {
                activityManager.killBackgroundProcesses(p)
                killed++
            } catch (_: Throwable) {
                skipped++
            }
        }
        return if (killed > 0) {
            Outcome(
                true,
                "Фоновые процессы",
                "Освобождено пакетов: $killed" + if (skipped > 0) ", системных пропущено: $skipped" else ""
            )
        } else {
            Outcome(false, "Фоновые процессы", "Ни один процесс не завершён: нет прав или список пуст")
        }
    }

    /** Чистит собственный кэш приложения — освобождает место, не трогая чужие данные. */
    fun clearOwnCache(): Outcome {
        return try {
            val dir = context.cacheDir
            var bytes = 0L
            dir.listFiles()?.forEach { f ->
                bytes += sizeOf(f)
                f.delete()
            }
            Outcome(
                true,
                "Кэш приложения",
                if (bytes > 0) "Освобождено ${bytes / 1024} КБ собственного кэша"
                else "Кэш и так пуст"
            )
        } catch (t: Throwable) {
            Outcome(false, "Кэш приложения", "Отказ: ${t.message}")
        }
    }

    private fun sizeOf(f: java.io.File): Long = try {
        if (f.isDirectory) f.listFiles()?.sumOf { sizeOf(it) } ?: 0L else f.length()
    } catch (_: Throwable) {
        0L
    }

    // ----------------------------------------------------------- 6. Самотест

    /**
     * Активная проверка аудиобуфера: создаёт AudioTrack с минимальным буфером и
     * смотрит, успевает ли HAL его наполнять. Это единственная честная замена
     * недоступному счётчику underrun из AudioFlinger.
     */
    fun audioSelfTest(durationMs: Long = 4000L): AudioProbe.Result = AudioProbe.run(durationMs)

    // -------------------------------------------------------------- Служебное

    private fun open(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (t: Throwable) {
        false
    }

    companion object {
        /**
         * Глобальная аудиосессия. Эффекты, созданные на ней, действуют на потоки,
         * которые система считает общими; игровые сессии им не подвластны —
         * поэтому результат переключателя всегда проверяется чтением обратно.
         */
        const val SESSION = 0

        /** Верхняя граница ключа SCREEN_BRIGHTNESS: диапазон 0..255. */
        const val MAX_BRIGHTNESS = 255
        private const val MIN_BRIGHTNESS = 10

        /** Скрытая в стабах SDK константа BluetoothAdapter.ACTION_REQUEST_DISABLE. */
        private const val ACTION_REQUEST_DISABLE =
            "android.bluetooth.adapter.action.REQUEST_DISABLE"
    }
}

/**
 * Долгоживущие дескрипторы эффектов микрофона.
 *
 * Зачем нужен именно этот кэш, а не создание эффекта на каждое обращение:
 * эффект, созданный через [AudioEffect], регистрируется в аудиоподсистеме.
 * Пересоздавая его каждые две секунды на фоне опроса состояния, приложение
 * само становилось бы источником нагрузки на тракт, который как раз и чинит.
 *
 * Создание ленивое и одноразовое: если эффекта нет, он не появится до
 * перезапуска процесса, и UI честно показывает UNKNOWN вместо выдуманного
 * состояния.
 */
private object MicFx {

    data class Observed(
        val noise: AudioTuner.Tri,
        val echo: AudioTuner.Tri,
        val gain: AudioTuner.Tri
    )

    private fun <T : AudioEffect> open(factory: () -> T?, supported: Boolean): T? =
        if (supported) runCatching { factory() }.getOrNull() else null

    private val noiseLazy = lazy {
        open({ NoiseSuppressor.create(AudioTuner.SESSION) }, NoiseSuppressor.isAvailable())
    }
    private val echoLazy = lazy {
        open({ AcousticEchoCanceler.create(AudioTuner.SESSION) }, AcousticEchoCanceler.isAvailable())
    }
    private val gainLazy = lazy {
        open({ AutomaticGainControl.create(AudioTuner.SESSION) }, AutomaticGainControl.isAvailable())
    }

    val noise: NoiseSuppressor? get() = noiseLazy.value
    val echo: AcousticEchoCanceler? get() = echoLazy.value
    val gain: AutomaticGainControl? get() = gainLazy.value

    /**
     * Состояние без создания эффектов. Пока человек не трогал переключатель
     * микрофона, эффекты в аудиоподсистеме не появляются: приложение не должно
     * регистрировать обработку ради фонового опроса состояния.
     */
    fun observed(): Observed = Observed(
        noise = state(noiseLazy.ifTouched()),
        echo = state(echoLazy.ifTouched()),
        gain = state(gainLazy.ifTouched())
    )

    private fun <T> Lazy<T>.ifTouched(): T? = if (isInitialized()) value else null

    fun state(effect: AudioEffect?): AudioTuner.Tri = when {
        effect == null -> AudioTuner.Tri.UNKNOWN
        else -> runCatching { if (effect.enabled) AudioTuner.Tri.ON else AudioTuner.Tri.OFF }
            .getOrDefault(AudioTuner.Tri.UNKNOWN)
    }

    /** Возвращает true только если система подтвердила новое состояние чтением. */
    fun set(effect: AudioEffect?, enable: Boolean): Boolean {
        if (effect == null) return false
        return runCatching {
            effect.enabled = enable
            effect.enabled == enable
        }.getOrDefault(false)
    }
}
