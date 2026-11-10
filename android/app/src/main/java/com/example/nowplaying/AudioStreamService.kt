package com.example.nowplaying

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import java.net.HttpURLConnection
import java.net.URL

/**
 * Передаёт звук телефона на ПК: перехватывает то, что играют приложения (Android 10+, нужно один раз
 * разрешить «запись экрана» - звук экрана не пишется, это только разрешение на перехват звука),
 * и отправляет его потоком на ПК (POST /audio-in). Передача идёт только когда ПК её просит:
 * когда в NowPlaying выбран источник «телефон».
 */
class AudioStreamService : Service() {

    private var projection: MediaProjection? = null

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var gen = 0

    /** Громкость «Медиа» на телефоне выключена на время передачи (звук должен играть только на ПК). */
    @Volatile
    private var muted = false

    private val am: AudioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        startForegroundCompat()
        val data: Intent? = intent?.getParcelableExtra("data")
        val code = intent?.getIntExtra("code", 0) ?: 0
        if (data != null) {
            try {
                projection?.stop()
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val p = mpm.getMediaProjection(code, data)
                // Android 14 требует зарегистрировать обратный вызов до начала захвата
                p.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        armed = false
                        stopStreaming()
                    }
                }, null)
                projection = p
                armed = true
                error = ""
            } catch (e: Exception) {
                armed = false
                error = e.message ?: "не удалось получить разрешение"
            }
        }
        applyWanted()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopStreaming()
        restoreLocal()
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        armed = false
        active = false
        instance = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("audio", "Передача звука на ПК", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "audio")
            .setContentTitle("NowPlaying")
            .setContentText("Звук телефона передаётся на ПК")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    @Synchronized
    fun applyWanted() {
        if (wanted && armed && thread == null) startStreaming()
        else if ((!wanted || !armed) && thread != null) stopStreaming()
    }

    @Synchronized
    private fun muteLocal() {
        if (muted || muteBroken) return
        try {
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (cur > 0) {
                getSharedPreferences("np_audio", Context.MODE_PRIVATE).edit().putInt("saved_vol", cur).apply()
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            }
            muted = true
            mutedNow = true
            muteNote = ""
        } catch (e: Exception) {
            muteNote = "не удалось выключить звук на телефоне: " + (e.message ?: "")
        }
    }

    @Synchronized
    private fun restoreLocal() {
        if (!muted) return
        muted = false
        mutedNow = false
        restoreSaved(this)
    }

    @Synchronized
    private fun startStreaming() {
        muteLocal()
        val my = ++gen
        val t = Thread { streamLoop(my) }
        t.isDaemon = true
        thread = t
        t.start()
    }

    @Synchronized
    private fun stopStreaming() {
        gen++
        thread?.interrupt()
        thread = null
        active = false
        restoreLocal()
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(p: MediaProjection): AudioRecord {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord.Builder()
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min, 32768))
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
    }

    private fun openRecord(p: MediaProjection): AudioRecord {
        if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("нужен Android 10 или новее")
        val r = createRecord(p)
        r.startRecording()
        return r
    }

    private fun streamLoop(my: Int) {
        var rec: AudioRecord? = null
        var silentMs = 0
        var heardWhileMuted = false
        try {
            while (gen == my) {
                val p = projection ?: break
                val r: AudioRecord = rec ?: openRecord(p).also { rec = it }
                val ip = Sender.target(this)
                if (ip.isEmpty()) {
                    Thread.sleep(1500)
                    continue
                }
                var conn: HttpURLConnection? = null
                try {
                    conn = URL("http://$ip:${Sender.PORT}/audio-in").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 3000
                    conn.setChunkedStreamingMode(0)
                    conn.setRequestProperty("Content-Type", "application/octet-stream")
                    val out = conn.outputStream
                    val buf = ByteArray(7680) // 40 мс звука
                    error = ""
                    active = true
                    while (gen == my) {
                        val n = r.read(buf, 0, buf.size)
                        if (n < 0) throw java.io.IOException("ошибка записи звука: $n")
                        if (n == 0) continue
                        out.write(buf, 0, n)
                        out.flush()
                        if (muted) {
                            var peak = 0
                            var i = 0
                            while (i + 1 < n) {
                                val v = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
                                val a = if (v < 0) -v else v
                                if (a > peak) peak = a
                                i += 8
                            }
                            if (peak > 0) {
                                silentMs = 0
                                heardWhileMuted = true
                            } else if (am.isMusicActive) {
                                silentMs += 40
                                if (!heardWhileMuted && silentMs >= 8000) {
                                    // на этом телефоне звук для передачи берётся уже с учётом громкости: вернуть громкость
                                    muteBroken = true
                                    muteNote = "Телефон отдаёт звук только при включенной громкости: громкость оставлена, звук идёт и на телефоне"
                                    restoreLocal()
                                }
                            } else {
                                silentMs = 0
                            }
                        }
                    }
                    out.close()
                } catch (e: Exception) {
                    if (gen == my) {
                        error = e.message ?: "нет связи с ПК"
                        Thread.sleep(1500)
                    }
                } finally {
                    active = false
                    try {
                        conn?.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            if (gen == my && error.isEmpty()) error = e.message ?: "ошибка"
        } finally {
            try {
                rec?.stop()
                rec?.release()
            } catch (_: Exception) {
            }
            synchronized(this) {
                if (gen == my) {
                    thread = null
                    restoreLocal()
                }
            }
            active = false
        }
    }

    companion object {
        const val RATE = 48000

        @Volatile
        var instance: AudioStreamService? = null

        /** Есть разрешение на перехват звука (выдаётся пользователем кнопкой в приложении). */
        @Volatile
        var armed = false

        /** ПК просит звук (выбран источник «телефон»). */
        @Volatile
        var wanted = false

        /** Звук сейчас уходит на ПК. */
        @Volatile
        var active = false

        @Volatile
        var error = ""

        /** Громкость «Медиа» на телефоне сейчас выключена на время передачи. */
        @Volatile
        var mutedNow = false

        /** Выяснилось, что при громкости 0 звук для передачи пропадает: глушение отключено. */
        @Volatile
        var muteBroken = false

        @Volatile
        var muteNote = ""

        /** Возвращает громкость «Медиа», если приложение выключило её и не успело вернуть (например, его закрыли). */
        fun restoreSaved(ctx: Context) {
            try {
                val prefs = ctx.getSharedPreferences("np_audio", Context.MODE_PRIVATE)
                val saved = prefs.getInt("saved_vol", 0)
                if (saved > 0) {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
                    }
                    prefs.edit().remove("saved_vol").apply()
                }
            } catch (_: Exception) {
            }
        }

        fun restoreIfStale(ctx: Context) {
            if (instance?.muted == true) return
            restoreSaved(ctx)
        }

        fun requestAudio(on: Boolean) {
            wanted = on
            instance?.applyWanted()
        }
    }
}
