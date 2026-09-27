package com.sj0404.snapboost

import android.app.Application
import android.content.Context
import com.sj0404.snapboost.core.Diagnoser
import com.sj0404.snapboost.core.Settings
import com.sj0404.snapboost.core.Snapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Единое состояние приложения: сэмплер пишет, UI и оверлей читают.
 *
 * Сэмплер работает в отдельном потоке, поэтому состояние только читается
 * через StateFlow — гонок между потоками нет, а оверлей не трогает диск.
 */
class SnapBoostApp : Application() {

    val settings: Settings by lazy { Settings(this) }

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    private val _findings = MutableStateFlow<List<Diagnoser.Finding>>(emptyList())
    val findings: StateFlow<List<Diagnoser.Finding>> = _findings

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning

    fun publish(s: Snapshot) {
        _snapshot.value = s
        _findings.value = Diagnoser.analyze(s)
    }

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }
}

val Context.app: SnapBoostApp
    get() = applicationContext as SnapBoostApp
