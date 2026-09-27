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
import com.sj0404.snapboost.audio.AudioTuner
import com.sj0404.snapboost.audio.TuningProfiles
import com.sj0404.snapboost.audio.TuningRunner
import com.sj0404.snapboost.core.Diagnoser
import com.sj0404.snapboost.core.Settings
import com.sj0404.snapboost.core.Snapshot
import com.sj0404.snapboost.core.audio.AudioProbe
import com.sj0404.snapboost.core.perf.ForegroundGameDetector
import com.sj0404.snapboost.core.system.GpuReader
import com.sj0404.snapboost.service.MonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Экран приложения: сначала тюнер, потом (по кнопке) оверлей и диагностика.
 *
 * Порядок экранов отражает то, зачем человек открывает приложение. Оверлей с
 * метриками — не главное: он включается осознанно, одной кнопкой, и по
 * умолчанию выключен, чтобы не появляться поверх чужой игры сам.
 */
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
    val tuner = remember { AudioTuner(ctx) }
    val runner = remember { TuningRunner(tuner, settings) }
    val detector = remember { ForegroundGameDetector(ctx) }

    var state by remember { mutableStateOf(tuner.readState()) }
    var overlayGranted by remember { mutableStateOf(AndroidSettings.canDrawOverlays(ctx)) }
    var usageAccess by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var lastOutcome by remember { mutableStateOf<AudioTuner.Outcome?>(null) }
    var report by remember { mutableStateOf<TuningRunner.Report?>(null) }
    var probe by remember { mutableStateOf<AudioProbe.Result?>(null) }
    var busy by remember { mutableStateOf(false) }

    /**
     * Состояние телефона опрашивается циклически: часть переключателей
     * меняется системой мимо приложения (яркость в шторке, отключение
     * Bluetooth с панели), и тумблер должен встать в реальное положение, а не
     * показывать то, что человек нажимал полчаса назад.
     */
    LaunchedEffect(Unit) {
        while (true) {
            state = tuner.readState()
            overlayGranted = AndroidSettings.canDrawOverlays(ctx)
            usageAccess = detector.hasUsageAccess()
            delay(2000)
        }
    }

    /** Любое действие завершается одним и тем же жестом: отчёт + свежий срез. */
    fun runTuner(block: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch(Dispatchers.IO) {
            block()
            state = tuner.readState()
            busy = false
        }
    }

    fun showOutcome(o: AudioTuner.Outcome) {
        lastOutcome = o
    }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Header()

            ProfileCard(
                busy = busy,
                lastProfileId = settings.lastProfileId,
                onApply = { profile ->
                    runTuner {
                        val r = runner.run(profile)
                        report = r
                        probe = r.probe
                    }
                }
            )

            report?.let { ProfileReportCard(it) }

            RouteCard(
                state = state,
                busy = busy,
                onDisableBt = { runTuner { showOutcome(tuner.disableBluetooth()) } },
                onOpenAudio = { runTuner { showOutcome(openResult(tuner.openAudioSettings(), "Настройки звука")) } },
                onOpenBt = { runTuner { showOutcome(openResult(tuner.openBluetoothSettings(), "Настройки Bluetooth")) } }
            )

            NoiseCard(
                state = state,
                busy = busy,
                onDnd = { enable -> runTuner { showOutcome(tuner.setDnd(enable)) } },
                onGrantDnd = { runTuner { showOutcome(openResult(tuner.openDndSettings(), "Доступ «Не беспокоить»")) } },
                onKillBackground = { runTuner { showOutcome(tuner.killHeavyBackground()) } },
                onSelfTest = {
                    runTuner {
                        val r = tuner.audioSelfTest()
                        probe = r
                        showOutcome(
                            AudioTuner.Outcome(
                                ok = !r.starved,
                                title = "Самотест аудиобуфера",
                                detail = r.verdict
                            )
                        )
                    }
                }
            )

            probe?.let { ProbeCard(it) }

            MicCard(
                state = state,
                busy = busy,
                onToggle = { enable -> runTuner { showOutcome(tuner.setMicDenoise(enable)) } }
            )

            HeatCard(
                state = state,
                busy = busy,
                onBrightness = { level -> runTuner { showOutcome(tuner.setBrightness(level)) } },
                onAutoBrightness = { enable -> runTuner { showOutcome(tuner.setAutoBrightness(enable)) } },
                onOpenBattery = { runTuner { showOutcome(openResult(tuner.openBatterySaverSettings(), "Экономия заряда")) } },
                onIgnoreOptimizations = { runTuner { showOutcome(tuner.requestIgnoreBatteryOptimizations()) } },
                onClearCache = { runTuner { showOutcome(tuner.clearOwnCache()) } }
            )

            HudCard(
                settings = settings,
                running = running,
                overlayGranted = overlayGranted,
                state = state,
                busy = busy,
                onGrantOverlay = { open(ctx, overlayPermissionIntent(ctx)) },
                onStartHud = {
                    settings.overlayMode = Settings.OverlayMode.FULL
                    ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
                },
                onStopHud = {
                    settings.overlayMode = Settings.OverlayMode.OFF
                    ctx.startService(
                        Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_STOP)
                    )
                },
                onStartOnBoot = { enable -> settings.startOnBoot = enable }
            )

            DiagnosticsCard(
                open = showDiagnostics,
                snap = snap,
                findings = findings,
                running = running,
                usageAccess = usageAccess,
                onToggle = { showDiagnostics = !showDiagnostics }
            )

            lastOutcome?.let { OutcomeCard(it) }

            Spacer(Modifier.height(24.dp))
            Text(
                "Тюнер работает только через публичный API Android: без root и без внешних сервисов. " +
                    "Всё, что защищено системными правами (глобальные анимации, системный DSP, " +
                    "игровой режим), приложение не меняет — оно честно пишет «N/A».",
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

/** Открытие экрана настроек — тоже результат: об отсутствии экрана тоже честно. */
private fun openResult(opened: Boolean, what: String) = AudioTuner.Outcome(
    ok = opened,
    title = what,
    detail = if (opened) "Открыт системный экран настроек"
    else "Система не открыла экран «$what» на этой прошивке"
)

@Composable
private fun darkCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF16202A))) {
        Column(Modifier.padding(14.dp), content = { content() })
    }
}

