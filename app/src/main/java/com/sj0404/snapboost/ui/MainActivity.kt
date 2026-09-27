package com.sj0404.snapboost.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sj0404.snapboost.app
import com.sj0404.snapboost.audio.SoundFixer
import com.sj0404.snapboost.core.Diagnoser
import com.sj0404.snapboost.core.Settings
import com.sj0404.snapboost.core.Snapshot
import com.sj0404.snapboost.core.audio.AudioProbe
import com.sj0404.snapboost.core.perf.ForegroundGameDetector
import com.sj0404.snapboost.core.system.GpuReader
import com.sj0404.snapboost.core.system.Privileged
import com.sj0404.snapboost.service.MonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { AppUi(this) }
    }
}

@Composable
private fun AppUi(activity: MainActivity) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val app = ctx.app
    val settings = remember { app.settings }

    val snap by app.snapshot.collectAsStateWithLifecycle()
    val findings by app.findings.collectAsStateWithLifecycle()
    val running by app.serviceRunning.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val fixer = remember { SoundFixer(ctx) }
    val detector = remember { ForegroundGameDetector(ctx) }

    var overlayGranted by remember { mutableStateOf(AndroidSettings.canDrawOverlays(ctx)) }
    var usageAccess by remember { mutableStateOf(false) }
    var dndAccess by remember { mutableStateOf(false) }
    var shellAccess by remember { mutableStateOf(Privileged.shizukuAvailable()) }
    var lastOutcome by remember { mutableStateOf<SoundFixer.Outcome?>(null) }
    var probeResult by remember { mutableStateOf<AudioProbe.Result?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Факты о доступах опрашиваются циклически: пользователь мог выдать
    // разрешения в системных настройках, пока приложение было в фоне.
    LaunchedEffect(Unit) {
        while (true) {
            overlayGranted = AndroidSettings.canDrawOverlays(ctx)
            usageAccess = detector.hasUsageAccess()
            dndAccess = fixer.dndEnabled()
            shellAccess = Privileged.shizukuAvailable()
            delay(1500)
        }
    }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Header(snap)

            ServiceCard(running) {
                val i = Intent(ctx, MonitorService::class.java)
                if (running) {
                    i.action = MonitorService.ACTION_STOP
                    ctx.startService(i)
                } else {
                    ContextCompat.startForegroundService(ctx, i)
                }
            }

            AccessCard(
                overlayGranted, shellAccess, usageAccess, dndAccess
            ) { open(ctx, it) }

            MetricsCard(snap)

            FindingsCard(findings, busy, scope, fixer, detector) { lastOutcome = it }

            ActionsCard(busy) { action ->
                busy = true
                scope.launch(Dispatchers.IO) {
                    lastOutcome = runAction(action, fixer, detector)
                    busy = false
                }
            } { probe ->
                busy = true
                scope.launch(Dispatchers.IO) {
                    probeResult = probe()
                    busy = false
                }
            }

            OverlaySettingsCard(settings)

            probeResult?.let { ProbeCard(it) }
            lastOutcome?.let { OutcomeCard(it) }

            Spacer(Modifier.height(24.dp))
            Text(
                "N/A означает, что источник закрыт для обычных приложений. " +
                    "Приложение не подставляет вымышленные значения метрик.",
                fontSize = 11.sp,
                color = Color(0xFF8FA3B0)
            )
        }
    }
}

private fun open(ctx: Context, intent: Intent) {
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun overlayPermissionIntent(ctx: Context) =
    Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION)
        .setData(Uri.parse("package:${ctx.packageName}"))

private fun shizukuIntent() =
    Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES")
        .setData(Uri.parse("package:dev.rikka.shizuku"))

@Composable
private fun darkCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF16202A))) {
        Column(Modifier.padding(14.dp), content = { content() })
    }
}

