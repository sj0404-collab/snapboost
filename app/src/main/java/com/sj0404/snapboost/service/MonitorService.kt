package com.sj0404.snapboost.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.sj0404.snapboost.R
import com.sj0404.snapboost.app
import com.sj0404.snapboost.core.Diagnoser
import com.sj0404.snapboost.core.FpsState
import com.sj0404.snapboost.core.Settings
import com.sj0404.snapboost.core.Snapshot
import com.sj0404.snapboost.core.audio.AudioReader
import com.sj0404.snapboost.core.perf.ForegroundGameDetector
import com.sj0404.snapboost.core.perf.FpsTracker
import com.sj0404.snapboost.core.system.CpuReader
import com.sj0404.snapboost.core.system.GpuReader
import com.sj0404.snapboost.core.system.Privileged
import com.sj0404.snapboost.core.system.PressureReader
import com.sj0404.snapboost.core.system.ProcReader
import com.sj0404.snapboost.core.system.ThermalReader
import com.sj0404.snapboost.overlay.HudFormatter
import com.sj0404.snapboost.overlay.HudView
import com.sj0404.snapboost.ui.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.provider.Settings as AndroidSettings

/**
 * Единственный сервис приложения: он и снимает метрики, и держит оверлей.
 *
 * Разделение на два сервиса усложнило бы уведомление и жизненный цикл без
 * выигрыша, поэтому измерение и отрисовка живут здесь, а разделены потоками:
 * сэмплинг идёт на одном фоновом потоке, оверлей трогается только через
 * главный поток.
 */
class MonitorService : Service() {