@Composable
private fun Header() {
    val platform = remember { GpuReader.platform() }
    val soc = remember { GpuReader.gpuModelName() }
    Column {
        Text("SnapBoost", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "Тюнер звука и системы · $platform · ${soc ?: "GPU не определён"}",
            fontSize = 12.sp,
            color = Color(0xFF8FA3B0)
        )
    }
}

// ----------------------------------------------------------------- пресеты

@Composable
private fun ProfileCard(
    busy: Boolean,
    lastProfileId: String?,
    onApply: (TuningProfiles.Profile) -> Unit
) {
    darkCard {
        Text("Профиль в один тап", fontWeight = FontWeight.Bold)
        Text(
            "Набор изменений, применяемых по порядку. Каждый шаг подтверждается чтением " +
                "значения обратно из системы — зелёная галочка означает «система приняла», " +
                "а не «приложение попросило».",
            fontSize = 11.sp,
            color = Color(0xFF8FA3B0)
        )
        Spacer(Modifier.height(8.dp))
        for (p in TuningProfiles.all) {
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.title + if (p.id == lastProfileId) "  · применён" else "",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (p.id == lastProfileId) Color(0xFF7BE495) else Color(0xFFE8EEF2)
                        )
                        Text(p.summary, fontSize = 11.sp, color = Color(0xFF8FA3B0))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !busy,
                        onClick = { onApply(p) },
                        colors = if (p.id == TuningProfiles.RESET.id) {
                            ButtonDefaults.buttonColors(containerColor = Color(0xFF7A2E2E))
                        } else {
                            ButtonDefaults.buttonColors()
                        }
                    ) { Text(if (busy) "…" else "Применить", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun ProfileReportCard(r: TuningRunner.Report) {
    darkCard {
        Text("Отчёт: ${r.profile.title}", fontWeight = FontWeight.Bold)
        Text(
            r.headline(),
            fontSize = 12.sp,
            color = if (r.failed == 0) Color(0xFF7BE495) else Color(0xFFF2C14E)
        )
        Spacer(Modifier.height(4.dp))
        r.results.forEach { s ->
            val color = when {
                s.outcome.ok -> Color(0xFF7BE495)
                s.outcome.manual -> Color(0xFFF2C14E)
                else -> Color(0xFFF16A5A)
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    (if (s.outcome.ok) "✓ " else if (s.outcome.manual) "? " else "✗ ") + s.step.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
                Text(s.outcome.detail, fontSize = 11.sp, color = Color(0xFFC8D4DC))
            }
        }
    }
}

// ------------------------------------------------------------------ секции

@Composable
private fun RouteCard(
    state: AudioTuner.State,
    busy: Boolean,
    onDisableBt: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenBt: () -> Unit
) {
    darkCard {
        Text("Маршрут вывода", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        InfoRow("Сейчас вывод:", state.routeName, Color(0xFFE8EEF2))
        state.routeDetail?.let { InfoRow("Устройств:", it, Color(0xFFF2C14E)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bluetooth", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        state.btAudioConnected ->
                            "A2DP-маршрут активен: поток перепаковывается по воздуху, " +
                                "запас на просадку нулевой"
                        state.btEnabled -> "Включён, но аудио через него не идёт"
                        else -> "Выключен: аудио идёт напрямую, без перепаковки"
                    },
                    fontSize = 11.sp,
                    color = if (state.btAudioConnected) Color(0xFFF16A5A) else Color(0xFFC8D4DC)
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(enabled = !busy && state.btEnabled, onClick = onDisableBt) {
                Text(if (state.btEnabled) "Выключить" else "Уже выкл", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenAudio) { Text("Настройки звука", fontSize = 12.sp) }
            OutlinedButton(onClick = onOpenBt) { Text("Bluetooth", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Маршрут вывода чужой игры обычному приложению неподвластен: единственный честный " +
                "способ убрать A2DP из тракта — выключить Bluetooth или снять наушники.",
            fontSize = 11.sp,
            color = Color(0xFF8FA3B0)
        )
    }
}

@Composable
private fun NoiseCard(
    state: AudioTuner.State,
    busy: Boolean,
    onDnd: (Boolean) -> Unit,
    onGrantDnd: () -> Unit,
    onKillBackground: () -> Unit,
    onSelfTest: () -> Unit
) {
    darkCard {
        Text("Помехи и проверка", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        ToggleRow(
            title = "Не беспокоить",
            subtitle = when {
                state.dndOn -> "Активно: уведомления не перебивают игру"
                state.dndGranted -> "Выключено"
                else -> "Нужен доступ к политике уведомлений"
            },
            checked = state.dndOn,
            enabled = state.dndGranted && !busy
        ) { onDnd(it) }
        if (!state.dndGranted) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onGrantDnd) { Text("Выдать доступ", fontSize = 12.sp) }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = onKillBackground) {
                Text("Освободить фон", fontSize = 12.sp)
            }
            OutlinedButton(enabled = !busy, onClick = onSelfTest) {
                Text("Самотест буфера", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Самотест создаёт AudioTrack с минимальным буфером и смотрит, успевает ли HAL его " +
                "наполнять. Это единственная честная замена счётчику underrun из AudioFlinger, " +
                "который закрыт android.permission.DUMP.",
            fontSize = 11.sp,
            color = Color(0xFF8FA3B0)
        )
    }
}

@Composable
private fun MicCard(
    state: AudioTuner.State,
    busy: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val ns = state.noiseSuppressor
    val aec = state.echoCanceler
    val agc = state.gainControl
    val any = ns != AudioTuner.Tri.UNKNOWN || aec != AudioTuner.Tri.UNKNOWN || agc != AudioTuner.Tri.UNKNOWN
    val allOn = ns == AudioTuner.Tri.ON && aec == AudioTuner.Tri.ON && agc == AudioTuner.Tri.ON

    darkCard {
        Text("Микрофон", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        ToggleRow(
            title = "Шумоподавление и эхоподавление",
            subtitle = when {
                !any ->
                    "Аудиопроцессор не отдал сессию: эффекты действуют только пока активна запись " +
                        "(голосовой чат). Сейчас тумблер недоступен."
                allOn -> "Включено: шумоподавление, эхоподавление, автоусиление"
                else -> "Частично включено — состояние каждого эффекта показано ниже"
            },
            checked = allOn,
            enabled = any && !busy
        ) { onToggle(it) }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EffectChip("Шум", ns)
            EffectChip("Эхо", aec)
            EffectChip("Усиление", agc)
        }
    }
}

@Composable
private fun EffectChip(name: String, tri: AudioTuner.Tri) {
    val (label, color) = when (tri) {
        AudioTuner.Tri.ON -> "вкл" to Color(0xFF7BE495)
        AudioTuner.Tri.OFF -> "выкл" to Color(0xFFE8EEF2)
        AudioTuner.Tri.UNKNOWN -> "N/A" to Color(0xFF8FA3B0)
    }
    Text("$name: $label", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = color)
}

@Composable
private fun HeatCard(
    state: AudioTuner.State,
    busy: Boolean,
    onBrightness: (Float) -> Unit,
    onAutoBrightness: (Boolean) -> Unit,
    onOpenBattery: () -> Unit,
    onIgnoreOptimizations: () -> Unit,
    onClearCache: () -> Unit
) {
    val level = if (state.brightness >= 0) state.brightness.toFloat() / AudioTuner.MAX_BRIGHTNESS else -1f
    darkCard {
        Text("Нагрев и ресурсы", fontWeight = FontWeight.Bold)
        Text(
            "Троттлинг поднимает frametime, а от frametime страдает и аудиобуфер. " +
                "Чем холоднее корпус, тем выше потолок частот CPU.",
            fontSize = 11.sp,
            color = Color(0xFF8FA3B0)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (level >= 0) "Яркость: ${"%.0f".format(level * 100)}%" else "Яркость: N/A",
            fontSize = 12.sp
        )
        if (level >= 0f) {
            Slider(
                value = level,
                onValueChange = { onBrightness(it) },
                valueRange = 0.05f..1f,
                enabled = !busy
            )
        }
        ToggleRow(
            title = "Автояркость",
            subtitle = if (state.autoBrightness)
                "Включена: яркость плавает, нагрев и батарея страдают"
            else
                "Выключена: яркость держится на выбранном уровне",
            checked = state.autoBrightness,
            enabled = !busy
        ) { onAutoBrightness(it) }
        InfoRow(
            "Экономия заряда:",
            if (state.powerSaveMode) "включена — частоты урезаны системой" else "выключена",
            if (state.powerSaveMode) Color(0xFFF2C14E) else Color(0xFF7BE495)
        )
        InfoRow(
            "Память:",
            "${state.memUsedPct}% занято (свободно ${state.memAvailMb} из ${state.memTotalMb} МБ)" +
                if (state.memLow) " · системе не хватает" else "",
            if (state.memLow) Color(0xFFF16A5A) else Color(0xFFE8EEF2)
        )
        InfoRow(
            "Выгрузка в фоне:",
            if (state.ignorBatteryOptimizations) "разрешена" else "система может усыпить сервис",
            if (state.ignorBatteryOptimizations) Color(0xFF7BE495) else Color(0xFFF2C14E)
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = onOpenBattery) {
                Text("Экономия заряда", fontSize = 12.sp)
            }
            OutlinedButton(enabled = !busy, onClick = onIgnoreOptimizations) {
                Text("Не выгружать", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(enabled = !busy, onClick = onClearCache) {
            Text("Очистить свой кэш", fontSize = 12.sp)
        }
    }
}

// ------------------------------------------------------- оверлей и диагностика

@Composable
private fun HudCard(
    settings: Settings,
    running: Boolean,
    overlayGranted: Boolean,
    state: AudioTuner.State,
    busy: Boolean,
    onGrantOverlay: () -> Unit,
    onStartHud: () -> Unit,
    onStopHud: () -> Unit,
    onStartOnBoot: (Boolean) -> Unit
) {
    val on = settings.overlayMode != Settings.OverlayMode.OFF
    var mode by remember { mutableStateOf(settings.overlayMode) }
    var alpha by remember { mutableStateOf(settings.opacity) }
    var interval by remember { mutableStateOf(settings.sampleIntervalMs) }
    var boot by remember { mutableStateOf(settings.startOnBoot) }

    darkCard {
        Text("Оверлей с метриками", fontWeight = FontWeight.Bold)
        Text(
            "По умолчанию выключен: приложение начинает с тюнера, а измерения включаются " +
                "явно. Оверлей считает реальные частоты, температуру, PSI и нагрузку GPU " +
                "из sysfs и procfs.",
            fontSize = 11.sp,
            color = Color(0xFF8FA3B0)
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        on && running -> "Оверлей работает"
                        on && !running -> "Включён, сервис не запущен"
                        else -> "Выключен"
                    },
                    fontSize = 13.sp,
                    color = if (on && running) Color(0xFF7BE495) else Color(0xFFE8EEF2)
                )
                Text(
                    if (on) "Панель можно перетащить: тап — режим, долгий тап — открыть приложение"
                    else "Метрики появляются поверх игры только после включения",
                    fontSize = 11.sp,
                    color = Color(0xFF8FA3B0)
                )
            }
            Spacer(Modifier.width(8.dp))
            if (on) {
                Button(
                    enabled = !busy,
                    onClick = onStopHud,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7A2E2E))
                ) { Text("Выключить", fontSize = 12.sp) }
            } else {
                Button(enabled = !busy && overlayGranted, onClick = onStartHud) {
                    Text("Включить", fontSize = 12.sp)
                }
            }
        }
        if (!overlayGranted) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Нужно разрешение «Отображение поверх других приложений» — без него оверлей " +
                    "не появится.",
                fontSize = 11.sp,
                color = Color(0xFFF2C14E)
            )
            OutlinedButton(onClick = onGrantOverlay) { Text("Выдать", fontSize = 12.sp) }
        }
        if (on) {
            Spacer(Modifier.height(8.dp))
            Text("Режим: ${modeLabel(mode)}", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in Settings.OverlayMode.entries) {
                    OutlinedButton(onClick = {
                        mode = m
                        settings.overlayMode = m
                    }) { Text(modeLabel(m), fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Частота замеров: ${interval / 1000} с", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (ms in listOf(1000, 2000, 3000, 5000)) {
                    OutlinedButton(onClick = {
                        interval = ms
                        settings.sampleIntervalMs = ms
                    }) { Text("${ms / 1000} с", fontSize = 12.sp) }
                }
            }
            Text(
                "Чаще опрашивать бессмысленно: пробуждения сами отнимают кадры у игры.",
                fontSize = 11.sp,
                color = Color(0xFF8FA3B0)
            )
            Spacer(Modifier.height(4.dp))
            Text("Прозрачность: ${"%.0f".format(alpha * 100)}%", fontSize = 12.sp)
            Slider(
                value = alpha,
                onValueChange = { alpha = it },
                onValueChangeFinished = { settings.opacity = alpha },
                valueRange = 0.2f..1f
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Запускать оверлей после перезагрузки", fontSize = 13.sp)
                    Text(
                        if (state.ignorBatteryOptimizations)
                            "Система разрешает фоновую работу — автозапуск сработает"
                        else
                            "Без разрешения на выгрузку в фоне автозапуск может не разрешиться",
                        fontSize = 11.sp,
                        color = Color(0xFF8FA3B0)
                    )
                }
                Switch(checked = boot, onCheckedChange = {
                    boot = it
                    onStartOnBoot(it)
                })
            }
        }
    }
}

private fun modeLabel(m: Settings.OverlayMode) = when (m) {
    Settings.OverlayMode.OFF -> "Выкл"
    Settings.OverlayMode.MINIMAL -> "Мин"
    Settings.OverlayMode.FULL -> "Полный"
}

@Composable
private fun DiagnosticsCard(
    open: Boolean,
    snap: com.sj0404.snapboost.core.Snapshot,
    findings: List<Diagnoser.Finding>,
    running: Boolean,
    usageAccess: Boolean,
    onToggle: () -> Unit
) {
    darkCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Диагностика", fontWeight = FontWeight.Bold)
                Text(
                    if (running) "Сервис измерений запущен" else "Сервис измерений остановлен",
                    fontSize = 12.sp,
                    color = Color(0xFFC8D4DC)
                )
                Text(
                    "Живые метрики и разбор причин. Реальный FPS, jank и счётчик underrun " +
                        "закрыты android.permission.DUMP и показываются как N/A — это ожидаемо.",
                    fontSize = 11.sp,
                    color = Color(0xFF8FA3B0)
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onToggle) {
                Text(if (open) "Скрыть" else "Показать", fontSize = 12.sp)
            }
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            if (!usageAccess) {
                Text(
                    "Без «Доступа к использованию» игра не определяется, но частоты, температуры " +
                        "и PSI всё равно читаются.",
                    fontSize = 11.sp,
                    color = Color(0xFFF2C14E)
                )
                Spacer(Modifier.height(4.dp))
            }
            MetricsBlock(snap)
            Spacer(Modifier.height(8.dp))
            if (findings.isEmpty()) {
                Text("Данных пока недостаточно — включите оверлей и запустите игру.", fontSize = 12.sp)
            }
            findings.forEach { f ->
                val color = when (f.severity) {
                    Diagnoser.Severity.CRITICAL -> Color(0xFFF16A5A)
                    Diagnoser.Severity.WARN -> Color(0xFFF2C14E)
                    Diagnoser.Severity.INFO -> Color(0xFF8FA3B0)
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(f.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
                    Text(f.detail, fontSize = 11.sp, color = Color(0xFFC8D4DC))
                    f.action?.let { action ->
                        Text(
                            "Что можно сделать: ${actionHint(action)}",
                            fontSize = 11.sp,
                            color = Color(0xFF7BE495)
                        )
                    }
                }
            }
        }
    }
}

private fun actionHint(a: Diagnoser.Action): String = when (a) {
    Diagnoser.Action.WIRED_AUDIO -> "выключить Bluetooth в разделе «Маршрут вывода»"
    Diagnoser.Action.SILENCE_NOTIFICATIONS -> "включить «Не беспокоить» в разделе «Помехи»"
    Diagnoser.Action.KILL_BACKGROUND -> "нажать «Освободить фон»"
    Diagnoser.Action.COOL_DOWN -> "приглушить экран в разделе «Нагрев», снять чехол, отключить зарядку"
    Diagnoser.Action.OPEN_AUDIO_SETTINGS -> "отключить объёмный звук в настройках звука"
}

@Composable
private fun MetricsBlock(s: Snapshot) {
    Column(Modifier.fillMaxWidth()) {
        Row {
            Metric("FPS", metric(s.fps.valid && s.fps.fps != null) { "%.0f".format(s.fps.fps) }, s.fps.valid)
            Spacer(Modifier.width(18.dp))
            Metric("Jank", s.fps.jankPct?.let { "$it%" } ?: "N/A")
            Spacer(Modifier.width(18.dp))
            Metric("Дисплей", s.fps.displayHz?.let { "$it Гц" } ?: "N/A")
        }
        Row {
            Metric("CPU", s.cpu.curMhz?.let { "$it МГц" } ?: "N/A")
            Spacer(Modifier.width(18.dp))
            Metric(
                "Троттлинг",
                when (s.cpu.throttled) {
                    true -> "ДА"
                    false -> "нет"
                    null -> "N/A"
                }
            )
            Spacer(Modifier.width(18.dp))
            Metric("GPU", s.gpu.busyPct?.let { "$it%" } ?: "N/A")
        }
        Row {
            Metric("Темп.", s.thermal.hottest?.let { "%.1f °C".format(it) } ?: "N/A")
            Spacer(Modifier.width(18.dp))
            Metric("Простои CPU", s.pressure.cpuSomeAvg10?.let { "%.0f%%".format(it) } ?: "N/A")
            Spacer(Modifier.width(18.dp))
            Metric("Выход", s.audio.routeName)
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    valid: Boolean = true,
    color: Color = if (valid) Color(0xFFE8EEF2) else Color(0xFF8FA3B0)
) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(label, fontSize = 11.sp, color = Color(0xFF8FA3B0))
        Text(value, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = color)
    }
}

private inline fun metric(valid: Boolean, block: () -> String): String = if (valid) block() else "N/A"

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 12.sp, color = Color(0xFF8FA3B0), modifier = Modifier.width(120.dp))
        Text(value, fontSize = 12.sp, color = valueColor)
    }
}

/**
 * Тумблер с честным состоянием. Если систему нельзя изменить (нет доступа,
 * эффект недоступен), переключатель не просто гаснет, а подписан причиной:
 * иначе человек решит, что настройка уже применена.
 */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    danger: Boolean = false,
    onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                fontSize = 11.sp,
                color = if (!enabled) Color(0xFF8FA3B0) else if (danger) Color(0xFFF16A5A) else Color(0xFFC8D4DC)
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ProbeCard(r: AudioProbe.Result) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101820))) {
        Column(Modifier.padding(14.dp)) {
            Text("Самотест аудиобуфера", fontWeight = FontWeight.Bold)
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
private fun OutcomeCard(o: AudioTuner.Outcome) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101820))) {
        Column(Modifier.padding(14.dp)) {
            Text(
                o.title,
                fontWeight = FontWeight.Bold,
                color = when {
                    o.ok -> Color(0xFF7BE495)
                    o.manual -> Color(0xFFF2C14E)
                    else -> Color(0xFFF16A5A)
                }
            )
            Text(o.detail, fontSize = 12.sp)
        }
    }
}
