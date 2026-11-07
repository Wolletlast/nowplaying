package com.example.nowplaying

import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock

/** Общие функции для службы (отправка на ПК) и экрана плеера в самом приложении. */
object MediaUtil {
    private val LIKE = Regex("like|heart|favou?rite|love|нравит|лайк|избран|сердц", RegexOption.IGNORE_CASE)
    private val DISLIKE = Regex("dislike|thumbs?[ _-]?down|дизлайк|не нравит|hate|ban", RegexOption.IGNORE_CASE)
    private val LIKED = Regex("unlike|remove|убрат|удал|убери|liked", RegexOption.IGNORE_CASE)

    /** Выбираем играющую сессию, иначе любую с метаданными. */
    fun pick(list: List<MediaController>): MediaController? =
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.metadata != null }

    /**
     * «Лайк» у плееров реализован как кастомное действие. Ищем его по названию.
     * Работает у плееров, которые выводят кнопку лайка в уведомлении (Яндекс Музыка, Spotify и др.).
     */
    fun likeAction(state: PlaybackState?): PlaybackState.CustomAction? =
        state?.customActions?.firstOrNull { a ->
            val t = "${a.action} ${a.name}"
            LIKE.containsMatchIn(t) && !DISLIKE.containsMatchIn(t)
        }

    fun isLiked(a: PlaybackState.CustomAction?): Boolean =
        a != null && LIKED.containsMatchIn("${a.action} ${a.name}")

    /** Текущая позиция воспроизведения в мс с учётом времени, прошедшего с последнего обновления. */
    fun position(state: PlaybackState?): Long {
        if (state == null) return 0
        var p = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val delta = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            p += (delta * state.playbackSpeed).toLong()
        }
        return p.coerceAtLeast(0)
    }

    fun execute(c: MediaController, cmd: String, pos: Long = 0) {
        val t = c.transportControls
        when (cmd) {
            "play" -> t.play()
            "pause" -> t.pause()
            "toggle" ->
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            "next" -> t.skipToNext()
            "prev" -> t.skipToPrevious()
            "seek" -> t.seekTo(pos)
            "like" -> likeAction(c.playbackState)?.let { t.sendCustomAction(it.action, it.extras) }
        }
    }
}