    private lateinit var settings: Settings
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "snapboost-sampler").apply { isDaemon = true }
    }

    private var windowManager: WindowManager? = null
    private var hudView: HudView? = null

    private lateinit var audioReader: AudioReader
    private lateinit var detector: ForegroundGameDetector
    private lateinit var fpsTracker: FpsTracker
    private lateinit var powerManager: PowerManager

    private var lastFps = FpsState()
    private var tick = 0
    private var currentSnapshot = Snapshot()
    private var currentGameName = "?"

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // sample_interval читается фоновым потоком, поэтому нужен снимок
        // значения на каждом цикле, а не обработчик изменения.
        if (key == "overlay_mode" || key == "overlay_alpha" || key == "keep_screen_on") {
            mainHandler.post { syncOverlay() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = app.settings
        settings.registerListener(prefListener)
        detector = ForegroundGameDetector(this)
        detector.manualPackage = settings.manualGamePackage
        fpsTracker = FpsTracker()
        audioReader = AudioReader(this)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        app.setServiceRunning(true)
        if (running.compareAndSet(false, true)) {
            executor.execute { loop() }
        }
        mainHandler.post { syncOverlay() }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        settings.unregisterListener(prefListener)
        executor.shutdownNow()
        runCatching { fpsTracker.stop() }
        mainHandler.post { removeOverlay() }
        app.setServiceRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- сэмплинг

    private fun loop() {
        // Частота дисплея — единственный источник, доступный без привилегий,
        // поэтому меряем один раз на старте, вне цикла.
        runCatching { fpsTracker.measureDisplayHz() }
            .onFailure { Log.w(TAG, "displayHz: ${it.message}") }

        val basePeriod = settings.sampleIntervalMs.toLong()
        while (running.get()) {
            val started = System.currentTimeMillis()
            try {
                collectOnce()
            } catch (t: Throwable) {
                Log.w(TAG, "sample failed: ${t.message}")
            }
            val spent = System.currentTimeMillis() - started
            val sleep = (basePeriod - spent).coerceAtLeast(200L)
            try {
                Thread.sleep(sleep)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun collectOnce() {
        val gamePkg = detector.detect()
        val gameName = detector.nameOf(gamePkg)
        if (gameName != currentGameName) {
            currentGameName = gameName
            ProcReader.reset()
            lastFps = FpsState()
        }

        val cpu = CpuReader.read()
        val gpu = GpuReader.read()
        val thermal = ThermalReader.read(powerManager)
        val pressure = PressureReader.read()
        val process = ProcReader.read(gamePkg)
        val audio = audioReader.read()

        // Окно FPS требует сна на всю длину окна, поэтому меряем через тик:
        // иначе цикл опроса съест сам измеряемый интервал.
        tick++
        if (tick % 2 == 1) {
            lastFps = try {
                fpsTracker.sample(gamePkg, windowMs = 1000L)
            } catch (t: Throwable) {
                Log.w(TAG, "fps: ${t.message}")
                lastFps
            }
        }

        val snapshot = Snapshot(
            cpu = cpu,
            gpu = gpu,
            thermal = thermal,
            pressure = pressure,
            process = process,
            audio = audio,
            fps = lastFps,
            privilegeSource = Privileged.sourceName,
            hasShell = Privileged.hasShell
        )
        currentSnapshot = snapshot
        app.publish(snapshot)

        val lines = HudFormatter.format(snapshot, settings.overlayMode, gameName)
        mainHandler.post {
            hudView?.submit(lines, settings.opacity)
            updateNotification(snapshot)
        }
    }

    // ---------------------------------------------------------------- оверлей

    private fun syncOverlay() {
        val mode = settings.overlayMode
        if (mode == Settings.OverlayMode.OFF) {
            removeOverlay()
            return
        }
        if (!AndroidSettings.canDrawOverlays(this)) {
            // Без разрешения мониторинг продолжает работать, HUD просто не
            // показывается. Молча пропадать нельзя — сообщаем в уведомление.
            removeOverlay()
            return
        }
        if (hudView == null) addOverlay() else applyLayoutParams()
    }

    private fun addOverlay() {
        val wm = windowManager ?: return
        val view = HudView(this)
        view.onTap = {
            settings.overlayMode = when (settings.overlayMode) {
                Settings.OverlayMode.FULL -> Settings.OverlayMode.MINIMAL
                Settings.OverlayMode.MINIMAL -> Settings.OverlayMode.OFF
                Settings.OverlayMode.OFF -> Settings.OverlayMode.FULL
            }
        }
        view.onLongPress = {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        view.onDrag = { dx, dy -> applyDrag(dx, dy) }

        try {
            wm.addView(view, layoutParams())
            hudView = view
            view.submit(
                HudFormatter.format(currentSnapshot, settings.overlayMode, currentGameName),
                settings.opacity
            )
        } catch (t: Throwable) {
            Log.w(TAG, "addView failed: ${t.message}")
            hudView = null
        }
    }

    private fun removeOverlay() {
        val view = hudView ?: return
        hudView = null
        runCatching { windowManager?.removeView(view) }
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(),
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        if (settings.overlayPositioned) {
            p.x = settings.overlayX
            p.y = settings.overlayY
        } else {
            p.x = (12 * resources.displayMetrics.density).toInt()
            p.y = (120 * resources.displayMetrics.density).toInt()
        }
        return p
    }

    private fun baseFlags(): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (settings.keepScreenOn) f = f or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        return f
    }

    private fun applyLayoutParams() {
        val view = hudView ?: return
        runCatching { windowManager?.updateViewLayout(view, layoutParams()) }
    }

    private fun applyDrag(dx: Int, dy: Int) {
        val view = hudView ?: return
        val p = layoutParams()
        p.x += dx
        p.y += dy
        runCatching { windowManager?.updateViewLayout(view, p) }
        settings.overlayX = p.x
        settings.overlayY = p.y
        settings.overlayPositioned = true
    }

    // ------------------------------------------------------------ уведомление

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_desc) }
        )
    }

    private fun startForegroundCompat() {
        val n = buildNotification(currentSnapshot)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (t: Throwable) {
            // На части прошивок specialUse запрещён политикой вендора,
            // сервис обязан продолжить работу, поэтому деградируем.
            Log.w(TAG, "startForeground(specialUse) failed: ${t.message}")
            runCatching { startForeground(NOTIF_ID, n) }
                .onFailure { Log.e(TAG, "startForeground failed: ${it.message}") }
        }
    }

    private fun buildNotification(s: Snapshot): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val sound = when {
            s.audio.underrunsPerSec != null && s.audio.underrunsPerSec!! >= 2f -> "аудио теряет пакеты"
            else -> "аудио в норме"
        }
        val fpsTxt = if (s.fps.valid && s.fps.fps != null) "%.0f FPS".format(s.fps.fps) else "FPS недоступен"
        val tempTxt = s.thermal.hottest?.let { "%.0f°C".format(it) } ?: "темп. n/a"
        val crit = Diagnoser.analyze(s).count { it.severity == Diagnoser.Severity.CRITICAL }

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("$fpsTxt · $tempTxt · $sound" + if (crit > 0) " · проблем: $crit" else "")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat),
                    getString(R.string.action_stop),
                    stop
                ).build()
            )
            .build()
    }

    private fun updateNotification(s: Snapshot) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(s))
        }
    }

    companion object {
        const val TAG = "SnapBoost/Service"
        const val ACTION_STOP = "com.sj0404.snapboost.STOP"
        private const val CHANNEL = "snapboost_monitor"
        private const val NOTIF_ID = 1001
    }
}
