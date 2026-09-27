package com.sj0404.snapboost.core

import android.content.Context
import android.content.SharedPreferences

/** Настройки приложения. Обёртка над SharedPreferences без лишних зависимостей. */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("snapboost", Context.MODE_PRIVATE)

    enum class OverlayMode { OFF, MINIMAL, FULL }

    var overlayMode: OverlayMode
        get() = runCatching {
            OverlayMode.valueOf(prefs.getString(KEY_MODE, OverlayMode.FULL.name) ?: OverlayMode.FULL.name)
        }.getOrDefault(OverlayMode.FULL)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /**
     * Интервал между циклами измерений. Это и есть реальная частота
     * обновления HUD: чаще опрашивать смысла нет, данные всё равно не меняются
     * быстрее, а лишние пробуждения сами съедают кадры игры.
     */
    var sampleIntervalMs: Int
        get() = prefs.getInt(KEY_INTERVAL, 1000).coerceIn(1000, 5000)
        set(value) = prefs.edit().putInt(KEY_INTERVAL, value.coerceIn(1000, 5000)).apply()

    var monitorEnabled: Boolean
        get() = prefs.getBoolean(KEY_MONITOR, true)
        set(value) = prefs.edit().putBoolean(KEY_MONITOR, value).apply()

    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_BOOT, false)
        set(value) = prefs.edit().putBoolean(KEY_BOOT, value).apply()

    /** Пакет, если пользователь указал игру вручную (когда автоопределение не работает). */
    var manualGamePackage: String?
        get() = prefs.getString(KEY_MANUAL_GAME, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_MANUAL_GAME, value).apply()

    var opacity: Float
        get() = prefs.getFloat(KEY_ALPHA, 0.72f).coerceIn(0.2f, 1f)
        set(value) = prefs.edit().putFloat(KEY_ALPHA, value.coerceIn(0.2f, 1f)).apply()

    var overlayX: Int
        get() = prefs.getInt(KEY_X, 0)
        set(value) = prefs.edit().putInt(KEY_X, value).apply()

    var overlayY: Int
        get() = prefs.getInt(KEY_Y, 0)
        set(value) = prefs.edit().putInt(KEY_Y, value).apply()

    var overlayPositioned: Boolean
        get() = prefs.getBoolean(KEY_POS, false)
        set(value) = prefs.edit().putBoolean(KEY_POS, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_ON, value).apply()

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(l)

    private companion object {
        const val KEY_MODE = "overlay_mode"
        const val KEY_INTERVAL = "sample_interval"
        const val KEY_MONITOR = "monitor_enabled"
        const val KEY_BOOT = "start_on_boot"
        const val KEY_MANUAL_GAME = "manual_game"
        const val KEY_ALPHA = "overlay_alpha"
        const val KEY_X = "overlay_x"
        const val KEY_Y = "overlay_y"
        const val KEY_POS = "overlay_positioned"
        const val KEY_KEEP_ON = "keep_screen_on"
    }
}
