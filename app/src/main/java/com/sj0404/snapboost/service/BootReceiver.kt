package com.sj0404.snapboost.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.sj0404.snapboost.app

/**
 * Автозапуск мониторинга после загрузки.
 *
 * На Android 12+ запуск foreground-сервиса из фона ограничен, поэтому старт
 * выполняется в try/catch: если система запретит, приложение просто не
 * стартует автоматически, а пользователь запустит его вручную. Исключение
 * наружу не пробрасывается, чтобы ресивер не падал.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!context.app.settings.startOnBoot) return

        try {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        } catch (t: Throwable) {
            Log.w("SnapBoost/Boot", "Автозапуск запрещён системой: ${t.message}")
        }
    }
}