@Composable
private fun Header(s: Snapshot) {
    val platform = remember { GpuReader.platform() }
    val soc = remember { GpuReader.gpuModelName() }
    Column {
        Text("SnapBoost", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("$platform · ${soc ?: "GPU не определён"}", fontSize = 12.sp, color = Color(0xFF8FA3B0))
        Text(
            "Права: ${s.privilegeSource} · " +
                if (s.hasShell) "дампы SurfaceFlinger/AudioFlinger доступны"
                else "только sysfs и procfs",
            fontSize = 12.sp,
            color = if (s.hasShell) Color(0xFF7BE495) else Color(0xFFF2C14E)
        )
    }
}

@Composable
private fun ServiceCard(running: Boolean, onToggle: () -> Unit) {
    darkCard {
        Text("Мониторинг", fontWeight = FontWeight.Bold)
        Text(
            if (running) "Сервис работает" else "Сервис остановлен",
            fontSize = 13.sp
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onToggle,
            colors = if (running) {
                ButtonDefaults.buttonColors(containerColor = Color(0xFF7A2E2E))
            } else {
                ButtonDefaults.buttonColors()
            }
        ) { Text(if (running) "Остановить" else "Запустить") }
    }
}

@Composable
private fun AccessCard(
    overlay: Boolean,
    shell: Boolean,
    usage: Boolean,
    dnd: Boolean,
    open: (Intent) -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    darkCard {
        Text("Источники данных", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        AccessRow(
            "Оверлей поверх игр",
            if (overlay) "разрешён" else "нужно разрешение",
            overlay
        ) { open(overlayPermissionIntent(ctx)) }
        AccessRow(
            "Права shell (Shizuku)",
            if (shell) "активны: настоящие FPS, jank и счётчик потерь"
            else "нет: FPS, jank и underrun недоступны",
            shell
        ) { open(shizukuIntent()) }
        AccessRow(
            "Доступ к использованию",
            if (usage) "игра в фокусе определяется" else "нужен для определения игры",
            usage
        ) { open(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS)) }
        AccessRow(
            "Не беспокоить",
            if (dnd) "разрешён" else "нужен для приглушения уведомлений",
            dnd
        ) { open(Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
    }
}

@Composable
private fun AccessRow(title: String, state: String, ok: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                state,
                fontSize = 11.sp,
                color = if (ok) Color(0xFF7BE495) else Color(0xFFF2C14E)
            )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onClick) { Text(if (ok) "Есть" else "Выдать") }
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(label, fontSize = 11.sp, color = Color(0xFF8FA3B0))
        Text(value, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = color)
    }
}

@Composable
private fun MetricsCard(s: Snapshot) {
    val u = s.audio.underrunsPerSec
    darkCard {
        Text("Живые данные", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row {
            Metric(
                "FPS",
                if (s.fps.valid && s.fps.fps != null) "%.0f".format(s.fps.fps) else "N/A",
                if (s.fps.valid) Color(0xFF7BE495) else Color(0xFF8FA3B0)
            )
            Spacer(Modifier.width(18.dp))
            Metric("Jank", s.fps.jankPct?.let { "$it%" } ?: "N/A", Color(0xFFE8EEF2))
            Spacer(Modifier.width(18.dp))
            Metric("Дисплей", s.fps.displayHz?.let { "$it Гц" } ?: "N/A", Color(0xFFE8EEF2))
        }
        Row {
            Metric("CPU", s.cpu.curMhz?.let { "$it МГц" } ?: "N/A", Color(0xFFE8EEF2))
            Spacer(Modifier.width(18.dp))
            Metric(
                "Троттлинг CPU",
                when (s.cpu.throttled) {
                    true -> "ДА"
                    false -> "нет"
                    null -> "N/A"
                },
                when (s.cpu.throttled) {
                    true -> Color(0xFFF16A5A)
                    false -> Color(0xFF7BE495)
                    null -> Color(0xFF8FA3B0)
                }
            )
        }
        Row {
            Metric("GPU", s.gpu.busyPct?.let { "$it%" } ?: "N/A", Color(0xFFE8EEF2))
            Spacer(Modifier.width(18.dp))
            Metric("Темп.", s.thermal.hottest?.let { "%.1f °C".format(it) } ?: "N/A", Color(0xFFE8EEF2))
            Spacer(Modifier.width(18.dp))
            Metric(
                "Простои CPU",
                s.pressure.cpuSomeAvg10?.let { "%.0f%%".format(it) } ?: "N/A",
                Color(0xFFE8EEF2)
            )
        }
        Row {
            Metric(
                "Аудио",
                when {
                    u != null && u >= 2f -> "%.1f/с потерь".format(u)
                    u != null && u >= 0.5f -> "%.1f/с потерь".format(u)
                    u != null -> "OK"
                    else -> "нет счётчика"
                },
                when {
                    u != null && u >= 2f -> Color(0xFFF16A5A)
                    u != null && u >= 0.5f -> Color(0xFFF2C14E)
                    s.audio.underrunsTotal != null -> Color(0xFF7BE495)
                    else -> Color(0xFF8FA3B0)
                }
            )
            Spacer(Modifier.width(18.dp))
            Metric("Выход", s.audio.routeName, Color(0xFFE8EEF2))
        }
        s.audio.dspEvidence?.let {
            Text("DSP: $it", fontSize = 10.sp, color = Color(0xFFF2C14E), fontFamily = FontFamily.Monospace)
        }
        if (s.audio.focusHolders.isNotEmpty()) {
            Text("Фокус: ${s.audio.focusHolders.joinToString()}", fontSize = 10.sp, color = Color(0xFFF2C14E))
        }
    }
}

@Composable
private fun FindingsCard(
    findings: List<Diagnoser.Finding>,
    busy: Boolean,
    scope: CoroutineScope,
    fixer: SoundFixer,
    detector: ForegroundGameDetector,
    onOutcome: (SoundFixer.Outcome) -> Unit
) {
    darkCard {
        Text("Диагноз", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        if (findings.isEmpty()) {
            Text("Данных пока недостаточно — запустите сервис в игре.", fontSize = 12.sp)
        }
        findings.forEach { f ->
            val color = when (f.severity) {
                Diagnoser.Severity.CRITICAL -> Color(0xFFF16A5A)
                Diagnoser.Severity.WARN -> Color(0xFFF2C14E)
                Diagnoser.Severity.INFO -> Color(0xFF8FA3B0)
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(f.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
                Text(f.detail, fontSize = 12.sp, color = Color(0xFFC8D4DC))
                f.action?.let { action ->
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                onOutcome(runAction(action, fixer, detector))
                            }
                        }
                    ) { Text(actionLabel(action)) }
                }
            }
        }
    }
}

