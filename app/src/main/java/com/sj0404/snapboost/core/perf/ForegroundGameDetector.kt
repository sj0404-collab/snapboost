package com.sj0404.snapboost.core.perf

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock
import com.sj0404.snapboost.core.system.Privileged

/**
 * Определяет, какое приложение сейчас на экране.
 *
 * Три независимых источника, каждый дешевле предыдущего по точности:
 *  1. `dumpsys activity` — точно, но нужен shell (Shizuku).
 *  2. UsageStatsManager — события активности, требуют «Доступ к использованию».
 *  3. Ничего — тогда HUD честно пишет, что игра не определена.
 *
 * Никаких догадок по типу окна: неизвестный пакет остаётся неизвестным пакетом.
 */
class ForegroundGameDetector(private val context: Context) {

    @Volatile
    var manualPackage: String? = null

    private var lastDetected: String? = null
    private var lastDetectedAt = 0L

    /** Известные игры: пакет -> название. Нужны, чтобы отличить игру от мессенджера. */
    private val knownGames = linkedMapOf(
        "com.miHoYo.Yuanshen" to "Genshin Impact",
        "com.miHoYo.hkrpg" to "Honkai: Star Rail",
        "com.miHoYo.Nap" to "Genshin (beta)",
        "com.kurogame.mingchao" to "Wuthering Waves",
        "com.kurogame.wuwa" to "Wuthering Waves",
        "com.hoyoverse.nap" to "Genshin CN",
        "com.tencent.ig" to "PUBG Mobile",
        "com.vng.pubgmobile" to "PUBG Mobile",
        "com.dts.freefireth" to "Free Fire",
        "com.levelinfinite.hotta.gp" to "Girls' Frontline",
        "com.netease.g104na" to "Knives Out",
        "com.HoYoverse.lumine" to "Honkai Impact 3rd",
        "com.riotgames.league.wildrift" to "Wild Rift",
        "com.supercell.brawlstars" to "Brawl Stars",
        "com.supercell.clashofclans" to "Clash of Clans",
        "com.ea.gp.fifamobile" to "EA SPORTS FC",
        "com.activision.callofduty.shooter" to "COD Mobile",
        "jp.konami.pes.e" to "eFootball",
        "com.nexon.bluearchive" to "Blue Archive",
        "com.YoStar.AetherGazer" to "Aether Gazer",
        "com.dragonnest.dnz" to "Black Survival"
    )

    fun hasUsageAccess(): Boolean {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return false
        // Принцип проверки: при выданном доступе запрос проходит молча (даже если
        // событий нет), а без доступа Android бросает SecurityException.
        return try {
            val now = SystemClock.elapsedRealtime()
            usm.queryEvents(now - 1000L, now)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun detect(): String? {
        manualPackage?.let { return it }
        if (SystemClock.elapsedRealtime() - lastDetectedAt < 1500L) return lastDetected

        val pkg = fromDumpsys() ?: fromUsageStats()
        lastDetected = pkg
        lastDetectedAt = SystemClock.elapsedRealtime()
        return pkg
    }

    private fun fromDumpsys(): String? {
        val dump = Privileged.dumpsys("activity", listOf("activities"), ttlMs = 2000L) ?: return null
        val patterns = listOf(
            Regex("mResumedActivity:\\s*ActivityRecord\\{[^}]*?\\s([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/"),
            Regex("topResumedActivity=.*?\\s([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/")
        )
        for (p in patterns) {
            p.find(dump)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    /**
     * Восстанавливает передний план из лога событий активности:
     * пакет считается в фокусе, если он получил RESUMED и после этого не
     * получил PAUSED/STOPPED.
     */
    private fun fromUsageStats(): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = SystemClock.elapsedRealtime()
        val events = try {
            usm.queryEvents(now - 15_000L, now)
        } catch (_: Throwable) {
            return null
        }
        val e = UsageEvents.Event()
        var resumed: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.packageName == context.packageName) continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> resumed = e.packageName
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED ->
                    if (e.packageName == resumed) resumed = null
            }
        }
        return resumed
    }

    fun nameOf(pkg: String?): String = when {
        pkg == null -> "?"
        knownGames.containsKey(pkg) -> knownGames.getValue(pkg)
        else -> pkg
    }

    /** Известна ли эта игра. Для неизвестных пакетов возвращаем false, а не догадку. */
    fun isGame(pkg: String?): Boolean = pkg != null && knownGames.containsKey(pkg)

    fun knownGamePackages(): Map<String, String> = knownGames
}
