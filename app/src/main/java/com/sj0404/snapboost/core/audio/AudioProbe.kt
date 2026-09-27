package com.sj0404.snapboost.core.audio

import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import kotlin.math.max
import kotlin.math.min

/**
 * Активный самотест аудиотракта: создаёт AudioTrack с минимальным буфером
 * (тем же, что выбирает игра) и наблюдает, голодает ли он.
 *
 * Принцип проверки: трек сам сообщает, сколько кадров реально выведено
 * (framePosition из getTimestamp). Если число записанных кадров устойчиво
 * опережает число выведенных, буфер заполнен — всё в порядке. Если разница
 * падает почти к нулю, система не успевает наполнять буфер, и именно это
 * слышно как «рваный звук».
 *
 * Запускается только по явной команде пользователя, длится несколько секунд и
 * гарантированно освобождает ресурсы: постоянно работающий трек забирал бы
 * аудио-фокус у игры и сам провоцировал артефакты.
 */
object AudioProbe {

    data class Result(
        val sampleRate: Int,
        val bufferBytes: Int,
        val bufferMs: Float,
        /** Минимальное число кадров, оставшихся в буфере за всё время теста. */
        val minInFlightFrames: Long,
        val bufferFrames: Int,
        /** Буфер опустошался — прямая причина щелчков. */
        val starved: Boolean,
        /** 99-й перцентиль времени блокирующего write(): всплески означают, что HAL ждал данных. */
        val writeStallP99Us: Long,
        val stalls: Int,
        val verdict: String
    )

    fun run(durationMs: Long = 4000L): Result {
        val rate = 48000
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBytes = try {
            AudioTrack.getMinBufferSize(rate, channelMask, encoding)
        } catch (t: Throwable) {
            return Result(rate, 0, 0f, 0, 0, false, 0, 0, "getMinBufferSize: ${t.message}")
        }
        if (minBytes <= 0) {
            return Result(rate, minBytes, 0f, 0, 0, false, 0, 0, "HAL не поддерживает формат $rate/$channelMask")
        }

        val channelCount = 2
        val bytesPerFrame = 2 * channelCount
        val bufferFrames = minBytes / bytesPerFrame

        // Тишина в PCM_16 little-endian: чанк пишется много раз за тест.
        val chunkFrames = 256
        val silence = java.nio.ByteBuffer
            .allocate(bytesPerFrame * chunkFrames)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(chunkFrames * channelCount) { silence.putShort(0) }
        val chunk = silence.array()

        var track: AudioTrack? = null
        var framesWritten = 0L
        var minInFlight = Long.MAX_VALUE
        var stallCount = 0
        val stalls = ArrayList<Long>(64)

        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(rate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                .build()

            track.play()

            val deadline = System.currentTimeMillis() + durationMs
            val ts = AudioTimestamp()
            while (System.currentTimeMillis() < deadline) {
                val t0 = System.nanoTime()
                val written = track.write(chunk, 0, chunk.size)
                val dtUs = (System.nanoTime() - t0) / 1000

                if (written <= 0) {
                    stallCount++
                } else {
                    if (dtUs > 5000) stallCount++ // write() ждал свободного места > 5 мс
                    stalls += dtUs
                    framesWritten += written / bytesPerFrame
                }

                // Сигнатура getTimestamp различается между версиями SDK: стабы
                // дают пересечённый тип Int & Boolean. Явно приводим к Any,
                // чтобы smart-cast работал в обеих версиях (Int-код 0 = успех
                // либо Boolean-признак успеха).
                val tsOk: Boolean = when (val raw: Any = track.getTimestamp(ts)) {
                    is Boolean -> raw
                    is Int -> raw == 0
                    else -> false
                }
                if (tsOk) {
                    val presented = ts.framePosition.toLong()
                    val inFlight = framesWritten - presented
                    minInFlight = min(minInFlight, inFlight)
                }
                if (minInFlight <= 0) break
            }

            val sorted = stalls.sorted()
            val p99 = if (sorted.isEmpty()) 0L else sorted[(sorted.size * 99 / 100).coerceAtMost(sorted.size - 1)]
            val minFlight = if (minInFlight == Long.MAX_VALUE) 0L else minInFlight
            val starved = minFlight <= max(1L, bufferFrames / 8L)

            val verdict = when {
                stallCount == 0 && !starved ->
                    "Буфер стабилен, HAL успевает вовремя. Если звук всё равно рвётся — причина в тракте игры или Bluetooth, а не в системе."
                starved ->
                    "Буфер голодал (всего $minFlight кадров в буфере при $bufferFrames ёмкости). Система не успевает наполнять аудио — это и есть источник щелчков."
                else ->
                    "HAL подтормаживает: $stallCount провалов записи, хвост задержки ${p99} мкс. Возможен конфликт с другими приложениями за аудио."
            }

            return Result(
                sampleRate = rate,
                bufferBytes = minBytes,
                bufferMs = if (rate > 0) minBytes.toFloat() * 1000f / (rate * bytesPerFrame) else 0f,
                minInFlightFrames = minFlight,
                bufferFrames = bufferFrames,
                starved = starved,
                writeStallP99Us = p99,
                stalls = stallCount,
                verdict = verdict
            )
        } catch (t: Throwable) {
            return Result(rate, minBytes, 0f, 0, bufferFrames, false, 0, 0, "Ошибка теста: ${t.message}")
        } finally {
            try {
                track?.pause()
                track?.flush()
                track?.stop()
            } catch (_: Throwable) {
            }
            try {
                track?.release()
            } catch (_: Throwable) {
            }
        }
    }
}