private fun actionLabel(a: Diagnoser.Action): String = when (a) {
    Diagnoser.Action.DISABLE_BT, Diagnoser.Action.WIRED_AUDIO -> "Выключить Bluetooth"
    Diagnoser.Action.DISABLE_SURROUND -> "Выключить объёмный звук"
    Diagnoser.Action.SILENCE_NOTIFICATIONS -> "Приглушить уведомления"
    Diagnoser.Action.KILL_BACKGROUND -> "Освободить фоновые процессы"
    Diagnoser.Action.DISABLE_ANIMATIONS -> "Выключить анимации"
    Diagnoser.Action.PERF_MODE -> "Запросить игровой режим"
    Diagnoser.Action.COOL_DOWN -> "Что делать с нагревом"
    Diagnoser.Action.SHIZUKU -> "Как получить права shell"
    Diagnoser.Action.RESTART_APP -> "Перезапустить"
}

private fun runAction(
    a: Diagnoser.Action,
    fixer: SoundFixer,
    detector: ForegroundGameDetector
): SoundFixer.Outcome = when (a) {
    Diagnoser.Action.DISABLE_BT, Diagnoser.Action.WIRED_AUDIO -> fixer.disableBluetooth()
    Diagnoser.Action.DISABLE_SURROUND -> fixer.disableSurround()
    Diagnoser.Action.SILENCE_NOTIFICATIONS -> fixer.silenceNotifications(true)
    Diagnoser.Action.KILL_BACKGROUND -> fixer.killHeavyBackground()
    Diagnoser.Action.DISABLE_ANIMATIONS -> fixer.disableAnimations(true)
    Diagnoser.Action.PERF_MODE -> fixer.setPerformanceMode(detector.detect())
    Diagnoser.Action.COOL_DOWN -> SoundFixer.Outcome(
        false,
        "Нагрев",
        "Снять тепловое ограничение программно нельзя: это защита от деградации SoC. " +
            "Что реально помогает: снять чехол, отключить зарядку на время игры, дать корпусу остыть, снизить яркость."
    )
    Diagnoser.Action.SHIZUKU -> SoundFixer.Outcome(
        false,
        "Права shell",
        "Установите Shizuku, запустите его через adb, затем вернитесь сюда и включите сервис. " +
            "Появятся настоящие FPS, jank и счётчик underrun."
    )
    Diagnoser.Action.RESTART_APP -> SoundFixer.Outcome(false, "Перезапуск", "Не требуется")
}

