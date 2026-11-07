package com.example.nowplaying

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Следит за активными медиасессиями (Яндекс Музыка, Spotify, YouTube Music и т.д.),
 * отправляет трек, позицию и обложку на ПК и выполняет команды с ПК (пауза, next/prev, перемотка, лайк).
 * Доступ к медиасессиям Android даёт только службе доступа к уведомлениям.
 */
class MediaListenerService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var controllers: List<MediaController> = emptyList()

    // дедупликация отправок
    private var lastKey: String? = null
    private var lastPos = 0L
    private var lastAt = 0L
    private var lastPlaying = false

    // кэш обложки
    private var lastBitmap: Bitmap? = null
    private var cachedArt: String? = null
    private var artSent: String? = null

    // приём команд
    @Volatile private var generation = 0
    @Volatile private var polling = false

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push(false)
        override fun onPlaybackStateChanged(state: PlaybackState?) = push(false)
        override fun onSessionDestroyed() = push(false)
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list ?: emptyList()) }

    /** Раз в 5 секунд подтверждаем состояние: ПК видит, что телефон на связи, и корректирует позицию. */
    private val heartbeat = object : Runnable {
        override fun run() {
            push(true)
            handler.postDelayed(this, 5000)
        }
    }

    override fun onListenerConnected() {
        val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm = manager
        val cn = ComponentName(this, MediaListenerService::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, cn)
            attach(manager.getActiveSessions(cn))
        } catch (_: SecurityException) {
            // нет доступа к уведомлениям
        }
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, 5000)
        startPolling()
    }

    override fun onListenerDisconnected() {
        stopPolling()
        handler.removeCallbacks(heartbeat)
        try {
            msm?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = emptyList()
    }

    private fun attach(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = list
        controllers.forEach { it.registerCallback(callback) }
        push(false)
    }

    // ---------- команды с ПК ----------
    private fun startPolling() {
        if (polling) stopPolling()
        polling = true
        val myGen = ++generation
        Thread {
            while (polling && generation == myGen) {
                val cmds = Sender.pollOnce(this)
                if (cmds == null) {
                    try {
                        Thread.sleep(3000)
                    } catch (_: InterruptedException) {
                    }
                    continue
                }
                for (cmd in cmds) handler.post { handleCommand(cmd) }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopPolling() {
        polling = false
        generation++
    }

    private fun handleCommand(cmd: JSONObject) {
        val c = MediaUtil.pick(controllers) ?: return
        MediaUtil.execute(c, cmd.optString("cmd"), cmd.optLong("pos"))
    }

    // ---------- отправка состояния ----------
    private fun push(force: Boolean) {
        val c = MediaUtil.pick(controllers)
        val md = c?.metadata
        val st = c?.playbackState
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val json = JSONObject()
        val key: String
        var pos = 0L
        var playing = false

        if (c == null || md == null || title.isBlank()) {
            json.put("title", "")
            json.put("playing", false)
            key = "idle"
        } else {
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: ""
            val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
            val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
            playing = st?.state == PlaybackState.STATE_PLAYING
            pos = MediaUtil.position(st)
            val like = MediaUtil.likeAction(st)
            val liked = MediaUtil.isLiked(like)
            val canSeek = st != null && (st.actions and PlaybackState.ACTION_SEEK_TO) != 0L
            val art = artFor(
                md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            )

            json.put("title", title)
            json.put("artist", artist)
            json.put("album", album)
            json.put("app", appLabel(c.packageName))
            json.put("playing", playing)
            json.put("position", pos)
            json.put("duration", duration)
            json.put("speed", (st?.playbackSpeed ?: 1f).toDouble())
            json.put("liked", liked)
            json.put("canLike", like != null)
            json.put("canSeek", canSeek)
            if (art != null) {
                if (art == artSent) json.put("artSame", true) else json.put("art", art)
            }
            key = listOf(
                title, artist, album, duration, playing, liked, like != null, canSeek,
                c.packageName, art?.hashCode() ?: 0
            ).joinToString("|")
        }

        val now = SystemClock.elapsedRealtime()
        val expected = if (lastPlaying) lastPos + (now - lastAt) else lastPos
        if (!force && key == lastKey && abs(pos - expected) < 1500) return
        lastKey = key
        lastPos = pos
        lastAt = now
        lastPlaying = playing

        val sentArt = if (json.has("art")) cachedArt else null
        Sender.post(this, json) { resp ->
            if (resp != null) {
                if (sentArt != null) artSent = sentArt
                // ПК перезапустился и обложки у него нет, отправляем заново
                if (resp.optBoolean("needArt")) {
                    handler.post {
                        artSent = null
                        push(true)
                    }
                }
            }
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    /** Обложка 144x144 в PNG (base64). Кэшируется, пока плеер отдаёт тот же Bitmap. */
    private fun artFor(bmp: Bitmap?): String? {
        if (bmp == null || bmp.isRecycled) return null
        if (bmp === lastBitmap && cachedArt != null) return cachedArt
        return try {
            val scaled = Bitmap.createScaledBitmap(bmp, 144, 144, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            lastBitmap = bmp
            cachedArt = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            cachedArt
        } catch (_: Exception) {
            null
        }
    }
}
