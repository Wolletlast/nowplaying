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
    private fun startStreaming() {
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
                if (gen == my) thread = null
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

        fun requestAudio(on: Boolean) {
            wanted = on
            instance?.applyWanted()
        }
    }
}