@Composable
private fun ActionsCard(
    busy: Boolean,
    onAction: (Diagnoser.Action) -> Unit,
    onProbe: (() -> AudioProbe.Result) -> Unit
) {
    darkCard {
        Text("Действия", fontWeight = FontWeight.Bold)
        Text(
            "Только штатные средства Android. Снятие тепловых лимитов и запись в cpufreq/KGSL " +
                "намеренно не реализованы: это не буст, а ускоренный износ процессора.",
            fontSize = 11.sp,
            color = Color(0xFF8FA3B0)
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { onAction(Diagnoser.Action.WIRED_AUDIO) }) {
                Text("Выкл. Bluetooth")
            }
            OutlinedButton(enabled = !busy, onClick = { onAction(Diagnoser.Action.DISABLE_SURROUND) }) {
                Text("Выкл. surround")
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { onAction(Diagnoser.Action.KILL_BACKGROUND) }) {
                Text("Фоновые")
            }
            OutlinedButton(enabled = !busy, onClick = { onAction(Diagnoser.Action.PERF_MODE) }) {
                Text("Игровой режим")
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { onAction(Diagnoser.Action.DISABLE_ANIMATIONS) }) {
                Text("Анимации")
            }
            Button(enabled = !busy, onClick = { onProbe({ AudioProbe.run(4000L) }) }) {
                Text("Самотест аудио")
            }
        }
    }
}

@Composable
private fun OverlaySettingsCard(settings: Settings) {
    var mode by remember { mutableStateOf(settings.overlayMode) }
    var alpha by remember { mutableStateOf(settings.opacity) }
    var keepOn by remember { mutableStateOf(settings.keepScreenOn) }
    var interval by remember { mutableStateOf(settings.sampleIntervalMs) }

    darkCard {
        Text("Оверлей", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Режим: ${modeLabel(mode)}", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in Settings.OverlayMode.entries) {
                OutlinedButton(
                    onClick = {
                        mode = m
                        settings.overlayMode = m
                    }
                ) { Text(modeLabel(m), fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("Интервал измерений: ${interval / 1000} с", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (ms in listOf(1000, 2000, 3000, 5000)) {
                OutlinedButton(
                    onClick = {
                        interval = ms
                        settings.sampleIntervalMs = ms
                    }
                ) { Text("${ms / 1000} с", fontSize = 12.sp) }
            }
        }
        Text(
            "HUD обновляется раз в цикл измерений. Чаще опрашивать бессмысленно: " +
                "пробуждения сами отнимают кадры у игры.",
            fontSize = 11.sp,
            color = Color(0xFF8FA3B0)
        )
        Spacer(Modifier.height(6.dp))
        Text("Прозрачность: ${"%.0f".format(alpha * 100)}%", fontSize = 12.sp)
        Slider(
            value = alpha,
            onValueChange = { alpha = it },
            onValueChangeFinished = { settings.opacity = alpha },
            valueRange = 0.2f..1f
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Не гасить экран", fontSize = 13.sp)
                Text(
                    "Пригодится для замеров, но расходует батарею",
                    fontSize = 11.sp,
                    color = Color(0xFF8FA3B0)
                )
            }
            Switch(
                checked = keepOn,
                onCheckedChange = {
                    keepOn = it
                    settings.keepScreenOn = it
                }
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Оверлей можно перетащить мышью/пальцем. Короткое нажатие меняет режим, " +
                "долгое открывает эту панель.",
            fontSize = 11.sp,
            color = Color(0xFF8FA3B0)
        )
    }
}

private fun modeLabel(m: Settings.OverlayMode) = when (m) {
    Settings.OverlayMode.OFF -> "Выкл"
    Settings.OverlayMode.MINIMAL -> "Мин"
    Settings.OverlayMode.FULL -> "Полный"
}

@Composable
private fun ProbeCard(r: AudioProbe.Result) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101820))) {
        Column(Modifier.padding(14.dp)) {
            Text("Результат самотеста", fontWeight = FontWeight.Bold)
            Text(
                "${r.sampleRate} Гц · буфер ${r.bufferBytes} Б (${"%.1f".format(r.bufferMs)} мс) · " +
                    "минимум в буфере ${r.minInFlightFrames} из ${r.bufferFrames} кадров",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                r.verdict,
                fontSize = 12.sp,
                color = if (r.starved) Color(0xFFF16A5A) else Color(0xFF7BE495)
            )
        }
    }
}

@Composable
private fun OutcomeCard(o: SoundFixer.Outcome) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101820))) {
        Column(Modifier.padding(14.dp)) {
            Text(
                o.title,
                fontWeight = FontWeight.Bold,
                color = if (o.ok) Color(0xFF7BE495) else Color(0xFFF2C14E)
            )
            Text(o.detail, fontSize = 12.sp)
        }
    }
}
